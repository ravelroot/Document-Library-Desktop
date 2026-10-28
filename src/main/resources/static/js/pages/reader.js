import { api } from "../api.js";
import { store } from "../store.js";
import { clear, debounce, errorMessage, fmt, h, icon, toast, fill } from "../ui.js";

const THEMES = [
  ["paper", "Papel", "sun"],
  ["sepia", "Sépia", "book"],
  ["night", "Noite", "moon"],
];
const COLORS = ["salvia", "rose", "ardosia", "camurca"];
const COLOR_NAMES = { salvia: "sálvia", rose: "rosé", ardosia: "ardósia", camurca: "camurça" };
const ZOOMS = [0.5, 0.67, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3];

function prefs() {
  return { theme: "paper", fontSize: 19, ...(store.user.reader_prefs || {}) };
}
const sendPrefs = debounce((next) => {
  api("me", { method: "PATCH", body: { reader_prefs: next } }).catch(() => {});
}, 800);
function savePrefs(next) {
  store.user.reader_prefs = next; // vale na hora, mesmo que o leitor feche antes de salvar
  sendPrefs(next);
}

function normalize(text) {
  return text.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
}

export async function mount({ root, params, query, navigate }) {
  const doc = await api(`documents/${params.id}`);
  if (doc.status === "failed" || (doc.kind === "docx" && !doc.has_reader_html && doc.status !== "ready")) {
    root.append(h("div.reader-error", h("h2", "Este documento ainda não pode ser lido"), h("p", doc.status_detail || "Ele ainda está sendo processado."), h("a.btn", { href: `/documento/${doc.id}` }, "Voltar")));
    return null;
  }
  const reading = await api(`documents/${doc.id}/open`, { method: "POST" }).catch(() => doc.reading);
  let annotations = await api(`documents/${doc.id}/annotations`).catch(() => []);
  const settings = prefs();
  const state = { page: 1, pages: doc.page_count || 1, progress: reading?.progress || 0, saved: null };

  /* ---------- Estrutura ---------- */
  const stage = h("main.rd-stage", { tabindex: "0", "aria-label": "Conteúdo do documento" });
  const leftPanel = h("aside.rd-panel.rd-left", { "aria-label": "Sumário" });
  const rightPanel = h("aside.rd-panel.rd-right", { "aria-label": "Anotações" });
  const pageInput = h("input.rd-page-input", { type: "text", inputmode: "numeric", "aria-label": "Página atual", value: "1" });
  const zoomLabel = h("button.btn.ghost.sm.rd-zoom", { title: "Ajustar à largura" }, "100%");
  const footerText = h("span.num");
  const footerRight = h("span.num");
  const progressBar = h("i");
  const searchInput = h("input.rd-search", { type: "search", placeholder: "Buscar no documento", "aria-label": "Buscar no documento" });
  const reader = h(`div.reader.theme-${settings.theme}`, { dataset: { kind: doc.kind } });

  const back = () => (history.length > 1 ? history.back() : navigate(`/documento/${doc.id}`));
  const themeSeg = h("div.seg", { role: "group", "aria-label": "Cor da página" }, THEMES.map(([key, label, ic]) => {
    const b = h(`button${settings.theme === key ? ".on" : ""}`, { type: "button", title: label }, icon(ic, "sm"), h("span.rd-hide-sm", label));
    b.addEventListener("click", () => {
      settings.theme = key;
      reader.className = reader.className.replace(/theme-\w+/, `theme-${key}`);
      themeSeg.querySelectorAll("button").forEach((x) => x.classList.toggle("on", x === b));
      savePrefs({ ...settings });
    });
    return b;
  }));
  const toggleLeft = h("button.btn.ghost.icon.sm", { title: "Sumário e páginas", "aria-label": "Sumário e páginas" }, icon("panel-left"));
  const toggleRight = h("button.btn.ghost.icon.sm", { title: "Anotações", "aria-label": "Anotações" }, icon("note"));
  const bookmarkBtn = h("button.btn.ghost.icon.sm", { title: "Marcar esta página", "aria-label": "Marcar esta página" }, icon("bookmark"));
  const focusBtn = h("button.btn.ghost.icon.sm", { title: "Modo foco (F)", "aria-label": "Modo foco" }, icon("maximize"));

  const pdfControls = doc.kind === "pdf" ? [
    h("div.rd-nav",
      h("button.btn.ghost.icon.sm", { "aria-label": "Página anterior", onclick: () => goTo(state.page - 1) }, icon("chevron-left")),
      pageInput, h("span.muted.num", `/ ${fmt.n(state.pages)}`),
      h("button.btn.ghost.icon.sm", { "aria-label": "Próxima página", onclick: () => goTo(state.page + 1) }, icon("chevron-right"))),
    h("div.rd-nav.rd-hide-sm",
      h("button.btn.ghost.icon.sm", { "aria-label": "Diminuir zoom", onclick: () => zoomStep(-1) }, icon("zoom-out")),
      zoomLabel,
      h("button.btn.ghost.icon.sm", { "aria-label": "Aumentar zoom", onclick: () => zoomStep(1) }, icon("zoom-in"))),
  ] : [
    h("div.rd-nav",
      h("button.btn.ghost.icon.sm", { "aria-label": "Diminuir letra", onclick: () => fontStep(-1) }, icon("font-down")),
      h("button.btn.ghost.icon.sm", { "aria-label": "Aumentar letra", onclick: () => fontStep(1) }, icon("font-up"))),
  ];

  reader.append(
    h("header.rd-top",
      h("button.btn.ghost.icon.sm", { "aria-label": "Voltar", title: "Voltar (Esc)", onclick: back }, icon("arrow-left")),
      toggleLeft,
      h("div.rd-title", h("strong", doc.title), h("span", doc.author || doc.kind.toUpperCase())),
      h("div.rd-center", pdfControls),
      h("div.rd-tools", h("label.rd-search-box.rd-hide-sm", icon("search", "sm"), searchInput), themeSeg, bookmarkBtn, toggleRight, focusBtn)),
    h("div.rd-body", leftPanel, stage, rightPanel),
    h("footer.rd-foot", footerText, h("div.bar", progressBar), footerRight),
  );
  root.append(reader);

  /* ---------- Painéis ---------- */
  const narrow = () => innerWidth < 1100;
  let leftOpen = !narrow() && (doc.outline?.length > 0);
  let rightOpen = false;
  function syncPanels() {
    reader.classList.toggle("left-open", leftOpen);
    reader.classList.toggle("right-open", rightOpen);
    toggleLeft.classList.toggle("on", leftOpen);
    toggleRight.classList.toggle("on", rightOpen);
  }
  toggleLeft.addEventListener("click", () => {
    leftOpen = !leftOpen;
    if (narrow() && leftOpen) rightOpen = false;
    syncPanels();
    afterLayout();
  });
  toggleRight.addEventListener("click", () => {
    rightOpen = !rightOpen;
    if (narrow() && rightOpen) leftOpen = false;
    syncPanels();
    afterLayout();
  });
  focusBtn.addEventListener("click", () => toggleFocus());
  function toggleFocus() {
    reader.classList.toggle("focus");
    if (reader.classList.contains("focus")) toast("Modo foco · F ou Esc para sair");
    afterLayout();
  }

  let leftTab = doc.outline?.length ? "outline" : doc.kind === "pdf" ? "thumbs" : "outline";
  function renderLeft() {
    const tabs = h("div.seg.rd-tabs",
      h(`button${leftTab === "outline" ? ".on" : ""}`, { type: "button", onclick: () => { leftTab = "outline"; renderLeft(); } }, icon("outline", "sm"), "Sumário"),
      doc.kind === "pdf" ? h(`button${leftTab === "thumbs" ? ".on" : ""}`, { type: "button", onclick: () => { leftTab = "thumbs"; renderLeft(); } }, icon("images", "sm"), "Páginas") : null);
    const body = h("div.rd-panel-body");
    if (leftTab === "outline") {
      if (!doc.outline?.length) body.append(h("p.muted.rd-empty", "Este documento não tem sumário."));
      else body.append(h("ol.rd-outline", doc.outline.map((item) => h(`li.l${Math.min(item.level || 1, 4)}`,
        h("button", { type: "button", onclick: () => { if (item.page) goTo(item.page); else if (item.anchor) goToAnchor(item.anchor); if (narrow()) { leftOpen = false; syncPanels(); } } },
          h("span", item.title), item.page ? h("span.num.pg-n", item.page) : null)))));
    } else {
      body.append(thumbs());
    }
    fill(leftPanel, tabs, body);
  }

  function renderNotes(prefill) {
    const list = h("div.rd-notes");
    const sorted = [...annotations].sort((a, b) => (a.page || 0) - (b.page || 0) || (a.anchor?.block || 0) - (b.anchor?.block || 0) || a.created_at.localeCompare(b.created_at));
    if (!sorted.length) list.append(h("p.muted.rd-empty", doc.kind === "pdf"
      ? "Selecione um trecho para destacar ou escreva uma nota sobre a página atual."
      : "Selecione um trecho para destacar ou escreva uma nota."));
    for (const a of sorted) {
      const item = h(`div.rd-note.c-${a.color}`,
        h("div.rd-note-head",
          h("button.rd-note-go", { type: "button", onclick: () => jumpTo(a) },
            icon(a.kind === "bookmark" ? "bookmark" : a.kind === "highlight" ? "highlighter" : "note", "sm"),
            a.page ? `Página ${a.page}` : a.kind === "bookmark" ? "Marcador" : "Trecho"),
          h("button.btn.ghost.icon.sm", { "aria-label": "Apagar", title: "Apagar", onclick: async () => {
            await api(`annotations/${a.id}`, { method: "DELETE" });
            annotations = annotations.filter((x) => x.id !== a.id);
            renderNotes();
            paintAnnotations();
          } }, icon("trash", "sm"))),
        a.quote ? h("blockquote", a.quote) : null,
        a.note ? h("p", a.note) : null);
      list.append(item);
    }
    const text = h("textarea.input", { rows: 3, placeholder: doc.kind === "pdf" ? `Nota na página ${state.page}` : "Escreva uma nota" });
    composer = prefill?.quote ? null : text;
    if (prefill?.quote) text.placeholder = "Sua nota sobre o trecho selecionado";
    const save = h("button.btn.primary.sm", { type: "button" }, "Salvar nota");
    const submit = async () => {
      const note = text.value.trim();
      if (!note) return text.focus();
      save.disabled = true;
      try {
        const created = await api(`documents/${doc.id}/annotations`, {
          method: "POST",
          body: { kind: "note", note, color: prefill?.color || "salvia", quote: prefill?.quote || "", page: prefill?.page ?? (doc.kind === "pdf" ? state.page : null), anchor: prefill?.anchor || currentAnchor() },
        });
        annotations.push(created);
        renderNotes();
        paintAnnotations();
      } catch (err) {
        toast(errorMessage(err), "err");
      } finally {
        save.disabled = false;
      }
    };
    save.addEventListener("click", submit);
    text.addEventListener("keydown", (e) => e.key === "Enter" && (e.ctrlKey || e.metaKey) && submit());
    fill(rightPanel, 
      h("div.rd-panel-head", h("strong", "Anotações"), h("span.muted.num", annotations.length)),
      h("div.rd-panel-body", list),
      h("div.rd-compose", prefill?.quote ? h("blockquote", prefill.quote) : null, text, h("div.rd-compose-foot", h("span.muted", "Ctrl + Enter"), save)),
    );
    if (prefill) setTimeout(() => text.focus(), 50);
  }

  bookmarkBtn.addEventListener("click", async () => {
    const created = await api(`documents/${doc.id}/annotations`, {
      method: "POST",
      body: { kind: "bookmark", page: doc.kind === "pdf" ? state.page : null, anchor: currentAnchor(), color: "rose" },
    });
    annotations.push(created);
    renderNotes();
    toast(doc.kind === "pdf" ? `Página ${state.page} marcada` : "Posição marcada");
  });

  /* ---------- Seleção de texto ---------- */
  const selPop = h("div.rd-selpop", { hidden: true },
    COLORS.map((c) => h(`button.rd-dot.c-${c}`, { type: "button", "aria-label": `Destacar em ${COLOR_NAMES[c]}`, title: `Destacar em ${COLOR_NAMES[c]}`, onclick: () => highlight(c) })),
    h("span.rd-sep"),
    h("button.btn.ghost.sm", { type: "button", onclick: () => highlight("salvia", true) }, icon("note", "sm"), "Anotar"),
    h("button.btn.ghost.sm", { type: "button", onclick: copySelection }, icon("copy", "sm")));
  reader.append(selPop);
  let pending = null;

  function onSelectionEnd() {
    const sel = getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) {
      selPop.hidden = true;
      return;
    }
    const range = sel.getRangeAt(0);
    const quote = sel.toString().trim().replace(/\s+/g, " ");
    if (quote.length < 2) return;
    const info = doc.kind === "pdf" ? pdfAnchor(range) : docxAnchor(range);
    if (!info) return;
    pending = { ...info, quote: quote.slice(0, 3000) };
    const r = range.getBoundingClientRect();
    selPop.hidden = false;
    const w = selPop.offsetWidth;
    selPop.style.left = `${Math.min(Math.max(8, r.left + r.width / 2 - w / 2), innerWidth - w - 8)}px`;
    selPop.style.top = `${Math.min(Math.max(60, r.top - 50), innerHeight - 110)}px`;
  }
  stage.addEventListener("mouseup", () => setTimeout(onSelectionEnd, 10));
  stage.addEventListener("keyup", (e) => e.shiftKey && onSelectionEnd());
  document.addEventListener("selectionchange", () => {
    if (getSelection()?.isCollapsed) selPop.hidden = true;
  });

  async function highlight(color, withNote = false) {
    if (!pending) return;
    const data = pending;
    selPop.hidden = true;
    getSelection()?.removeAllRanges();
    if (withNote) {
      rightOpen = true;
      syncPanels();
      renderNotes({ ...data, color });
      return;
    }
    try {
      const created = await api(`documents/${doc.id}/annotations`, { method: "POST", body: { kind: "highlight", color, ...data } });
      annotations.push(created);
      renderNotes();
      paintAnnotations();
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  async function copySelection() {
    selPop.hidden = true;
    try {
      await navigator.clipboard.writeText(getSelection()?.toString() || "");
      toast("Trecho copiado");
    } catch {
      toast("Use Ctrl + C para copiar o trecho", "err");
    }
  }

  /* ---------- Progresso ---------- */
  function persist(keepalive = false) {
    const body = doc.kind === "pdf" ? { page: state.page, progress: state.progress } : { progress: state.progress, location: currentAnchor() };
    const key = JSON.stringify(body);
    if (key === state.saved) return;
    state.saved = key;
    fetch(`/api/v1/documents/${doc.id}/reading`, {
      method: "PATCH",
      keepalive,
      headers: { "Content-Type": "application/json" },
      body: key,
    }).catch(() => {});
  }
  const persistSoon = debounce(() => persist(), 1200);

  let composer = null;
  function updateFooter() {
    if (composer && doc.kind === "pdf") composer.placeholder = `Nota na página ${state.page}`;
    const pct = Math.round(state.progress * 100);
    progressBar.style.width = `${pct}%`;
    const count = annotations.length;
    footerRight.textContent = `${pct}% lido${count ? ` · ${count} ${count === 1 ? "anotação" : "anotações"}` : ""}`;
    footerText.textContent = doc.kind === "pdf" ? `Página ${state.page} de ${state.pages}` : doc.word_count ? `${fmt.n(doc.word_count)} palavras · ${Math.max(1, Math.round(doc.word_count / 230))} min de leitura` : "";
  }

  /* ---------- Motor de cada formato ---------- */
  let engine;
  let afterLayout = () => {};
  let goTo = () => {};
  let goToAnchor = () => {};
  let currentAnchor = () => ({});
  let pdfAnchor = () => null;
  let docxAnchor = () => null;
  let paintAnnotations = () => {};
  let thumbs = () => h("div");
  let zoomStep = () => {};
  let fontStep = () => {};
  let jumpTo = () => {};
  let search = () => {};

  if (doc.kind === "pdf") {
    engine = await pdfEngine();
  } else {
    engine = await docxEngine();
  }

  async function pdfEngine() {
    const base = `${store.staticRoot}vendor/pdfjs/`;
    const pdfjs = await import(`${base}pdf.min.mjs`);
    pdfjs.GlobalWorkerOptions.workerSrc = `${base}pdf.worker.min.mjs`;
    const loading = h("div.rd-loading", h("span.spinner"), "Abrindo o documento…");
    stage.append(loading);
    // O arquivo está no disco do próprio computador: ler inteiro é mais rápido que pedir em pedaços.
    const response = await fetch(doc.file_url);
    if (!response.ok) throw new Error("O arquivo do documento não foi encontrado na pasta da biblioteca.");
    const task = pdfjs.getDocument({
      data: new Uint8Array(await response.arrayBuffer()),
      cMapUrl: `${base}cmaps/`,
      cMapPacked: true,
      standardFontDataUrl: `${base}standard_fonts/`,
      wasmUrl: `${base}wasm/`,
      isEvalSupported: false,
    });
    const pdf = await task.promise;
    loading.remove();
    state.pages = pdf.numPages;
    const first = await pdf.getPage(1);
    const base1 = first.getViewport({ scale: 1 });
    const sizes = new Map([[1, { w: base1.width, h: base1.height }]]);
    let mode = "fit";
    let scale = 1;
    const pagesBox = h("div.rd-pages");
    stage.append(pagesBox);
    const slots = [];
    const rendered = new Map();

    for (let n = 1; n <= pdf.numPages; n += 1) {
      const slot = h("div.pg", { dataset: { page: n }, "aria-label": `Página ${n}` }, h("div.pg-num", n));
      slots.push(slot);
      pagesBox.append(slot);
    }

    function fitScale() {
      const available = Math.min(stage.clientWidth - 64, base1.width > base1.height ? 1180 : 900);
      return Math.max(0.4, Math.min(2.2, available / base1.width));
    }
    function layout() {
      scale = mode === "fit" ? fitScale() : scale;
      zoomLabel.textContent = `${Math.round(scale * 100)}%`;
      for (const slot of slots) {
        const size = sizes.get(Number(slot.dataset.page)) || { w: base1.width, h: base1.height };
        slot.style.width = `${Math.floor(size.w * scale)}px`;
        slot.style.height = `${Math.floor(size.h * scale)}px`;
        slot.style.setProperty("--scale-factor", scale);
      }
    }

    async function renderPage(slot) {
      const n = Number(slot.dataset.page);
      const key = `${n}@${scale}`;
      if (rendered.get(n)?.key === key) return;
      rendered.get(n)?.task?.cancel();
      const page = await pdf.getPage(n);
      const viewport = page.getViewport({ scale });
      if (!sizes.has(n)) {
        const v1 = page.getViewport({ scale: 1 });
        sizes.set(n, { w: v1.width, h: v1.height });
        slot.style.width = `${Math.floor(viewport.width)}px`;
        slot.style.height = `${Math.floor(viewport.height)}px`;
      }
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      const canvas = h("canvas");
      canvas.width = Math.floor(viewport.width * ratio);
      canvas.height = Math.floor(viewport.height * ratio);
      canvas.style.width = `${Math.floor(viewport.width)}px`;
      canvas.style.height = `${Math.floor(viewport.height)}px`;
      const ctx = canvas.getContext("2d");
      const renderTask = page.render({ canvasContext: ctx, canvas, viewport, transform: ratio !== 1 ? [ratio, 0, 0, ratio, 0, 0] : null });
      rendered.set(n, { key, task: renderTask });
      try {
        await renderTask.promise;
      } catch {
        return;
      }
      const textLayer = h("div.textLayer");
      const hlLayer = h("div.hl-layer");
      slot.querySelectorAll("canvas, .textLayer, .hl-layer").forEach((el) => el.remove());
      slot.prepend(canvas);
      slot.append(hlLayer, textLayer);
      slot.classList.add("ready");
      rendered.set(n, { key });
      try {
        await new pdfjs.TextLayer({ textContentSource: page.streamTextContent(), container: textLayer, viewport }).render();
      } catch {
        /* página sem texto */
      }
      paintPage(n);
      if (lastSearch) markHits(slot, lastSearch);
    }

    function release(slot) {
      const n = Number(slot.dataset.page);
      if (!rendered.has(n)) return;
      rendered.get(n)?.task?.cancel();
      rendered.delete(n);
      slot.classList.remove("ready");
      slot.querySelectorAll("canvas, .textLayer, .hl-layer").forEach((el) => el.remove());
    }

    const visible = new Set();
    const io = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          visible.add(entry.target);
          renderPage(entry.target);
        } else {
          visible.delete(entry.target);
          if (rendered.size > 14) release(entry.target);
        }
      }
    }, { root: stage, rootMargin: "1200px 0px" });
    slots.forEach((slot) => io.observe(slot));

    function currentFromScroll() {
      const mid = stage.scrollTop + stage.clientHeight * 0.35;
      let n = 1;
      for (const slot of slots) {
        if (slot.offsetTop <= mid) n = Number(slot.dataset.page);
        else break;
      }
      return n;
    }
    function onScroll() {
      const n = currentFromScroll();
      const max = stage.scrollHeight - stage.clientHeight;
      const atEnd = max > 0 && stage.scrollTop >= max - 4;
      state.page = atEnd ? pdf.numPages : n;
      state.progress = pdf.numPages <= 1 ? 1 : (state.page - 1) / (pdf.numPages - 1);
      if (document.activeElement !== pageInput) pageInput.value = state.page;
      updateFooter();
      persistSoon();
    }
    stage.addEventListener("scroll", onScroll, { passive: true });

    goTo = (n, smooth = false) => {
      const target = Math.min(Math.max(1, Number(n) || 1), pdf.numPages);
      const slot = slots[target - 1];
      stage.scrollTo({ top: slot.offsetTop - 24, behavior: smooth ? "smooth" : "auto" });
    };
    pageInput.addEventListener("change", () => goTo(pageInput.value));
    pageInput.addEventListener("keydown", (e) => e.key === "Enter" && (goTo(pageInput.value), pageInput.blur()));

    function rezoom(next) {
      const n = state.page;
      const slot = slots[n - 1];
      const offset = (stage.scrollTop - slot.offsetTop) / slot.offsetHeight;
      if (next === "fit") mode = "fit";
      else {
        mode = "manual";
        scale = next;
      }
      layout();
      stage.scrollTop = slots[n - 1].offsetTop + offset * slots[n - 1].offsetHeight;
      visible.forEach((s) => renderPage(s));
    }
    zoomStep = (dir) => {
      const index = ZOOMS.findIndex((z) => z >= scale - 0.001);
      const next = dir > 0 ? ZOOMS.find((z) => z > scale + 0.01) ?? ZOOMS.at(-1) : [...ZOOMS].reverse().find((z) => z < scale - 0.01) ?? ZOOMS[0];
      rezoom(index === -1 && dir < 0 ? ZOOMS.at(-1) : next);
    };
    zoomLabel.addEventListener("click", () => rezoom("fit"));
    afterLayout = debounce(() => mode === "fit" && rezoom("fit"), 150);
    addEventListener("resize", afterLayout);

    /* Destaques */
    pdfAnchor = (range) => {
      const startSlot = range.startContainer.parentElement?.closest(".pg");
      if (!startSlot) return null;
      const box = startSlot.getBoundingClientRect();
      const rects = [...range.getClientRects()]
        .filter((r) => r.width > 1 && r.height > 1 && r.top >= box.top - 2 && r.bottom <= box.bottom + 2)
        .map((r) => [(r.left - box.left) / box.width, (r.top - box.top) / box.height, r.width / box.width, r.height / box.height].map((v) => Math.round(v * 10000) / 10000));
      if (!rects.length) return null;
      return { page: Number(startSlot.dataset.page), anchor: { rects: rects.slice(0, 60) } };
    };
    currentAnchor = () => ({});
    function paintPage(n) {
      const layer = slots[n - 1]?.querySelector(".hl-layer");
      if (!layer) return;
      clear(layer);
      for (const a of annotations) {
        if (a.page !== n || !a.anchor?.rects) continue;
        for (const [x, y, w, hgt] of a.anchor.rects) {
          layer.append(h(`div.hl.c-${a.color}`, { title: a.note || a.quote, style: { left: `${x * 100}%`, top: `${y * 100}%`, width: `${w * 100}%`, height: `${hgt * 100}%` } }));
        }
      }
    }
    paintAnnotations = () => {
      for (const n of rendered.keys()) paintPage(n);
      updateFooter();
    };
    jumpTo = (a) => a.page && goTo(a.page, true);

    /* Miniaturas */
    thumbs = () => {
      const grid = h("div.rd-thumbs");
      const tio = new IntersectionObserver(async (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting || entry.target.dataset.done) continue;
          entry.target.dataset.done = "1";
          tio.unobserve(entry.target);
          const page = await pdf.getPage(Number(entry.target.dataset.page));
          const vp = page.getViewport({ scale: 150 / page.getViewport({ scale: 1 }).width });
          const c = h("canvas");
          c.width = vp.width;
          c.height = vp.height;
          await page.render({ canvasContext: c.getContext("2d"), canvas: c, viewport: vp }).promise.catch(() => {});
          entry.target.querySelector(".th-img").append(c);
        }
      }, { root: leftPanel, rootMargin: "300px" });
      for (let n = 1; n <= pdf.numPages; n += 1) {
        const size = sizes.get(n) || { w: base1.width, h: base1.height };
        const item = h("button.rd-thumb", { type: "button", dataset: { page: n }, onclick: () => goTo(n) },
          h("div.th-img", { style: { aspectRatio: `${size.w} / ${size.h}` } }), h("span.num", n));
        grid.append(item);
        tio.observe(item);
      }
      return grid;
    };

    /* Busca */
    const textCache = new Map();
    async function pageText(n) {
      if (!textCache.has(n)) {
        const page = await pdf.getPage(n);
        const content = await page.getTextContent();
        textCache.set(n, normalize(content.items.map((i) => i.str).join(" ")));
      }
      return textCache.get(n);
    }
    let lastSearch = "";
    function markHits(slot, term) {
      slot.querySelectorAll(".textLayer span").forEach((span) => {
        span.classList.toggle("hit", Boolean(term) && normalize(span.textContent).includes(term));
      });
    }
    search = async (raw, backwards = false) => {
      const term = normalize(raw.trim());
      lastSearch = term;
      slots.forEach((slot) => markHits(slot, term));
      if (!term) return;
      for (let i = 1; i <= pdf.numPages; i += 1) {
        const n = backwards ? ((state.page - 1 - i + pdf.numPages * 2) % pdf.numPages) + 1 : ((state.page - 1 + i) % pdf.numPages) + 1;
        if ((await pageText(n)).includes(term)) {
          goTo(n, true);
          return;
        }
      }
      toast(`“${raw}” não aparece no documento`);
    };

    layout();
    const start = Number(query.p) || reading?.page || 1;
    requestAnimationFrame(() => {
      goTo(start);
      onScroll();
    });
    return { destroy: () => { io.disconnect(); removeEventListener("resize", afterLayout); pdf.destroy(); } };
  }

  async function docxEngine() {
    const { html, outline } = await api(`documents/${doc.id}/reader`);
    doc.outline = outline;
    const article = h("article.docx-article", { html });
    article.style.setProperty("--rd-size", `${settings.fontSize}px`);
    // Quando o próprio arquivo já abre com um título, não repete o cabeçalho.
    const hasTitle = article.firstElementChild?.tagName === "H1";
    stage.append(h("div.docx-wrap", hasTitle ? null : h("header.docx-head", h("h1", doc.title), doc.author ? h("p", doc.author) : null), article));
    const blocks = () => [...article.querySelectorAll("[data-b]")];

    function onScroll() {
      const max = stage.scrollHeight - stage.clientHeight;
      state.progress = max <= 0 ? 1 : Math.min(1, stage.scrollTop / max);
      updateFooter();
      persistSoon();
    }
    stage.addEventListener("scroll", onScroll, { passive: true });

    currentAnchor = () => {
      const top = stage.getBoundingClientRect().top + 80;
      const block = blocks().find((b) => b.getBoundingClientRect().bottom > top);
      return block ? { block: Number(block.dataset.b) } : {};
    };
    goToAnchor = (id) => {
      const el = article.querySelector(`#${CSS.escape(id)}`);
      if (el) stage.scrollTo({ top: el.offsetTop - 40, behavior: "smooth" });
    };
    const goToBlock = (index, smooth = true) => {
      const el = article.querySelector(`[data-b="${index}"]`);
      if (el) stage.scrollTo({ top: el.offsetTop - 60, behavior: smooth ? "smooth" : "auto" });
    };
    jumpTo = (a) => a.anchor?.block !== undefined && goToBlock(a.anchor.block);
    fontStep = (dir) => {
      settings.fontSize = Math.min(28, Math.max(14, settings.fontSize + dir));
      article.style.setProperty("--rd-size", `${settings.fontSize}px`);
      savePrefs({ ...settings });
    };

    docxAnchor = (range) => {
      const node = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
      const block = node?.closest("[data-b]");
      if (!block || !article.contains(block)) return null;
      return { page: null, anchor: { block: Number(block.dataset.b) } };
    };

    function wrapQuote(block, quote, cls, title) {
      const target = normalize(quote).replace(/\s+/g, " ");
      const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
      const nodes = [];
      let text = "";
      while (walker.nextNode()) {
        nodes.push({ node: walker.currentNode, start: text.length });
        text += walker.currentNode.nodeValue;
      }
      const flat = normalize(text);
      const collapsed = [];
      let out = "";
      for (let i = 0; i < flat.length; i += 1) {
        if (/\s/.test(flat[i]) && /\s$/.test(out)) continue;
        collapsed.push(i);
        out += /\s/.test(flat[i]) ? " " : flat[i];
      }
      const at = out.indexOf(target);
      if (at < 0) return;
      const from = collapsed[at];
      const to = collapsed[at + target.length - 1] + 1;
      for (const { node, start } of nodes) {
        const end = start + node.nodeValue.length;
        if (end <= from || start >= to) continue;
        const range = document.createRange();
        range.setStart(node, Math.max(0, from - start));
        range.setEnd(node, Math.min(node.nodeValue.length, to - start));
        const mark = h(`mark.hl.${cls}`, { title: title || "" });
        try {
          range.surroundContents(mark);
        } catch {
          /* trecho cruza elementos: marca o que der */
        }
      }
    }
    paintAnnotations = () => {
      article.querySelectorAll("mark.hl").forEach((m) => m.replaceWith(...m.childNodes));
      article.normalize();
      for (const a of annotations) {
        if (!a.quote || a.anchor?.block === undefined) continue;
        const block = article.querySelector(`[data-b="${a.anchor.block}"]`);
        if (block) wrapQuote(block, a.quote, `c-${a.color}`, a.note);
      }
      updateFooter();
    };

    let hits = [];
    let hitIndex = -1;
    search = (raw, backwards = false) => {
      article.querySelectorAll("mark.hit").forEach((m) => m.replaceWith(...m.childNodes));
      article.normalize();
      const term = raw.trim();
      if (!term) return;
      hits = [];
      blocks().forEach((b) => {
        if (normalize(b.textContent).includes(normalize(term))) {
          wrapQuote(b, term, "hit");
          hits.push(b);
        }
      });
      if (!hits.length) return toast(`“${raw}” não aparece no documento`);
      hitIndex = (hitIndex + (backwards ? -1 : 1) + hits.length) % hits.length;
      stage.scrollTo({ top: hits[hitIndex].offsetTop - 80, behavior: "smooth" });
    };

    paintAnnotations();
    requestAnimationFrame(() => {
      if (location.hash) goToAnchor(location.hash.slice(1));
      else if (reading?.location?.block !== undefined) goToBlock(reading.location.block, false);
      else if (reading?.progress) stage.scrollTop = reading.progress * (stage.scrollHeight - stage.clientHeight);
      onScroll();
    });
    return { destroy: () => {} };
  }

  searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") search(searchInput.value, e.shiftKey);
    if (e.key === "Escape") {
      searchInput.value = "";
      search("");
      stage.focus();
    }
  });

  /* ---------- Teclado ---------- */
  function onKey(e) {
    const typing = /^(INPUT|TEXTAREA)$/.test(document.activeElement?.tagName);
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "f") {
      e.preventDefault();
      searchInput.focus();
      return;
    }
    if (typing) return;
    if (e.key === "Escape") {
      if (reader.classList.contains("focus")) toggleFocus();
      else back();
    } else if (e.key.toLowerCase() === "f") toggleFocus();
    else if (doc.kind === "pdf" && ["ArrowRight", "PageDown"].includes(e.key) && !e.altKey) {
      e.preventDefault();
      goTo(state.page + 1);
    } else if (doc.kind === "pdf" && ["ArrowLeft", "PageUp"].includes(e.key) && !e.altKey) {
      e.preventDefault();
      goTo(state.page - 1);
    } else if (doc.kind === "pdf" && e.key === "Home") goTo(1);
    else if (doc.kind === "pdf" && e.key === "End") goTo(state.pages);
    else if (e.key === "+" || e.key === "=") (doc.kind === "pdf" ? zoomStep : fontStep)(1);
    else if (e.key === "-") (doc.kind === "pdf" ? zoomStep : fontStep)(-1);
  }
  document.addEventListener("keydown", onKey);
  const onHide = () => persist(true);
  addEventListener("pagehide", onHide);

  renderLeft();
  renderNotes();
  syncPanels();
  updateFooter();
  document.title = `${doc.title} · ${store.brand.name}`;
  stage.focus({ preventScroll: true });

  return () => {
    persist(true);
    document.removeEventListener("keydown", onKey);
    removeEventListener("pagehide", onHide);
    engine?.destroy();
    document.title = store.brand.name;
  };
}
