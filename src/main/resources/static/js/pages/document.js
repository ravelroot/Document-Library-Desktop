import { api } from "../api.js";
import { documentCard, progressOf, sourceLabel } from "../components/card.js";
import { on } from "../realtime.js";
import { refreshCollections, store } from "../store.js";
import { SHELVES, confirmDialog, cover, errorMessage, fmt, formDialog, h, icon, menu, toast, fill } from "../ui.js";

export async function mount({ root, params, navigate }) {
  let doc = await api(`documents/${params.id}`);
  const view = h("div");
  root.append(view);

  /* ----- Pergunte ao documento (criado uma vez para não perder a conversa a cada atualização) ----- */
  const askInput = h("input.input", { type: "text", maxlength: 500, placeholder: "Ex.: Quais são as principais conclusões?", "aria-label": "Pergunta sobre o documento" });
  const askButton = h("button.btn.primary", { type: "submit" }, icon("send", "sm"), "Perguntar");
  const askOut = h("div", { "aria-live": "polite" });
  const SUGGESTIONS = ["Qual é o assunto principal?", "Quais são as conclusões?", "Que datas e prazos aparecem?", "Quais números são citados?"];
  const askForm = h("form.ask-form", { onsubmit: (e) => { e.preventDefault(); ask(askInput.value); } }, askInput, askButton);
  const askBox = h("section.block.ask",
    h("h2", icon("message", "sm"), "Pergunte ao documento"),
    h("p.muted", { style: { fontSize: "13px", marginBottom: "10px" } }, "Respostas com os trechos de onde saíram. Funciona sem internet; com um provedor de IA configurado, a resposta vem escrita."),
    askForm,
    h("div.ask-suggest", SUGGESTIONS.map((q) => h("button", { type: "button", onclick: () => { askInput.value = q; ask(q); } }, q))),
    askOut);

  async function ask(question) {
    const q = String(question || "").trim();
    if (q.length < 3) {
      askInput.focus();
      return;
    }
    askButton.disabled = true;
    fill(askOut, h("div.notice.info", { style: { marginTop: "14px" } }, h("span.spinner"), h("div", "Procurando a resposta no texto…")));
    try {
      const r = await api(`documents/${doc.id}/ask`, { method: "POST", body: { question: q } });
      const local = r.mode === "local";
      const passages = r.passages || [];
      // No modo local a resposta já é o melhor trecho: ele não se repete na lista abaixo.
      const probe = r.answer.slice(0, 60);
      const top = local && passages[0] && (passages[0].text.includes(probe) || r.answer.includes(passages[0].text.slice(0, 60))) ? passages[0] : null;
      const others = top ? passages.slice(1) : passages;
      const pageLink = (p) => p?.page ? h("a", { href: `/ler/${doc.id}?p=${p.page}` }, `abrir na página ${p.page}`) : null;
      fill(askOut,
        h("div.ask-answer",
          h("div.label", icon(local ? "search" : "sparkles", "sm"), local ? "Trecho mais relevante" : "Resposta",
            h("span", local ? "busca no texto, sem internet" : r.model || r.mode)),
          h("p", r.answer),
          top?.page ? h("div.passage-link", pageLink(top)) : null),
        r.warning ? h("p.muted", { style: { fontSize: "12.5px", marginTop: "8px" } }, icon("info", "sm"), " ", r.warning) : null,
        others.length ? [h("div.section-label", { style: { marginTop: "16px" } }, local ? "Outros trechos" : "Trechos usados na resposta"),
          h("div.passages", others.map((p, i) => h("div.passage", h("span.n", `[${top ? i + 2 : i + 1}]`),
            h("div", h("span", p.text), p.page ? h("div", pageLink(p)) : null))))] : null);
    } catch (err) {
      fill(askOut, h("div.notice", { style: { marginTop: "14px" } }, icon("alert"), h("div", errorMessage(err))));
    } finally {
      askButton.disabled = false;
    }
  }

  /* ----- Parecidos na biblioteca ----- */
  const relatedBox = h("section.block", { hidden: true });
  async function loadRelated() {
    try {
      const items = await api(`documents/${doc.id}/related`, { query: { limit: 6 } });
      relatedBox.hidden = !items.length;
      fill(relatedBox, h("h2", icon("layers", "sm"), "Parecidos na sua biblioteca"),
        h("div.related", items.map((d) => documentCard(d))));
    } catch {
      relatedBox.hidden = true;
    }
  }

  const reload = async () => {
    doc = await api(`documents/${params.id}`);
    render();
  };

  async function setReading(body) {
    try {
      doc.reading = await api(`documents/${doc.id}/reading`, { method: "PATCH", body });
      render();
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  async function edit() {
    const updated = await formDialog({
      title: "Editar informações",
      fields: [
        { name: "title", label: "Título", value: doc.title, required: true, maxlength: 300 },
        { name: "author", label: "Autor", value: doc.author, maxlength: 200 },
        { name: "description", label: "Sua descrição", value: doc.description, type: "textarea", hint: "Opcional. Aparece abaixo da descrição por IA e entra na busca." },
      ],
      onSubmit: (values) => api(`documents/${doc.id}`, { method: "PATCH", body: values }),
    });
    if (updated) {
      doc = updated;
      render();
      toast("Informações salvas");
    }
  }

  async function editTags() {
    const updated = await formDialog({
      title: "Etiquetas",
      fields: [{ name: "tags", label: "Etiquetas separadas por vírgula", value: doc.tags.map((t) => t.name).join(", "), hint: "Até 12. As sugeridas pela IA são substituídas pelas suas." }],
      onSubmit: (values) => api(`documents/${doc.id}/tags`, { method: "PUT", body: { tags: values.tags.split(",").map((t) => t.trim()).filter(Boolean) } }),
    });
    if (updated) reload();
  }

  async function remove() {
    const ok = await confirmDialog({
      title: "Excluir documento?",
      text: `“${doc.title}” sai da biblioteca junto com as anotações e o progresso de leitura. O arquivo de onde ele foi importado continua onde estava.`,
      ok: "Excluir",
      danger: true,
    });
    if (!ok) return;
    await api(`documents/${doc.id}`, { method: "DELETE" });
    toast("Documento excluído");
    navigate("/");
  }

  async function toggleCollection(collection, inside) {
    try {
      if (inside) await api(`collections/${collection.id}/documents/${doc.id}`, { method: "DELETE" });
      else await api(`collections/${collection.id}/documents`, { method: "POST", body: { document_ids: [doc.id] } });
      await refreshCollections();
      reload();
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  async function newCollection() {
    const created = await formDialog({
      title: "Nova coleção",
      fields: [{ name: "name", label: "Nome", required: true, maxlength: 80, placeholder: "Ex.: Leituras do trimestre" }],
      submit: "Criar",
      onSubmit: (values) => api("collections", { method: "POST", body: values }),
    });
    if (created) toggleCollection(created, false);
  }

  function readerHref(item) {
    if (doc.kind === "pdf" && item?.page) return `/ler/${doc.id}?p=${item.page}`;
    if (item?.anchor) return `/ler/${doc.id}#${item.anchor}`;
    return `/ler/${doc.id}`;
  }

  async function desktopAction(fn, okMessage) {
    try {
      const result = await fn();
      if (result?.ok === false && result.error) toast(result.error, "err");
      else if (result?.ok && okMessage) toast(okMessage);
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  async function exportNotes() {
    try {
      const { filename, content } = await api(`documents/${doc.id}/annotations/export`);
      const saved = await window.lume.saveText(filename, content);
      if (saved.ok) toast("Anotações salvas");
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  function render() {
    const progress = progressOf(doc);
    const reading = doc.reading || {};
    const ready = doc.status === "ready";
    const processing = ["queued", "extracting", "describing"].includes(doc.status);
    const facts = [
      [icon("file", "sm"), doc.kind.toUpperCase()],
      doc.page_count ? [icon("book", "sm"), `${fmt.n(doc.page_count)} páginas`] : null,
      doc.word_count ? [icon("list", "sm"), `${fmt.n(doc.word_count)} palavras`] : null,
      [icon("disk", "sm"), fmt.bytes(doc.size_bytes)],
      doc.language ? [icon("globe", "sm"), fmt.lang(doc.language)] : null,
    ].filter(Boolean);

    const more = h("button.btn.icon", { "aria-label": "Mais ações", title: "Mais ações" }, icon("more"));
    more.addEventListener("click", () => menu(more, [
      { label: "Editar informações", icon: "pencil", onClick: edit },
      { label: `Abrir no programa padrão`, icon: "external", onClick: () => desktopAction(() => window.lume.openFile(doc.id)) },
      { label: "Mostrar na pasta", icon: "folder-open", onClick: () => desktopAction(() => window.lume.showInFolder(doc.id)) },
      { label: "Exportar anotações (Markdown)", icon: "file-down", onClick: exportNotes },
      { label: "Copiar o caminho do arquivo", icon: "copy", onClick: async () => {
        try {
          await navigator.clipboard.writeText(doc.source_path || doc.original_name);
          toast("Caminho copiado");
        } catch {
          toast("Não foi possível copiar", "err");
        }
      } },
      { label: "Ler o arquivo de novo", icon: "refresh", onClick: async () => {
        await api(`documents/${doc.id}/reprocess`, { method: "POST" });
        toast("O documento voltou para a fila");
        reload();
      } },
      "-",
      { label: "Excluir da biblioteca", icon: "trash", danger: true, onClick: remove },
    ]));

    const aside = h("aside.detail-aside",
      h("div", { style: { viewTransitionName: "doc-cover" } }, cover(doc)),
      progress > 0 ? h("div", h("div.bar", h("i", { style: { width: `${progress * 100}%` } })),
        h("p.muted", { style: { fontSize: "12.5px", marginTop: "6px" } },
          progress >= 1 ? "Leitura concluída" : doc.kind === "pdf" && doc.page_count ? `Página ${reading.page} de ${doc.page_count} · ${Math.round(progress * 100)}%` : `${Math.round(progress * 100)}% lido`)) : null,
      h("div.actions",
        h("a.btn.primary", { href: `/ler/${doc.id}`, "aria-disabled": ready ? null : "true" }, icon("book", "sm"), progress > 0 && progress < 1 ? "Continuar lendo" : "Ler agora"),
        h("button.btn.icon", { "aria-label": "Salvar uma cópia do arquivo", title: "Salvar uma cópia do arquivo", onclick: () => desktopAction(() => window.lume.saveCopy(doc.id), "Cópia salva") }, icon("download")),
        more),
    );

    const collectionsBlock = h("section.block", h("h2", "Coleções"),
      h("div.collections-pick",
        store.collections.map((c) => {
          const inside = doc.collections.includes(c.id);
          return h(`button.chip${inside ? ".on" : ""}`, { type: "button", onclick: () => toggleCollection(c, inside) }, inside ? icon("check", "sm") : icon("plus", "sm"), c.name);
        }),
        h("button.chip", { type: "button", onclick: newCollection }, icon("folder-plus", "sm"), "Nova coleção")));

    const metaRows = [
      ["Arquivo original", doc.original_name],
      doc.source_path ? ["Importado de", h("span", { style: { wordBreak: "break-all" } }, doc.source_path)] : null,
      ["Adicionado em", fmt.dateTime(doc.created_at)],
      ["Processado em", fmt.dateTime(doc.processed_at)],
      doc.metadata?.subject ? ["Assunto", doc.metadata.subject] : null,
      doc.metadata?.keywords ? ["Palavras-chave", doc.metadata.keywords] : null,
      doc.metadata?.producer ? ["Gerado por", doc.metadata.producer] : null,
      doc.metadata?.creator ? ["Programa de origem", doc.metadata.creator] : null,
      doc.metadata?.created_at ? ["Criado em", fmt.dateTime(doc.metadata.created_at)] : null,
      ["Impressão digital (SHA-256)", h("code", { style: { fontFamily: "var(--mono)", fontSize: "12px" } }, doc.sha256)],
    ].filter(Boolean);

    fill(view, 
      h("a.back", { href: "/", onclick: (e) => { if (history.length > 1) { e.preventDefault(); history.back(); } } }, icon("arrow-left", "sm"), "Voltar"),
      h("div.detail",
        aside,
        h("article",
          h("h1", doc.title),
          doc.author ? h("p.by", doc.author) : null,
          h("div.facts", facts.map(([i, t]) => h("span", i, t))),
          doc.status === "failed" ? h("div.notice", { style: { marginTop: "18px" } }, icon("alert"), h("div", h("strong", "Não foi possível ler este arquivo. "), doc.status_detail || "")) : null,
          processing ? h("div.notice.info", { style: { marginTop: "18px" } }, h("span.spinner"), h("div", h("strong", `${doc.status_label}… `), "Esta página se atualiza sozinha quando terminar.")) : null,

          h("section.block",
            h("h2", icon("sparkles", "sm"), "Descrição por IA",
              ready ? h("button.btn.ghost.sm", { onclick: async () => {
                await api(`documents/${doc.id}/describe`, { method: "POST" });
                toast("Gerando a descrição de novo");
                reload();
              } }, icon("refresh", "sm"), "Gerar de novo") : null),
            h(`div.ai-box${doc.summary ? "" : ".pending"}`,
              h("div.label", icon("sparkles"), sourceLabel(doc) || "Aguardando", doc.summary_at ? h("span", fmt.ago(doc.summary_at)) : null),
              h("p", doc.summary || (processing ? "A descrição aparece aqui assim que o documento terminar de ser lido." : "Sem descrição.")))),

          doc.description ? h("section.block", h("h2", "Sua descrição"), h("p", { style: { whiteSpace: "pre-line" } }, doc.description)) : null,

          ready ? askBox : null,

          h("section.block",
            h("h2", "Etiquetas", h("button.btn.ghost.sm", { onclick: editTags }, icon("pencil", "sm"), "Editar")),
            doc.tags.length
              ? h("div.chips", doc.tags.map((t) => h(`a.tag${t.source === "ia" ? ".ai" : ""}`, { href: `/?tag=${t.slug}`, title: t.source === "ia" ? "Sugerida pela IA" : "Escolhida por você" }, t.source === "ia" ? icon("sparkles", "sm") : null, t.name)))
              : h("p.muted", "Nenhuma etiqueta.")),

          h("section.block", h("h2", "Minha leitura"),
            h("div.chips",
              h("div.seg", { role: "group", "aria-label": "Estante" },
                Object.entries(SHELVES).map(([key, label]) => h(`button${reading.shelf === key ? ".on" : ""}`, {
                  type: "button",
                  onclick: () => setReading({ shelf: reading.shelf === key ? "" : key }),
                }, h("span.dot", { style: { width: "8px", height: "8px", borderRadius: "50%", background: `var(--shelf-${key})` } }), label))),
              h(`button.btn.sm${reading.favorite ? ".on" : ""}`, { onclick: () => setReading({ favorite: !reading.favorite }) }, icon("star", "sm"), reading.favorite ? "Favorito" : "Favoritar"),
              reading.last_opened_at ? h("span.muted", { style: { fontSize: "12.5px" } }, `Aberto ${fmt.ago(reading.last_opened_at)}`) : null)),

          collectionsBlock,

          doc.outline?.length ? h("section.block", h("h2", "Sumário"),
            h("ol.outline-list", doc.outline.slice(0, 80).map((item) => h(`li.l${Math.min(item.level || 1, 4)}`,
              h("a", { href: readerHref(item) }, h("span", item.title), item.page ? h("span", item.page) : h("span")))))) : null,

          h("section.block", h("h2", "Detalhes do arquivo"), h("dl.kv", metaRows.flatMap(([k, v]) => [h("dt", k), h("dd", v || "—")]))),

          relatedBox,
        ),
      ),
    );
  }

  render();
  if (doc.status === "ready") loadRelated();
  const offs = [
    on("document.updated", ({ id }) => id === doc.id && reload()),
    on("reading.updated", ({ id }) => id === doc.id && reload()),
    on("document.deleted", ({ id }) => id === doc.id && navigate("/", { replace: true })),
  ];
  return () => offs.forEach((off) => off());
}
