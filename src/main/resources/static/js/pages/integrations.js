import { api } from "../api.js";
import { EXPORT_FORMATS, exportCatalog, exportMarkdownFolder, openPath } from "../actions.js";
import { on } from "../realtime.js";
import { store } from "../store.js";
import { confirmDialog, debounce, errorMessage, fill, fmt, h, icon, menu, toast } from "../ui.js";

/* Integrações: pastas monitoradas (importação automática), webhooks e atalhos para outros programas. */

const FORMATS = { json: "JSON", slack: "Slack", discord: "Discord" };

export async function mount({ root, navigate }) {
  const view = h("div");
  root.append(h("div.page-head", h("div", h("span.eyebrow", "Aplicativo"), h("h1", "Integrações"),
    h("p", "Conecte o Lume às pastas e aos serviços que você já usa: importação automática, avisos para outros sistemas e exportação para outros programas."))), view);

  let data = await api("integrations");

  const save = async (fn, message) => {
    try {
      const result = await fn();
      if (result?.watch_folders) data = result;
      else data = await api("integrations");
      if (message) toast(message);
      render();
      return result;
    } catch (err) {
      toast(errorMessage(err), "err");
      return null;
    }
  };

  function toggle(on, label, onChange) {
    const sw = h("button.switch", { type: "button", role: "switch", "aria-checked": String(on), "aria-label": label, title: on ? "Ativa" : "Pausada" });
    sw.addEventListener("click", () => onChange(!on));
    return sw;
  }

  /* ----- Pastas monitoradas ----- */
  function folderRow(f) {
    const colBtn = h("button.btn.ghost.sm", { type: "button", title: "Coleção de destino" }, icon("collection", "sm"), f.collection_name || "Sem coleção");
    colBtn.addEventListener("click", () => menu(colBtn, [
      { label: "Importar para a coleção", header: true },
      { label: "Nenhuma (só a biblioteca)", checked: !f.collection, onClick: () => save(() => api(`integrations/watch-folders/${f.id}`, { method: "PATCH", body: { collection: null } })) },
      ...store.collections.map((c) => ({ label: c.name, checked: f.collection === c.id, onClick: () => save(() => api(`integrations/watch-folders/${f.id}`, { method: "PATCH", body: { collection: c.id } })) })),
    ]));
    const details = [
      f.exists === false ? "Pasta não encontrada" : null,
      `${fmt.n(f.imported_total || 0)} importados`,
      f.last_scan_at ? `verificada ${fmt.ago(f.last_scan_at)}` : "ainda não verificada",
    ].filter(Boolean).join(" · ");
    return h("div.integration",
      h("span.ic", icon(f.exists === false ? "alert" : "folder-sync")),
      h("div", { style: { minWidth: 0 } }, h("strong", { title: f.path }, f.path), h("span.sub", details)),
      h("div.actions",
        colBtn,
        toggle(f.enabled, "Monitorar esta pasta", (enabled) => save(() => api(`integrations/watch-folders/${f.id}`, { method: "PATCH", body: { enabled } }), enabled ? "Pasta monitorada de novo" : "Monitoramento pausado")),
        h("button.btn.ghost.icon.sm", { type: "button", "aria-label": "Abrir no Explorador", title: "Abrir no Explorador", onclick: () => openPath(f.path) }, icon("external", "sm")),
        h("button.btn.ghost.icon.sm", { type: "button", "aria-label": "Parar de monitorar", title: "Parar de monitorar", onclick: async () => {
          if (await confirmDialog({ title: "Parar de monitorar?", text: `A pasta “${f.path}” não será mais verificada. Os documentos que já entraram continuam na biblioteca.`, ok: "Parar" })) {
            save(() => api(`integrations/watch-folders/${f.id}`, { method: "DELETE" }), "Pasta removida");
          }
        } }, icon("trash", "sm"))));
  }

  async function addFolder() {
    const result = await save(() => api("integrations/watch-folders", { method: "POST", body: {} }));
    if (result?.watch_folders) toast("Pasta adicionada. Os documentos que já estão nela entram na próxima verificação.");
  }

  async function scanNow(b) {
    b.disabled = true;
    try {
      const r = await api("integrations/watch-folders/scan", { method: "POST" });
      toast(r.imported ? `${fmt.n(r.imported)} ${r.imported === 1 ? "documento novo" : "documentos novos"}` : `Nada novo${r.failed ? ` · ${r.failed} com erro` : ""}`);
      data = await api("integrations");
      render();
    } catch (err) {
      toast(errorMessage(err), "err");
    } finally {
      b.disabled = false;
    }
  }

  /* ----- Webhooks ----- */
  function hookRow(w) {
    const events = w.events.map((e) => data.webhook_events.find((x) => x.id === e)?.label || e).join(", ");
    const status = w.last_at ? (w.last_error ? `falhou ${fmt.ago(w.last_at)}: ${w.last_error}` : `último envio ${fmt.ago(w.last_at)} (HTTP ${w.last_status})`) : "nenhum envio ainda";
    return h("div.integration",
      h("span.ic", icon(w.last_error ? "alert" : "webhook")),
      h("div", { style: { minWidth: 0 } }, h("strong.mono", { title: w.url, style: { fontFamily: "var(--mono)", fontSize: "12.5px" } }, w.url),
        h("span.sub", `${FORMATS[w.format] || w.format}${w.has_secret ? " · assinado" : ""} · ${events}`),
        h("span.sub", { style: { color: w.last_error ? "var(--danger)" : null } }, status)),
      h("div.actions",
        toggle(w.enabled, "Webhook ativo", (enabled) => save(() => api(`integrations/webhooks/${w.id}`, { method: "PATCH", body: { enabled } }))),
        h("button.btn.ghost.sm", { type: "button", onclick: async (e) => {
          const b = e.currentTarget;
          b.disabled = true;
          try {
            const r = await api(`integrations/webhooks/${w.id}/test`, { method: "POST" });
            toast(r.message || (r.ok ? "Enviado" : "Falhou"), r.ok ? "" : "err");
            data = await api("integrations");
            render();
          } catch (err) {
            toast(errorMessage(err), "err");
          } finally {
            b.disabled = false;
          }
        } }, icon("send", "sm"), "Testar"),
        h("button.btn.ghost.icon.sm", { type: "button", "aria-label": "Remover webhook", title: "Remover", onclick: async () => {
          if (await confirmDialog({ title: "Remover webhook?", text: w.url, ok: "Remover", danger: true })) {
            save(() => api(`integrations/webhooks/${w.id}`, { method: "DELETE" }), "Webhook removido");
          }
        } }, icon("trash", "sm"))));
  }

  function hookForm() {
    const url = h("input.input", { type: "url", placeholder: "https://hooks.slack.com/... · https://discord.com/api/webhooks/... · https://seu-n8n/webhook/...", "aria-label": "Endereço do webhook" });
    const format = h("select.input", { "aria-label": "Formato" },
      h("option", { value: "" }, "Formato automático"), Object.entries(FORMATS).map(([v, l]) => h("option", { value: v }, l)));
    const secret = h("input.input", { type: "password", autocomplete: "off", placeholder: "Segredo para assinar (opcional)", "aria-label": "Segredo" });
    const checks = data.webhook_events.map((e) => {
      const box = h("input", { type: "checkbox", value: e.id, checked: e.id === "document.ready" });
      return { box, el: h("label", box, e.label) };
    });
    const submit = async () => {
      const events = checks.filter((c) => c.box.checked).map((c) => c.box.value);
      if (!url.value.trim()) return url.focus();
      const body = { url: url.value.trim(), events };
      if (format.value) body.format = format.value;
      if (secret.value.trim()) body.secret = secret.value.trim();
      await save(() => api("integrations/webhooks", { method: "POST", body }), "Webhook adicionado. Use “Testar” para conferir.");
    };
    return h("div", { style: { marginTop: "14px", paddingTop: "14px", borderTop: "1px solid var(--line)" } },
      h("div.field", h("label", "Novo webhook"), url),
      h("div.events", checks.map((c) => c.el)),
      h("div", { style: { display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr) auto", gap: "8px" } }, format, secret,
        h("button.btn.primary", { type: "button", onclick: submit }, icon("plus", "sm"), "Adicionar")));
  }

  const sample = `POST <seu endereço>
Content-Type: application/json
X-Lume-Event: document.ready
X-Lume-Signature: sha256=<HMAC-SHA256 do corpo com o segredo>

{
  "event": "document.ready",
  "sent_at": "2026-10-27T13:00:00Z",
  "app": "Lume",
  "document": { "id": "…", "title": "Relatório anual", "author": "…",
                "kind": "pdf", "page_count": 42, "summary": "…", "tags": ["finanças"] }
}`;

  function render() {
    const ai = store.ai || {};
    fill(view,
      h("div.two-col",
        h("section.panel", h("div.panel-head", h("h2", "Pastas monitoradas"),
          h("div.chips", h("button.btn.sm", { type: "button", onclick: (e) => scanNow(e.currentTarget) }, icon("refresh", "sm"), "Verificar agora"),
            h("button.btn.sm.primary", { type: "button", onclick: addFolder }, icon("folder-plus", "sm"), "Adicionar pasta…"))),
          h("div.panel-body",
            h("p.muted", { style: { fontSize: "13px" } }, `PDFs e DOCX novos nestas pastas entram sozinhos na biblioteca (verificação a cada ${data.scan_interval_s || 60} s enquanto o Lume está aberto). Ideal para Downloads, OneDrive, Google Drive, Dropbox ou uma pasta do scanner.`),
            data.watch_folders.length ? h("div", { style: { marginTop: "6px" } }, data.watch_folders.map(folderRow))
              : h("div.empty", { style: { padding: "26px 10px" } }, h("p", "Nenhuma pasta monitorada ainda."),
                h("button.btn", { type: "button", onclick: addFolder }, icon("folder-plus", "sm"), "Escolher uma pasta")))),
        h("section.panel", h("div.panel-head", h("h2", "Inteligência artificial"), h(`span.status-pill.${ai.configured ? "ok" : "err"}`, ai.label || "Resumo automático")),
          h("div.panel-body",
            h("p.muted", { style: { fontSize: "13px", marginBottom: "12px" } }, "Descrições, etiquetas e o “Pergunte ao documento” usam o provedor escolhido. Sem internet, o Lume usa o resumo local."),
            h("div.chips", ["Claude", "OpenAI", "Google Gemini", "Groq", "OpenRouter", "Mistral", "DeepSeek", "Ollama", "LM Studio"].map((n) => h("span.tag", n))),
            h("button.btn.sm", { type: "button", style: { marginTop: "14px" }, onclick: () => navigate("/preferencias#ia") }, icon("settings", "sm"), "Configurar o provedor")))),
      h("section.panel", { style: { marginBottom: "18px" } },
        h("div.panel-head", h("h2", "Webhooks"), h("span.muted", { style: { fontSize: "12.5px" } }, "n8n · Make · Zapier · Slack · Discord · Teams · seu servidor")),
        h("div.panel-body",
          h("p.muted", { style: { fontSize: "13px" } }, "Avise outros sistemas quando algo acontecer no Lume. Os envios saem do seu computador direto para o endereço informado, em segundo plano."),
          data.webhooks.length ? h("div", { style: { marginTop: "6px" } }, data.webhooks.map(hookRow)) : null,
          hookForm(),
          h("details", { style: { marginTop: "14px" } }, h("summary", { style: { cursor: "pointer", fontSize: "13px", color: "var(--ink-2)" } }, "Formato do envio (JSON)"),
            h("pre.code-sample", { style: { marginTop: "10px" } }, sample)))),
      h("section.panel",
        h("div.panel-head", h("h2", "Outros programas")),
        h("div.panel-body",
          EXPORT_FORMATS.map((f) => h("div.integration",
            h("span.ic", icon(f.icon)),
            h("div", h("strong", f.hint), h("span.sub", `Catálogo em ${f.label}`)),
            h("div.actions", h("button.btn.sm", { type: "button", onclick: () => exportCatalog(f.id) }, icon("file-down", "sm"), "Exportar…")))),
          h("div.integration",
            h("span.ic", icon("file-text")),
            h("div", h("strong", "Obsidian, Logseq, Notion e Joplin"), h("span.sub", "Uma nota Markdown por documento, com destaques e notas")),
            h("div.actions", h("button.btn.sm", { type: "button", onclick: () => exportMarkdownFolder() }, icon("folder-open", "sm"), "Escolher pasta…"))),
          h("div.integration",
            h("span.ic", icon("app-window")),
            h("div", h("strong", "Explorador do Windows"), h("span.sub", "Clique com o botão direito num PDF → Abrir com → Lume, ou arraste arquivos e pastas para a janela")),
            h("div.actions")))),
    );
  }

  render();
  const soon = debounce(async () => {
    try {
      data = await api("integrations");
      render();
    } catch {
      /* tela fechada */
    }
  }, 600);
  const offs = [on("watch.imported", soon), on("system.changed", soon)];
  return () => offs.forEach((off) => off());
}
