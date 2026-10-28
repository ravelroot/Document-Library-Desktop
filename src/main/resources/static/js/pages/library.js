import { api } from "../api.js";
import { documentCard, documentRow, hidePreview } from "../components/card.js";
import { on } from "../realtime.js";
import { refreshAll, refreshCollections, store, subscribe } from "../store.js";
import { SHELVES, clear, confirmDialog, cover, errorMessage, fmt, formDialog, h, icon, menu, modal, toast, fill } from "../ui.js";
import { pickFiles, pickFolder } from "../upload.js";
import { EXPORT_FORMATS, exportCatalog } from "../actions.js";

const SORTS = [
  ["recent", "Adicionados recentemente"],
  ["last_read", "Lidos recentemente"],
  ["title", "Título (A–Z)"],
  ["author", "Autor (A–Z)"],
  ["pages", "Mais páginas"],
  ["size", "Maior tamanho"],
];

function greeting() {
  const hour = new Date().getHours();
  const part = hour < 5 ? "Boa noite" : hour < 12 ? "Bom dia" : hour < 18 ? "Boa tarde" : "Boa noite";
  return `${part}, ${store.user.name.split(" ")[0]}.`;
}

function today() {
  return new Intl.DateTimeFormat("pt-BR", { weekday: "long", day: "numeric", month: "long" }).format(new Date());
}

const plural = (n, one, many) => `${fmt.n(n)} ${n === 1 ? one : many}`;

/* Resumo em uma frase, com números reais: diz o que está em andamento em vez de uma pergunta genérica. */
function summary(s) {
  const total = s.total || 0;
  if (!total) return ["Sua biblioteca ainda está vazia. Arraste PDFs ou DOCX para esta janela, ou use ", h("b", "Adicionar documentos"), "."];
  const reading = s.shelves?.lendo || 0;
  const parts = [h("b", plural(total, "documento", "documentos")), " neste computador"];
  if (reading) parts.push(", ", h("b", plural(reading, "em leitura", "em leitura")));
  if (s.processing) parts.push(" e ", h("b", plural(s.processing, "sendo preparado", "sendo preparados")));
  parts.push(".");
  if (s.failed) parts.push(` ${plural(s.failed, "arquivo precisa", "arquivos precisam")} de atenção.`);
  return parts;
}

function ledger(s) {
  const item = (label, value) => h("div", h("dt", label), h("dd", fmt.n(value || 0)));
  return h("dl.ledger", { "aria-label": "Resumo da biblioteca" },
    item("Quero ler", s.shelves?.para_ler), item("Lendo", s.shelves?.lendo), item("Lidos", s.shelves?.lido));
}

function viewPref() {
  try {
    return localStorage.getItem("library:view") || "grid";
  } catch {
    return "grid";
  }
}

