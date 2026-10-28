import { api } from "../api.js";
import { SHELVES, cover, errorMessage, fmt, h, icon, toast, fill } from "../ui.js";

const SOURCES = { local: "Resumo automático", anthropic: "Claude", openai: "Modelo compatível com OpenAI" };

export function sourceLabel(doc) {
  if (!doc.summary_source) return "";
  if (doc.summary_source === "openai" && doc.summary_model) return doc.summary_model;
  if (doc.summary_source === "anthropic" && doc.summary_model) return `Claude · ${doc.summary_model}`;
  return SOURCES[doc.summary_source] || doc.summary_source;
}

export function progressOf(doc) {
  const r = doc.reading || {};
  if (r.shelf === "lido") return 1;
  if (doc.kind === "pdf" && doc.page_count && r.page > 1) return Math.min(1, r.page / doc.page_count);
  return r.progress || 0;
}

/* ---------- Prévia ao passar o mouse ---------- */
const preview = h("div.preview", { role: "dialog", "aria-label": "Prévia do documento" });
document.body.append(preview);
let showTimer;
let hideTimer;
let anchor = null;

preview.addEventListener("mouseenter", () => clearTimeout(hideTimer));
preview.addEventListener("mouseleave", () => scheduleHide());
addEventListener("scroll", () => hidePreview(), { passive: true, capture: true });
addEventListener("resize", () => hidePreview());

function scheduleHide(delay = 140) {
  clearTimeout(showTimer);
  clearTimeout(hideTimer);
  hideTimer = setTimeout(hidePreview, delay);
}

export function hidePreview() {
  clearTimeout(showTimer);
  preview.classList.remove("show");
  anchor = null;
}

function fillPreview(doc) {
  const shelf = doc.reading?.shelf;
  const ready = doc.status === "ready" || (doc.summary && doc.status !== "failed");
  fill(preview, 
    h("div.arrow"),
    h("div.kicker", h(`span.badge.${doc.kind}`, doc.kind), doc.page_count ? `${fmt.n(doc.page_count)} páginas` : null,
      doc.size_bytes ? h("span", `· ${fmt.bytes(doc.size_bytes)}`) : null,
      shelf ? h("span", { style: { marginLeft: "auto" } }, SHELVES[shelf]) : null),
    h("h3", doc.title),
    doc.author ? h("div.by", doc.author) : null,
    h(`div.ai-box${ready ? "" : ".pending"}`,
      h("div.label", icon("sparkles"), "Descrição por IA", ready ? h("span", sourceLabel(doc)) : null),
      h("p", ready ? doc.summary || "Sem texto suficiente para descrever." : doc.status === "failed"
        ? doc.status_detail || "O arquivo não pôde ser lido."
        : "A descrição aparece aqui assim que o documento terminar de ser lido.")),
    doc.tags?.length ? h("div.chips", doc.tags.slice(0, 5).map((t) => h(`span.tag${t.source === "ia" ? ".ai" : ""}`, t.name))) : null,
    h("div.foot",
      h("a.btn.primary.sm", { href: `/ler/${doc.id}` }, icon("book", "sm"), progressOf(doc) > 0 && progressOf(doc) < 1 ? "Continuar" : "Ler agora"),
      h("a.btn.sm", { href: `/documento/${doc.id}` }, "Detalhes")),
  );
}

function place(card) {
  const rect = (card.querySelector(".cover") || card).getBoundingClientRect();
  const width = 348;
  const gap = 16;
  const height = preview.offsetHeight;
  let side = "right";
  let left = rect.right + gap;
  if (left + width > innerWidth - 12) {
    side = "left";
    left = rect.left - width - gap;
  }
  if (left < 12) left = 12;
  const top = Math.min(Math.max(12, rect.top - 4), innerHeight - height - 12);
  preview.style.left = `${left}px`;
  preview.style.top = `${top}px`;
  preview.style.setProperty("--arrow-y", `${Math.min(Math.max(18, rect.top + 36 - top), height - 30)}px`);
  preview.classList.toggle("left", side === "left");
  preview.classList.toggle("right", side === "right");
}

function attachPreview(card, getDoc, blocked = () => false) {
  const open = (delay) => {
    clearTimeout(hideTimer);
    clearTimeout(showTimer);
    showTimer = setTimeout(() => {
      if (!card.isConnected || blocked() || matchMedia("(hover: none)").matches) return;
      anchor = card;
      fillPreview(getDoc());
      preview.classList.remove("show");
      place(card);
      requestAnimationFrame(() => preview.classList.add("show"));
    }, delay);
  };
  card.addEventListener("mouseenter", () => open(anchor ? 120 : 380));
  card.addEventListener("mouseleave", () => scheduleHide());
  card.addEventListener("focus", () => open(250));
  card.addEventListener("blur", () => scheduleHide(200));
  card.addEventListener("dragstart", hidePreview);
}

