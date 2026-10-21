import { ICONS } from "./icons.js";

/** Cria elementos: h("div.card#x", { onclick, dataset, ... }, filhos...) */
export function h(spec, attrs = {}, ...children) {
  const [, tag = "div", rest = ""] = spec.match(/^([a-z0-9-]+)?(.*)$/i) || [];
  const el = document.createElement(tag || "div");
  for (const part of rest.match(/[.#][^.#]+/g) || []) {
    if (part[0] === ".") el.classList.add(part.slice(1));
    else el.id = part.slice(1);
  }
  if (attrs instanceof Node || typeof attrs !== "object" || Array.isArray(attrs)) {
    children.unshift(attrs);
    attrs = {};
  }
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === "dataset") Object.assign(el.dataset, value);
    else if (key === "style" && typeof value === "object") {
      for (const [prop, v] of Object.entries(value)) {
        if (prop.startsWith("--")) el.style.setProperty(prop, v);
        else el.style[prop] = v;
      }
    } else if (key === "class") el.className += (el.className ? " " : "") + value;
    else if (key === "html") el.innerHTML = value;
    else if (value === true) el.setAttribute(key, "");
    else el.setAttribute(key, value);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function icon(name, cls = "") {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", `i ${cls}`.trim());
  svg.innerHTML = ICONS[name] || "";
  return svg;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

/** Substitui o conteúdo de um elemento ignorando filhos nulos (o append nativo escreveria "null"). */
export function fill(el, ...children) {
  clear(el);
  append(el, children);
  return el;
}

export function escapeHtml(text) {
  return String(text ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/* ---------- Formatação ---------- */
const numberFmt = new Intl.NumberFormat("pt-BR");
export const fmt = {
  n: (value) => numberFmt.format(value ?? 0),
  bytes(value) {
    if (!value && value !== 0) return "—";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;
    let v = value;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i += 1;
    }
    return `${v.toLocaleString("pt-BR", { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${units[i]}`;
  },
  date(value) {
    if (!value) return "—";
    return new Date(value).toLocaleDateString("pt-BR", { day: "2-digit", month: "short", year: "numeric" });
  },
  dateTime(value) {
    if (!value) return "—";
    return new Date(value).toLocaleString("pt-BR", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
  },
  ago(value) {
    if (!value) return "nunca";
    const s = (Date.now() - new Date(value).getTime()) / 1000;
    if (s < 45) return "agora";
    if (s < 3600) return `há ${Math.round(s / 60)} min`;
    if (s < 86400) return `há ${Math.round(s / 3600)} h`;
    if (s < 86400 * 30) return `há ${Math.round(s / 86400)} d`;
    return fmt.date(value);
  },
  ms(value) {
    if (value === null || value === undefined) return "—";
    return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toLocaleString("pt-BR", { maximumFractionDigits: 1 })} s`;
  },
  pages(doc) {
    if (!doc.page_count) return doc.kind.toUpperCase();
    return `${doc.kind.toUpperCase()} · ${fmt.n(doc.page_count)} ${doc.page_count === 1 ? "pág." : "págs."}`;
  },
  lang(code) {
    const map = { pt: "Português", "pt-br": "Português", en: "Inglês", "en-us": "Inglês", es: "Espanhol" };
    return map[(code || "").toLowerCase()] || code || "—";
  },
};

export const SHELVES = { para_ler: "Quero ler", lendo: "Lendo", lido: "Lido" };
export const STAGES = {
  queued: "Na fila",
  extracting: "Lendo o documento",
  describing: "Escrevendo a descrição",
  ready: "Pronto",
  failed: "Falhou",
};

/* ---------- Avisos ---------- */
let toastHost;
export function toast(message, kind = "") {
  toastHost ||= document.body.appendChild(h("div.toasts", { role: "status", "aria-live": "polite" }));
  const el = h(`div.toast${kind ? "." + kind : ""}`, icon(kind === "err" ? "alert" : "check", "sm"), message);
  toastHost.append(el);
  setTimeout(() => el.remove(), 3600);
}

export function errorMessage(err) {
  return err?.body?.message || err?.message || "Algo deu errado. Tente de novo.";
}

/* ---------- Diálogos ---------- */
export function modal({ title, body, actions = [], wide = false, onClose }) {
  const back = h("div.modal-back");
  const close = (value) => {
    back.remove();
    document.removeEventListener("keydown", onKey);
    onClose?.(value);
  };
  const onKey = (e) => e.key === "Escape" && close(null);
  const box = h(`div.modal${wide ? ".wide" : ""}`, { role: "dialog", "aria-modal": "true", "aria-label": title },
    h("div.modal-head", h("h2", title), h("button.btn.ghost.icon.sm", { "aria-label": "Fechar", onclick: () => close(null) }, icon("x"))),
    h("div.modal-body", body),
    actions.length ? h("div.modal-foot", actions.map((a) => {
      const btn = h(`button.btn${a.kind ? "." + a.kind : ""}`, { type: a.submit ? "submit" : "button" }, a.label);
      btn.addEventListener("click", async () => {
        if (!a.onClick) return close(a.value ?? null);
        btn.disabled = true;
        try {
          const result = await a.onClick();
          if (result !== false) close(result ?? a.value ?? true);
        } finally {
          btn.disabled = false;
        }
      });
      return btn;
    })) : null,
  );
  back.addEventListener("mousedown", (e) => e.target === back && close(null));
  back.append(box);
  document.body.append(back);
  document.addEventListener("keydown", onKey);
  setTimeout(() => box.querySelector("input, textarea, select, button.primary")?.focus(), 30);
  return { close, el: box };
}

export function confirmDialog({ title, text, ok = "Confirmar", danger = false }) {
  return new Promise((resolve) => {
    modal({
      title,
      body: h("p", text),
      onClose: (v) => resolve(Boolean(v)),
      actions: [
        { label: "Cancelar", value: false },
        { label: ok, kind: danger ? "danger" : "primary", value: true },
      ],
    });
  });
}

export function formDialog({ title, fields, submit = "Salvar", onSubmit }) {
  const inputs = {};
  const error = h("div.form-error", { hidden: true });
  const body = h("form", { onsubmit: (e) => e.preventDefault() }, error, fields.map((f) => {
    const id = `f-${f.name}`;
    let input;
    if (f.type === "textarea") input = h("textarea.input", { id, name: f.name, rows: 4, maxlength: f.maxlength }, f.value || "");
    else if (f.type === "select") {
      input = h("select.input", { id, name: f.name }, f.options.map(([value, label]) => h("option", { value, selected: value === f.value }, label)));
    } else input = h("input.input", { id, name: f.name, type: f.type || "text", value: f.value ?? "", placeholder: f.placeholder, required: f.required, maxlength: f.maxlength, autocomplete: f.autocomplete || "off" });
    inputs[f.name] = input;
    return h("div.field", h("label", { for: id }, f.label), input, f.hint ? h("span.hint", f.hint) : null);
  }));
  return new Promise((resolve) => {
    const dialog = modal({
      title,
      body,
      onClose: resolve,
      actions: [
        { label: "Cancelar", value: null },
        {
          label: submit,
          kind: "primary",
          onClick: async () => {
            const values = Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.value.trim()]));
            try {
              error.hidden = true;
              return (await onSubmit(values)) ?? values;
            } catch (err) {
              error.textContent = errorMessage(err);
              error.hidden = false;
              return false;
            }
          },
        },
      ],
    });
    body.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && e.target.tagName === "INPUT") dialog.el.querySelector(".modal-foot .primary")?.click();
    });
  });
}

/* ---------- Menus ---------- */
let openMenu;
export function menu(anchor, items) {
  closeMenu();
  const el = h("div.menu", { role: "menu" }, items.filter(Boolean).map((item) => {
    if (item === "-") return h("hr");
    if (item.label && item.header) return h("div.menu-label", item.label);
    return h(`button${item.danger ? ".danger" : ""}`, {
      role: "menuitem",
      onclick: () => { closeMenu(); item.onClick?.(); },
    }, item.icon ? icon(item.icon, "sm") : null, item.label, item.checked ? icon("check", "sm") : null);
  }));
  document.body.append(el);
  const r = anchor.getBoundingClientRect();
  const w = el.offsetWidth;
  const hgt = el.offsetHeight;
  let left = Math.min(r.left, innerWidth - w - 8);
  let top = r.bottom + 6;
  if (top + hgt > innerHeight - 8) top = r.top - hgt - 6;
  el.style.left = `${Math.max(8, left)}px`;
  el.style.top = `${Math.max(8, top)}px`;
  openMenu = el;
  setTimeout(() => document.addEventListener("mousedown", outside), 0);
  return el;
}
function outside(e) {
  if (openMenu && !openMenu.contains(e.target)) closeMenu();
}
export function closeMenu() {
  openMenu?.remove();
  openMenu = null;
  document.removeEventListener("mousedown", outside);
}
document.addEventListener("keydown", (e) => e.key === "Escape" && closeMenu());

/* ---------- Capas ---------- */
export function cover(doc, { mini = false } = {}) {
  const landscape = doc.cover_width && doc.cover_height && doc.cover_width > doc.cover_height * 1.05;
  const el = h(`div.${mini ? "mini-cover" : "cover"}${landscape && !mini ? ".landscape" : ""}`, {
    style: { "--cover-color": doc.cover_color || "" },
  });
  if (doc.cover_url) {
    const img = h("img", { src: doc.cover_url, alt: "", loading: "lazy", decoding: "async", draggable: "false" });
    if (img.complete) img.classList.add("loaded");
    img.addEventListener("load", () => img.classList.add("loaded"));
    el.append(img);
  }
  if (!mini && doc.status && doc.status !== "ready" && !(doc.status === "describing" && doc.cover_url)) {
    if (doc.status === "failed") el.append(h("div.state.failed", icon("alert", "lg"), "Não foi possível ler"));
    else el.append(h("div.state", h("span.spinner"), STAGES[doc.status] || "Processando"));
  }
  return el;
}

export function avatar(person, cls = "") {
  return h(`span.avatar${cls ? "." + cls : ""}`, { title: person?.name || "" }, person?.initials || "?");
}

export function debounce(fn, wait = 250) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), wait);
  };
}

export function brandMark() {
  const el = h("span.brand-mark", { "aria-hidden": "true" });
  el.innerHTML = document.getElementById("brand-svg")?.innerHTML || "";
  return el;
}