export async function mount({ root, props, params, navigate }) {
  const collectionId = params.collection || null;
  const scope = collectionId ? "all" : props.scope;
  let collection = null;
  if (collectionId) collection = await api(`collections/${collectionId}`);

  const state = { page: 1, next: null, loading: false, docs: new Map(), view: viewPref(), total: 0, selecting: false, selected: new Set() };
  const results = h("div");
  const sentinel = h("div.more-loader", { hidden: true }, h("span.spinner"));
  const chipsBox = h("div.chips.filters", { role: "toolbar", "aria-label": "Filtros" });
  const activeFilter = h("div");
  const tagsBtn = h("button.btn.sm", { type: "button", title: "Filtrar por etiqueta" }, icon("tag", "sm"), "Etiquetas");
  const selectBtn = h("button.btn.sm", { type: "button", title: "Selecionar vários (S)" }, icon("square-check", "sm"), "Selecionar");
  const bulkbar = h("div.bulkbar", { role: "toolbar", "aria-label": "Ações nos documentos selecionados" });
  document.body.append(bulkbar);
  const sortSelect = h("select.input.select-sm", { "aria-label": "Ordenar" });
  const viewSeg = h("div.seg.icons", { role: "group", "aria-label": "Visualização" });
  const head = h("div");
  const continueBox = h("div");
  let container = null;

  const query = () => Object.fromEntries(new URLSearchParams(location.search));
  const filtersActive = () => {
    const q = query();
    return ["q", "kind", "shelf", "favorite", "status", "tag"].some((k) => q[k]);
  };

  function setParam(key, value) {
    const url = new URL(location.href);
    if (value) url.searchParams.set(key, value);
    else url.searchParams.delete(key);
    history.replaceState({}, "", url.pathname + url.search);
    reload();
  }

  /* ----- Cabeçalho ----- */
  function renderHead() {
    clear(head);
    if (collection) {
      const actions = h("div.actions",
        h("button.btn", { onclick: addDocuments }, icon("plus", "sm"), "Adicionar documentos"),
        h("button.btn.icon", { "aria-label": "Mais ações", onclick: (e) => menu(e.currentTarget, [
          { label: "Renomear coleção", icon: "pencil", onClick: editCollection },
          { label: "Excluir coleção", icon: "trash", danger: true, onClick: deleteCollection },
        ]) }, icon("more")));
      head.append(h("a.back", { href: "/colecoes" }, icon("arrow-left", "sm"), "Coleções"),
        h("div.page-head", h("div", h("h1", collection.name),
          h("p", collection.description || `${fmt.n(collection.document_count)} ${collection.document_count === 1 ? "documento" : "documentos"} · arraste os cartões para mudar a ordem`)), actions));
    } else {
      const s = store.stats || {};
      head.append(h("div.page-head.greeting",
        h("div", h("span.eyebrow", today()), h("h1", greeting()), h("p.summary", ...summary(s))),
        s.total ? ledger(s) : null));
    }
  }

  /* ----- Seleção múltipla e ações em lote ----- */
  const selection = {
    active: () => state.selecting,
    has: (id) => state.selected.has(id),
    toggle(id) {
      if (state.selected.has(id)) state.selected.delete(id);
      else state.selected.add(id);
      if (state.selected.size && !state.selecting) setSelecting(true);
      refreshItem(id);
      renderBulkbar();
    },
  };

  function refreshItem(id) {
    const el = results.querySelector(`[data-id="${id}"]`);
    el?.refresh?.();
  }

  function setSelecting(on) {
    state.selecting = on;
    hidePreview();
    if (!on) {
      const ids = [...state.selected];
      state.selected.clear();
      ids.forEach(refreshItem);
    }
    root.classList.toggle("selecting", on);
    selectBtn.classList.toggle("on", on);
    renderBulkbar();
  }

  function selectAll() {
    const all = [...state.docs.keys()];
    const everything = all.every((id) => state.selected.has(id));
    if (everything) state.selected.clear();
    else all.forEach((id) => state.selected.add(id));
    if (!state.selecting) setSelecting(true);
    all.forEach(refreshItem);
    renderBulkbar();
  }

  async function batch(action, value, message) {
    const ids = [...state.selected];
    if (!ids.length) return;
    try {
      const { affected } = await api("documents/batch", { method: "POST", body: { ids, action, value } });
      toast(message(affected ?? ids.length));
      setSelecting(false);
      refreshAll().catch(() => {});
      reload();
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }
  const docsWord = (n) => `${fmt.n(n)} ${n === 1 ? "documento" : "documentos"}`;

  function renderBulkbar() {
    const n = state.selected.size;
    bulkbar.classList.toggle("show", state.selecting);
    const shelfBtn = h("button.btn.sm", { type: "button" }, icon("shelves", "sm"), "Estante");
    shelfBtn.addEventListener("click", () => menu(shelfBtn, [
      ...Object.entries(SHELVES).map(([key, label]) => ({ label, icon: "book-marked", onClick: () => batch("shelf", key, (k) => `${docsWord(k)} em “${label}”`) })),
      "-",
      { label: "Tirar da estante", icon: "x", onClick: () => batch("shelf", "", (k) => `${docsWord(k)} sem estante`) },
    ]));
    const colBtn = h("button.btn.sm", { type: "button" }, icon("collection", "sm"), "Coleção");
    colBtn.addEventListener("click", () => menu(colBtn, [
      { label: "Adicionar à coleção", header: true },
      ...store.collections.map((c) => ({ label: c.name, icon: "folder-plus", onClick: () => batch("collection_add", c.id, (k) => `${docsWord(k)} em “${c.name}”`) })),
      { label: "Nova coleção…", icon: "plus", onClick: async () => {
        const created = await formDialog({
          title: "Nova coleção",
          fields: [{ name: "name", label: "Nome", required: true, maxlength: 80 }],
          submit: "Criar e adicionar",
          onSubmit: (values) => api("collections", { method: "POST", body: values }),
        });
        if (created) {
          await refreshCollections();
          batch("collection_add", created.id, (k) => `${docsWord(k)} em “${created.name}”`);
        }
      } },
      collection ? "-" : null,
      collection ? { label: `Tirar de “${collection.name}”`, icon: "x", onClick: () => batch("collection_remove", collection.id, (k) => `${docsWord(k)} fora da coleção`) } : null,
    ]));
    const moreBtn = h("button.btn.sm", { type: "button" }, icon("more", "sm"), "Mais");
    moreBtn.addEventListener("click", () => menu(moreBtn, [
      { label: "Favoritar", icon: "star", onClick: () => batch("favorite", true, (k) => `${docsWord(k)} nos favoritos`) },
      { label: "Tirar dos favoritos", icon: "star", onClick: () => batch("unfavorite", true, (k) => `${docsWord(k)} fora dos favoritos`) },
      { label: "Adicionar etiquetas…", icon: "tag", onClick: addTags },
      { label: "Gerar a descrição de novo", icon: "sparkles", onClick: () => batch("redescribe", true, (k) => `${docsWord(k)} na fila da descrição`) },
      { label: "Ler os arquivos de novo", icon: "refresh", onClick: () => batch("reprocess", true, (k) => `${docsWord(k)} na fila`) },
      "-",
      { label: "Exportar seleção", header: true },
      ...EXPORT_FORMATS.map((f) => ({ label: f.label, icon: f.icon, onClick: () => exportCatalog(f.id, [...state.selected]) })),
    ]));
    fill(bulkbar,
      h("span.count", n ? `${fmt.n(n)} ${n === 1 ? "selecionado" : "selecionados"}` : "Clique nos documentos"),
      h("button.btn.sm", { type: "button", onclick: selectAll }, icon("square-check", "sm"), "Todos"),
      h("span.sep"),
      n ? [shelfBtn, colBtn, moreBtn,
        h("button.btn.sm.danger", { type: "button", onclick: removeSelected }, icon("trash", "sm"), "Excluir")] : null,
      h("span.sep"),
      h("button.btn.sm.icon", { type: "button", "aria-label": "Sair da seleção (Esc)", title: "Sair da seleção (Esc)", onclick: () => setSelecting(false) }, icon("x", "sm")));
  }

  async function addTags() {
    const values = await formDialog({
      title: "Adicionar etiquetas",
      fields: [{ name: "tags", label: "Etiquetas separadas por vírgula", required: true, placeholder: "Ex.: trabalho, 2026, revisar", hint: "Somam às etiquetas que cada documento já tem." }],
      submit: "Adicionar",
      onSubmit: async (v) => v,
    });
    if (!values?.tags) return;
    const tags = values.tags.split(",").map((t) => t.trim()).filter(Boolean);
    batch("tags_add", tags, (k) => `Etiquetas adicionadas a ${docsWord(k)}`);
  }

  async function removeSelected() {
    const n = state.selected.size;
    const ok = await confirmDialog({
      title: `Excluir ${docsWord(n)}?`,
      text: "Os documentos saem da biblioteca junto com as anotações e o progresso de leitura. Os arquivos originais continuam onde estavam.",
      ok: "Excluir",
      danger: true,
    });
    if (ok) batch("delete", true, (k) => `${docsWord(k)} ${k === 1 ? "excluído" : "excluídos"}`);
  }

  selectBtn.addEventListener("click", () => setSelecting(!state.selecting));

  /* ----- Etiquetas ----- */
  tagsBtn.addEventListener("click", async () => {
    let tags = [];
    try {
      tags = await api("tags");
    } catch {
      /* sem etiquetas */
    }
    const current = query().tag;
    menu(tagsBtn, tags.length ? [
      { label: "Filtrar por etiqueta", header: true },
      current ? { label: "Todas as etiquetas", icon: "x", onClick: () => setParam("tag", null) } : null,
      ...tags.slice(0, 200).map((t) => ({ label: `${t.name} (${fmt.n(t.count)})`, icon: "tag", checked: current === t.slug, onClick: () => setParam("tag", t.slug) })),
    ] : [{ label: "Nenhuma etiqueta ainda", header: true }]);
  });

  function renderActiveFilter() {
    const tag = query().tag;
    fill(activeFilter, tag ? h("div.active-filter", "Etiqueta:", h("span.tag", icon("tag", "sm"), tag.replace(/-/g, " "),
      h("button", { type: "button", "aria-label": "Tirar o filtro", onclick: () => setParam("tag", null) }, icon("x", "sm")))) : null);
    tagsBtn.classList.toggle("on", Boolean(tag));
  }

  async function editCollection() {
    const updated = await formDialog({
      title: "Renomear coleção",
      fields: [
        { name: "name", label: "Nome", value: collection.name, required: true, maxlength: 80 },
        { name: "description", label: "Descrição", value: collection.description, type: "textarea", maxlength: 300 },
      ],
      onSubmit: (values) => api(`collections/${collection.id}`, { method: "PATCH", body: values }),
    });
    if (updated) {
      collection = updated;
      renderHead();
      refreshCollections();
    }
  }

  async function deleteCollection() {
    const ok = await confirmDialog({
      title: "Excluir coleção?",
      text: `A coleção “${collection.name}” será apagada. Os documentos continuam na sua biblioteca.`,
      ok: "Excluir coleção",
      danger: true,
    });
    if (!ok) return;
    await api(`collections/${collection.id}`, { method: "DELETE" });
    await refreshCollections();
    navigate("/colecoes");
  }

  async function addDocuments() {
    const data = await api("documents", { query: { scope: "all", page_size: 200, sort: "title", status: "ready" } });
    const inCollection = new Set([...state.docs.keys()]);
    const options = data.results.filter((d) => !inCollection.has(d.id));
    if (!options.length) return toast("Todos os seus documentos já estão nesta coleção.");
    const checks = new Map();
    const body = h("div.picker", options.map((d) => {
      const box = h("input", { type: "checkbox", value: d.id });
      checks.set(d.id, box);
      return h("label", box, cover(d, { mini: true }), h("span", h("strong", { style: { display: "block", fontSize: "13.5px" } }, d.title), h("span.muted", { style: { fontSize: "12px" } }, d.author || d.kind.toUpperCase())));
    }));
    modal({
      title: "Adicionar à coleção",
      body,
      wide: true,
      actions: [
        { label: "Cancelar" },
        {
          label: "Adicionar",
          kind: "primary",
          onClick: async () => {
            const ids = [...checks].filter(([, b]) => b.checked).map(([id]) => id);
            if (!ids.length) return false;
            await api(`collections/${collection.id}/documents`, { method: "POST", body: { document_ids: ids } });
            toast(`${ids.length} ${ids.length === 1 ? "documento adicionado" : "documentos adicionados"}`);
            refreshCollections();
            reload();
          },
        },
      ],
    });
  }

  /* ----- Continuar lendo ----- */
  function renderContinue() {
    clear(continueBox);
    const items = store.stats?.continue_reading || [];
    if (scope !== "mine" || collection || filtersActive() || !items.length) return;
    continueBox.append(
      h("div.section-title", h("h2", "Continuar lendo"), h("a", { href: "/estantes" }, "Ver estantes")),
      h("div.reading-row", items.slice(0, 3).map((item) => {
        const byPage = item.kind === "pdf" && item.page_count;
        const progress = byPage ? item.page / item.page_count : item.progress;
        return h("a.reading-card", { href: `/ler/${item.id}` },
          cover({ cover_url: item.cover_url, cover_color: item.cover_color }, { mini: true }),
          h("div", { style: { minWidth: 0 } }, h("strong", item.title),
            h("div.meta", byPage ? `Página ${item.page} de ${item.page_count}` : `${Math.round(progress * 100)}% lido`),
            h("div.bar", h("i", { style: { width: `${Math.max(3, Math.round(progress * 100))}%` } }))));
      })));
  }

  /* ----- Filtros ----- */
  function chip(label, key, value, count, dot) {
    const q = query();
    const active = value === null ? !["kind", "shelf", "favorite", "status"].some((k) => q[k]) : q[key] === value;
    const btn = h(`button.chip${active ? ".on" : ""}`, { type: "button", "aria-pressed": String(active) },
      dot ? h("span.dot", { style: { background: dot } }) : null, label, count !== undefined && count !== null ? h("span.n", fmt.n(count)) : null);
    btn.addEventListener("click", () => {
      const url = new URL(location.href);
      for (const k of ["kind", "shelf", "favorite", "status"]) url.searchParams.delete(k);
      if (value !== null && !active) url.searchParams.set(key, value);
      history.replaceState({}, "", url.pathname + url.search);
      reload();
    });
    return btn;
  }

  function renderFilters() {
    const s = store.stats || {};
    const mine = scope === "mine";
    fill(chipsBox, 
      chip("Todos", null, null, mine ? s.total : null),
      chip("PDF", "kind", "pdf", mine ? s.pdf : null),
      chip("DOCX", "kind", "docx", mine ? s.docx : null),
      h("span.sep", { "aria-hidden": "true" }),
      chip("Quero ler", "shelf", "para_ler", null, "var(--shelf-para_ler)"),
      chip("Lendo", "shelf", "lendo", null, "var(--shelf-lendo)"),
      chip("Lidos", "shelf", "lido", null, "var(--shelf-lido)"),
      chip("Favoritos", "favorite", "1", mine ? s.favorites : null),
      mine && s.processing ? chip("Processando", "status", "processing", s.processing) : null,
      mine && s.failed ? chip("Com erro", "status", "failed", s.failed) : null,
    );
    const q = query();
    const sorts = [...(q.q ? [["relevance", "Mais relevantes"]] : []), ...(collection ? [["collection", "Ordem da coleção"]] : []), ...SORTS];
    const currentSort = q.sort || (q.q ? "relevance" : collection ? "collection" : "recent");
    sortSelect.replaceChildren(...sorts.map(([v, l]) => h("option", { value: v, selected: v === currentSort }, l)));
    viewSeg.replaceChildren(
      h(`button${state.view === "grid" ? ".on" : ""}`, { type: "button", "aria-label": "Grade", title: "Grade", onclick: () => setView("grid") }, icon("grid", "sm")),
      h(`button${state.view === "list" ? ".on" : ""}`, { type: "button", "aria-label": "Lista", title: "Lista", onclick: () => setView("list") }, icon("list", "sm")),
    );
  }
  sortSelect.addEventListener("change", () => setParam("sort", sortSelect.value));

  function setView(view) {
    state.view = view;
    try {
      localStorage.setItem("library:view", view);
    } catch {
      /* sem armazenamento */
    }
    renderFilters();
    renderResults([...state.docs.values()], true);
  }

  /* ----- Resultados ----- */
  function emptyState() {
    const q = query();
    if (q.q) return h("div.empty", h("div.art", icon("search")), h("h3", "Nada encontrado"), h("p", `Nenhum documento corresponde a “${q.q}”. Tente outras palavras ou tire os filtros.`));
    if (filtersActive()) return h("div.empty", h("div.art", icon("layers")), h("h3", "Nenhum documento com esses filtros"), h("p", "Escolha “Todos” para ver a biblioteca inteira."));
    if (collection) return h("div.empty", h("div.art", icon("collection")), h("h3", "Coleção vazia"), h("p", "Adicione documentos que você já tem ou arraste arquivos novos para esta página."), h("button.btn.primary", { onclick: addDocuments }, "Adicionar documentos"));
    return h("div.empty", h("div.art", icon("file-up")), h("h3", "Sua biblioteca está vazia"),
      h("p", "Arraste PDFs, DOCX ou pastas inteiras para esta janela. O Lume gera a capa e escreve uma descrição de cada um, tudo aqui no seu computador."),
      h("div.chips", { style: { justifyContent: "center" } },
        h("button.btn.primary", { onclick: () => pickFiles() }, icon("upload", "sm"), "Escolher arquivos"),
        h("button.btn", { onclick: () => pickFolder() }, icon("folder-open", "sm"), "Importar uma pasta")));
  }

  function makeItem(doc) {
    if (state.view === "list") return documentRow(doc, { selection });
    const card = documentCard(doc, { selection });
    if (collection) enableReorder(card);
    return card;
  }

  function renderResults(docs, replace) {
    if (replace) {
      clear(results);
      container = null;
    }
    const q = query();
    if (replace && q.q) results.append(h("p.muted", { style: { margin: "-6px 0 18px" } }, `${fmt.n(state.total)} ${state.total === 1 ? "resultado" : "resultados"} para “${q.q}”`));
    if (!docs.length && replace) {
      results.append(emptyState());
      return;
    }
    if (!container) {
      if (state.view === "list") {
        const tbody = h("tbody");
        container = tbody;
        results.append(h("div.panel", { style: { overflowX: "auto" } }, h("table.table",
          h("thead", h("tr", h("th.pick-cell", h("input", { type: "checkbox", "aria-label": "Selecionar todos", onclick: (e) => { e.preventDefault(); selectAll(); } })), h("th", "Documento"), h("th", "Tipo"), h("th", "Páginas"), h("th", "Tamanho"), h("th", "Estado"), h("th", "Adicionado"))), tbody)));
        tbody.addEventListener("open", (e) => navigate(`/documento/${e.detail}`));
      } else {
        container = h("div.grid");
        results.append(container);
      }
    }
    container.append(...docs.map(makeItem));
  }

  async function load(page) {
    state.loading = true;
    const q = query();
    const data = await api("documents", {
      query: {
        scope, page, page_size: 48, collection: collectionId,
        q: q.q, kind: q.kind, shelf: q.shelf, favorite: q.favorite, status: q.status, tag: q.tag,
        sort: q.sort || (q.q ? "relevance" : collectionId ? "collection" : "recent"),
      },
    });
    state.loading = false;
    state.next = data.next;
    state.total = data.count;
    sentinel.hidden = !data.next;
    return data.results;
  }

  let loadToken = 0;
  async function reload() {
    const token = ++loadToken;
    hidePreview();
    renderFilters();
    renderContinue();
    renderActiveFilter();
    try {
      const docs = await load(1);
      if (token !== loadToken) return;
      state.docs = new Map(docs.map((d) => [d.id, d]));
      renderResults(docs, true);
    } catch (err) {
      fill(results, h("div.empty", h("h3", "Não foi possível carregar"), h("p", errorMessage(err))));
    }
  }

  const observer = new IntersectionObserver(async ([entry]) => {
    if (!entry.isIntersecting || state.loading || !state.next) return;
    const docs = await load(state.next);
    docs.forEach((d) => state.docs.set(d.id, d));
    renderResults(docs, false);
  }, { rootMargin: "600px" });
  observer.observe(sentinel);

  /* ----- Reordenar na coleção ----- */
  let dragging = null;
  function enableReorder(card) {
    card.addEventListener("dragstart", () => { dragging = card; });
    card.addEventListener("dragend", () => {
      dragging = null;
      container?.querySelectorAll(".drop-before").forEach((el) => el.classList.remove("drop-before"));
    });
    card.addEventListener("dragover", (e) => {
      if (!dragging || dragging === card) return;
      e.preventDefault();
      container.querySelectorAll(".drop-before").forEach((el) => el !== card && el.classList.remove("drop-before"));
      card.classList.add("drop-before");
    });
    card.addEventListener("drop", async (e) => {
      if (!dragging || dragging === card) return;
      e.preventDefault();
      card.classList.remove("drop-before");
      container.insertBefore(dragging, card);
      const ids = [...container.querySelectorAll(".card")].map((el) => el.dataset.id);
      try {
        await api(`collections/${collection.id}/order`, { method: "PUT", body: { document_ids: ids } });
      } catch (err) {
        toast(errorMessage(err), "err");
      }
    });
  }

  /* ----- Tempo real ----- */
  async function refreshDoc(id) {
    const el = results.querySelector(`[data-id="${id}"]`);
    if (!el) return;
    try {
      const doc = await api(`documents/${id}`);
      state.docs.set(id, doc);
      if (el.update) el.update(doc);
      else el.replaceWith(documentRow(doc, { selection }));
    } catch {
      el.remove();
    }
  }

  async function insertDoc(doc) {
    if (state.docs.has(doc.id) || filtersActive()) return;
    if (collectionId && !doc.collections?.includes(collectionId)) return;
    state.docs.set(doc.id, doc);
    if (!container) {
      renderResults([doc], true);
      return;
    }
    container.prepend(makeItem(doc));
  }

  const offs = [
    on("document.updated", ({ id }) => refreshDoc(id)),
    on("reading.updated", ({ id }) => refreshDoc(id)),
    on("document.created", async ({ id }) => {
      if (state.docs.has(id)) return;
      try {
        insertDoc(await api(`documents/${id}`));
      } catch {
        /* sem acesso */
      }
    }),
    on("document.deleted", ({ id }) => {
      results.querySelector(`[data-id="${id}"]`)?.remove();
      state.docs.delete(id);
    }),
  ];
  const onUploaded = (e) => !e.detail.duplicate && insertDoc(e.detail);
  const onQuery = () => reload();
  window.addEventListener("library:uploaded", onUploaded);
  window.addEventListener("library:query", onQuery);
  window.addEventListener("library:refresh", onQuery);
  const onSelect = () => setSelecting(true);
  const onKey = (e) => {
    if (!state.selecting || document.querySelector(".modal-back, .palette-back, .menu")) return;
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName);
    if (e.key === "Escape") setSelecting(false);
    else if (!typing && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "a") {
      e.preventDefault();
      selectAll();
    }
  };
  window.addEventListener("library:select", onSelect);
  document.addEventListener("keydown", onKey);
  const unsubscribe = subscribe(() => {
    if (!collection && scope === "mine") renderHead();
    renderContinue();
    renderFilters();
  });

  renderHead();
  root.append(head, continueBox, h("div.toolbar", chipsBox, h("div.chips", tagsBtn, selectBtn, sortSelect, viewSeg)), activeFilter, results, sentinel);
  await reload();

  return () => {
    unsubscribe();
    observer.disconnect();
    offs.forEach((off) => off());
    window.removeEventListener("library:uploaded", onUploaded);
    window.removeEventListener("library:query", onQuery);
    window.removeEventListener("library:refresh", onQuery);
    window.removeEventListener("library:select", onSelect);
    document.removeEventListener("keydown", onKey);
    root.classList.remove("selecting");
    bulkbar.remove();
    hidePreview();
  };
}
