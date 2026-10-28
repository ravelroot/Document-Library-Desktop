import { api } from "../api.js";
import { EXPORT_FORMATS, backup, exportCatalog, exportMarkdownFolder, openPath } from "../actions.js";
import { refreshAll } from "../store.js";
import { confirmDialog, errorMessage, fill, fmt, h, icon, toast } from "../ui.js";

/* Ferramentas: backup, exportações e manutenção da biblioteca. Tudo roda no computador, sem servidor na nuvem. */

export async function mount({ root }) {
  root.append(h("div.page-head", h("div", h("span.eyebrow", "Biblioteca"), h("h1", "Ferramentas"),
    h("p", "Backup, exportação para outros programas e manutenção. Nada sai do seu computador."))));

  function tool({ ic, title, text, actions, result }) {
    return h("article.tool", h("span.ic", icon(ic)), h("h3", title), h("p", text), h("div.chips", actions), result || null);
  }

  async function running(btn, fn) {
    btn.disabled = true;
    const label = btn.lastChild.textContent;
    btn.lastChild.textContent = "Aguarde…";
    try {
      return await fn();
    } catch (err) {
      toast(errorMessage(err), "err");
      return null;
    } finally {
      btn.disabled = false;
      btn.lastChild.textContent = label;
    }
  }

  const btn = (label, ic, onClick, kind = "") => {
    const b = h(`button.btn.sm${kind}`, { type: "button" }, icon(ic, "sm"), h("span", label));
    b.addEventListener("click", () => onClick(b));
    return b;
  };

  /* ----- Backup e exportação ----- */
  const backupResult = h("div.result");
  const notesResult = h("div.result");
  const showSaved = (box, path, extra) => fill(box, h("div", icon("check", "sm"), " ", extra),
    h("button.btn.ghost.sm", { type: "button", style: { marginTop: "6px" }, onclick: () => openPath(path) }, icon("folder-open", "sm"), "Mostrar no Explorador"));

  const exportCard = tool({
    ic: "file-down",
    title: "Exportar o catálogo",
    text: "Título, autor, páginas, etiquetas, estante, coleções e descrição de todos os documentos, no formato do programa que você usa.",
    actions: EXPORT_FORMATS.map((f) => btn(f.label, f.icon, (b) => running(b, () => exportCatalog(f.id)))),
  });
  exportCard.append(h("p.muted", { style: { fontSize: "12px" } }, EXPORT_FORMATS.map((f) => `${f.label.replace(/ \(.*/, "")}: ${f.hint}`).join(" · ")));

  /* ----- Manutenção ----- */
  const checkResult = h("div");
  const maintResult = h("div.result");

  async function maintenance(action) {
    return api("tools/maintenance", { method: "POST", body: { action } });
  }

  function line(ok, text) {
    return h("div", h(`span.${ok ? "ok" : "err"}`, icon(ok ? "check" : "alert", "sm")), h("span", text));
  }

  async function check(b) {
    const r = await running(b, () => maintenance("check"));
    if (!r) return;
    const problems = r.missing_files.length + r.orphan_folders + r.temp_files;
    fill(checkResult, h("div.check-list",
      line(r.integrity === "ok", r.integrity === "ok" ? "Banco de dados íntegro (PRAGMA integrity_check)" : `Banco de dados: ${r.integrity}`),
      line(!r.missing_files.length, r.missing_files.length ? `${r.missing_files.length} documento(s) sem o arquivo: ${r.missing_files.slice(0, 3).map((d) => d.title).join(", ")}` : `Os ${fmt.n(r.documents)} documentos têm o arquivo guardado`),
      line(!r.orphan_folders, r.orphan_folders ? `${r.orphan_folders} pasta(s) sobrando (${fmt.bytes(r.orphan_bytes)}) — use “Limpar sobras”` : "Nenhuma pasta sobrando"),
      line(!r.temp_files, r.temp_files ? `${r.temp_files} arquivo(s) temporário(s) de importações interrompidas` : "Nenhum temporário esquecido")),
      problems ? null : h("p.muted", { style: { fontSize: "12.5px", marginTop: "6px" } }, "Tudo certo com a sua biblioteca."));
  }

  const maint = (title, ic, text, label, action, done, confirm) => tool({
    ic, title, text,
    actions: [btn(label, ic, async (b) => {
      if (confirm && !(await confirmDialog(confirm))) return;
      const r = await running(b, () => maintenance(action));
      if (r) {
        fill(maintResult, h("div", icon("check", "sm"), " ", done(r)));
        toast(done(r));
        refreshAll().catch(() => {});
      }
    })],
  });

  root.append(
    h("div.section-label", "Backup e exportação"),
    h("div.tool-grid",
      tool({
        ic: "archive",
        title: "Backup completo",
        text: "Banco de dados, documentos, capas, anotações e progresso num único arquivo .zip. Para restaurar, feche o Lume e extraia o .zip na pasta de dados.",
        actions: [btn("Fazer backup…", "archive", async (b) => {
          const r = await running(b, backup);
          if (r) showSaved(backupResult, r.path, `${fmt.n(r.documents)} documentos · ${fmt.bytes(r.bytes)}`);
        }, ".primary")],
        result: backupResult,
      }),
      exportCard,
      tool({
        ic: "file-text",
        title: "Notas para Obsidian e Notion",
        text: "Uma nota Markdown por documento, com propriedades (YAML), descrição, etiquetas e todos os destaques e notas. Funciona com Obsidian, Logseq, Notion e Joplin.",
        actions: [btn("Escolher a pasta…", "folder-open", async (b) => {
          const r = await running(b, exportMarkdownFolder);
          if (r) showSaved(notesResult, r.folder, `${fmt.n(r.files)} notas criadas`);
        })],
        result: notesResult,
      })),
    h("div.section-label", "Manutenção"),
    h("div.tool-grid",
      tool({
        ic: "shield-check",
        title: "Verificar a biblioteca",
        text: "Confere o banco de dados, se cada documento ainda tem o arquivo guardado e se sobrou alguma pasta ou temporário.",
        actions: [btn("Verificar agora", "shield-check", check)],
        result: checkResult,
      }),
      maint("Reconstruir o índice de busca", "search", "Refaz a busca em texto completo (FTS5) de todos os documentos. Útil se algum resultado parecer faltar.",
        "Reconstruir", "reindex", (r) => `Índice refeito para ${fmt.n(r.reindexed)} documentos`),
      maint("Otimizar o banco de dados", "database", "Compacta o lume.db e atualiza as estatísticas do SQLite. Leva alguns segundos em bibliotecas grandes.",
        "Otimizar", "optimize", (r) => `Banco otimizado: ${fmt.bytes(r.before_bytes)} → ${fmt.bytes(r.after_bytes)}`),
      maint("Limpar sobras", "eraser", "Apaga pastas de documentos que já foram excluídos e temporários de importações interrompidas.",
        "Limpar", "cleanup", (r) => `${fmt.n(r.removed)} itens apagados · ${fmt.bytes(r.freed_bytes)} liberados`),
      maint("Descrever tudo de novo", "sparkles", "Gera de novo a descrição e as etiquetas de todos os documentos com o provedor de IA atual (Preferências → Inteligência artificial).",
        "Gerar de novo", "redescribe", (r) => `${fmt.n(r.requeued)} documentos na fila da descrição`,
        { title: "Descrever todos os documentos de novo?", text: "As descrições e etiquetas sugeridas pela IA serão substituídas. As etiquetas que você escolheu continuam. Com um provedor pago, cada documento conta como uma chamada.", ok: "Gerar de novo" })),
    maintResult,
  );
  return null;
}
