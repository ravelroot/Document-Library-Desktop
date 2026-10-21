import { api } from "../api.js";
import { documentCard, documentRow, hidePreview } from "../components/card.js";
import { on } from "../realtime.js";
import { refreshCollections, store, subscribe } from "../store.js";
import { clear, confirmDialog, cover, errorMessage, fmt, formDialog, h, icon, menu, modal, toast, fill } from "../ui.js";
import { pickFiles, pickFolder } from "../upload.js";

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
  return `${part}, ${store.user.name.split(" ")[0]}`;
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

  const state = { page: 1, next: null, loading: false, docs: new Map(), view: viewPref(), total: 0 };
  const results = h("div");
  const sentinel = h("div.more-loader", { hidden: true }, h("span.spinner"));
  const chipsBox = h("div.chips", { role: "toolbar", "aria-label": "Filtros" });
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
      head.append(h("div.page-head.greeting", h("div", h("h1", greeting()), h("p", "O que você vai ler hoje?"))));
    }
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
      chip("Quero ler", "shelf", "para_ler", null, "var(--shelf-para_ler)"),
      chip("Lendo", "shelf", "lendo", null, "var(--shelf-lendo)"),
      chip("Lidos", "shelf", "lido", null, "var(--shelf-lido)"),
      chip("Favoritos", "favorite", "1"),
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
    if (state.view === "list") return documentRow(doc);
    const card = documentCard(doc);
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
          h("thead", h("tr", h("th", "Documento"), h("th", "Tipo"), h("th", "Páginas"), h("th", "Tamanho"), h("th", "Estado"), h("th", "Adicionado"))), tbody)));
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
      else el.replaceWith(documentRow(doc));
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
  const unsubscribe = subscribe(() => {
    renderContinue();
    renderFilters();
  });

  renderHead();
  root.append(head, continueBox, h("div.toolbar", chipsBox, h("div.chips", sortSelect, viewSeg)), results, sentinel);
  await reload();

  return () => {
    unsubscribe();
    observer.disconnect();
    offs.forEach((off) => off());
    window.removeEventListener("library:uploaded", onUploaded);
    window.removeEventListener("library:query", onQuery);
    window.removeEventListener("library:refresh", onQuery);
    hidePreview();
  };
}