/* ---------- Cartão ---------- */
/**
 * Cartão do documento. `selection` (opcional) liga a seleção múltipla: { active(), has(id), toggle(id) }.
 * Com a seleção ativa, clicar no cartão marca/desmarca em vez de abrir.
 */
export function documentCard(doc, { onChange, selection } = {}) {
  let current = doc;
  const card = h("a.card", { href: `/documento/${doc.id}`, dataset: { id: doc.id }, draggable: "true" });
  if (selection) {
    card.addEventListener("click", (e) => {
      if (!selection.active() && !e.shiftKey) return;
      e.preventDefault();
      selection.toggle(current.id);
    });
  }
  const render = () => {
    const d = current;
    const progress = progressOf(d);
    const shelf = d.reading?.shelf;
    const fav = h(`button.fav${d.reading?.favorite ? ".on" : ""}`, {
      type: "button",
      "aria-label": d.reading?.favorite ? "Tirar dos favoritos" : "Marcar como favorito",
      title: d.reading?.favorite ? "Favorito" : "Marcar como favorito",
    }, icon("star", "sm"));
    fav.addEventListener("click", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      try {
        const reading = await api(`documents/${d.id}/reading`, { method: "PATCH", body: { favorite: !d.reading?.favorite } });
        update({ ...current, reading });
        onChange?.(current);
      } catch (err) {
        toast(errorMessage(err), "err");
      }
    });
    const selected = Boolean(selection?.has(d.id));
    card.classList.toggle("selected", selected);
    const pick = selection ? h("button.pick", {
      type: "button", "aria-label": selected ? "Desmarcar" : "Selecionar", title: selected ? "Desmarcar" : "Selecionar (Shift+clique)", "aria-pressed": String(selected),
      onclick: (e) => { e.preventDefault(); e.stopPropagation(); selection.toggle(d.id); },
    }, icon(selected ? "check" : "square", "sm")) : null;
    fill(card, 
      h("div", { style: { position: "relative" } }, cover(d), pick, d.status === "ready" ? fav : null),
      h("div.body",
        h("div.title", d.title),
        d.author ? h("div.author", d.author) : null,
        h("div.meta",
          h("span", fmt.pages(d)),
          shelf ? h("span.shelf", { style: { "--shelf-color": `var(--shelf-${shelf})` } }, SHELVES[shelf]) : null),
        progress > 0 && progress < 1 ? h("div.bar", { title: `${Math.round(progress * 100)}% lido` }, h("i", { style: { width: `${progress * 100}%` } })) : null,
        d.snippet ? h("div.snippet", { html: `…${d.snippet}…` }) : null),
    );
  };
  const update = (next) => {
    current = { ...next, snippet: next.snippet ?? current.snippet };
    render();
    if (anchor === card) fillPreview(current);
  };
  card.addEventListener("dragstart", (e) => {
    e.dataTransfer.setData("application/x-document", current.id);
    e.dataTransfer.effectAllowed = "copyMove";
    card.classList.add("dragging");
  });
  card.addEventListener("dragend", () => card.classList.remove("dragging"));
  attachPreview(card, () => current, () => Boolean(selection?.active()));
  render();
  card.update = update;
  card.refresh = render;
  card.doc = () => current;
  return card;
}

export function documentRow(doc, { selection } = {}) {
  const shelf = doc.reading?.shelf;
  const check = selection ? h("input", { type: "checkbox", "aria-label": `Selecionar ${doc.title}`, checked: selection.has(doc.id) }) : null;
  check?.addEventListener("click", (e) => {
    e.stopPropagation();
    selection.toggle(doc.id);
  });
  const row = h(`tr.row${selection?.has(doc.id) ? ".selected" : ""}`, { dataset: { id: doc.id }, tabindex: "0" },
    selection ? h("td.pick-cell", check) : null,
    h("td", h("div.doc", cover(doc, { mini: true }), h("div", { style: { minWidth: 0 } }, h("strong", doc.title), h("span", doc.author || doc.original_name)))),
    h("td", h(`span.badge.${doc.kind}`, doc.kind)),
    h("td.num", doc.page_count ? fmt.n(doc.page_count) : "—"),
    h("td.num", fmt.bytes(doc.size_bytes)),
    h("td", doc.status !== "ready"
      ? h(`span.status-pill${doc.status === "failed" ? ".err" : ".warn"}`, doc.status_label)
      : shelf ? h("span.status-pill", { style: { color: `var(--shelf-${shelf})` } }, SHELVES[shelf]) : h("span.muted", "—")),
    h("td.muted", fmt.date(doc.created_at)),
  );
  const open = () => row.dispatchEvent(new CustomEvent("open", { bubbles: true, detail: doc.id }));
  row.refresh = () => {
    const on = Boolean(selection?.has(doc.id));
    row.classList.toggle("selected", on);
    if (check) check.checked = on;
  };
  row.addEventListener("click", (e) => {
    if (selection && (selection.active() || e.shiftKey)) {
      selection.toggle(doc.id);
      return;
    }
    open();
  });
  row.addEventListener("keydown", (e) => e.key === "Enter" && open());
  return row;
}
