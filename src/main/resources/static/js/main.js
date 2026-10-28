import { api } from "./api.js";
import { connect, on } from "./realtime.js";
import { refreshAll, refreshCollections, refreshStats, setUser, store, subscribe } from "./store.js";
import { avatar, brandMark, clear, closeMenu, cover, debounce, errorMessage, fmt, h, icon, menu, toast, fill } from "./ui.js";
import { initUploads, pickFiles, pickFolder } from "./upload.js";
import { backup } from "./actions.js";
import { MOD, showShortcuts, togglePalette } from "./palette.js";

const sidebar = document.getElementById("sidebar");
const topbar = document.getElementById("topbar");
const content = document.getElementById("content");
const app = document.getElementById("app");
const readerRoot = document.getElementById("reader-root");

const pages = {
  library: () => import("./pages/library.js"),
  shelves: () => import("./pages/shelves.js"),
  collections: () => import("./pages/collections.js"),
  document: () => import("./pages/document.js"),
  reader: () => import("./pages/reader.js"),
  system: () => import("./pages/system.js"),
  preferences: () => import("./pages/preferences.js"),
  insights: () => import("./pages/insights.js"),
  tools: () => import("./pages/tools.js"),
  integrations: () => import("./pages/integrations.js"),
};

const UUID = "([0-9a-f-]{36})";
const routes = [
  { re: /^\/(?:biblioteca)?$/, page: "library", nav: "library", props: { scope: "mine" } },
  { re: /^\/estantes$/, page: "shelves", nav: "shelves" },
  { re: /^\/colecoes$/, page: "collections", nav: "collections" },
  { re: new RegExp(`^/colecoes/${UUID}$`), page: "library", nav: "collection", props: { scope: "all" }, param: "collection" },
  { re: new RegExp(`^/documento/${UUID}$`), page: "document", nav: null, param: "id" },
  { re: new RegExp(`^/ler/${UUID}$`), page: "reader", nav: null, param: "id", fullscreen: true },
  { re: /^\/sistema$/, page: "system", nav: "system" },
  { re: /^\/preferencias$/, page: "preferences", nav: "preferences" },
  { re: /^\/estatisticas$/, page: "insights", nav: "insights" },
  { re: /^\/ferramentas$/, page: "tools", nav: "tools" },
  { re: /^\/integracoes$/, page: "integrations", nav: "integrations" },
];

let cleanup = null;
let current = { nav: null, path: null };
let renderToken = 0;

/* ---------- Tema ---------- */
export function applyTheme(pref = store.user?.theme || "system") {
  try {
    localStorage.setItem("theme", pref);
  } catch {
    /* navegação privada */
  }
  const dark = pref === "dark" || (pref === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyTheme());

export async function setTheme(pref) {
  applyTheme(pref);
  try {
    setUser(await api("me", { method: "PATCH", body: { theme: pref } }));
  } catch (err) {
    toast(errorMessage(err), "err");
  }
}

/* ---------- Navegação ---------- */
export function navigate(url, { replace = false } = {}) {
  const target = new URL(url, location.origin);
  if (target.pathname + target.search === location.pathname + location.search && !replace) return;
  history[replace ? "replaceState" : "pushState"]({}, "", target.pathname + target.search + target.hash);
  render();
}

document.addEventListener("click", (e) => {
  const link = e.target.closest("a[href]");
  if (!link || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const href = link.getAttribute("href");
  if (!href.startsWith("/") || href.startsWith("/api/") || link.target || link.hasAttribute("download")) return;
  e.preventDefault();
  sidebar.classList.remove("open");
  navigate(href);
});
addEventListener("popstate", () => render());

async function render() {
  const token = ++renderToken;
  closeMenu();
  document.querySelectorAll(".preview").forEach((el) => el.classList.remove("show"));
  const path = location.pathname.replace(/\/+$/, "") || "/";
  const route = routes.find((r) => r.re.test(path));
  if (!route) {
    navigate("/", { replace: true });
    return;
  }
  const match = path.match(route.re);
  const params = route.param ? { [route.param]: match[1] } : {};
  const query = Object.fromEntries(new URLSearchParams(location.search));

  const run = async () => {
    if (cleanup) {
      try {
        cleanup();
      } catch {
        /* página já saiu */
      }
      cleanup = null;
    }
    const module = await pages[route.page]();
    if (token !== renderToken) return;
    current = { nav: route.nav, path, collection: params.collection };
    app.hidden = Boolean(route.fullscreen);
    readerRoot.hidden = !route.fullscreen;
    const root = route.fullscreen ? clear(readerRoot) : clear(content);
    renderSidebar();
    renderTopbar(route);
    if (!route.fullscreen) scrollTo({ top: 0 });
    try {
      cleanup = (await module.mount({ root, params, query, props: route.props || {}, navigate })) || null;
    } catch (err) {
      root.append(h("div.empty", h("div.art", icon("alert")), h("h3", "Não foi possível abrir esta página"), h("p", errorMessage(err))));
    }
  };
  if (document.startViewTransition && current.path && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    const transition = document.startViewTransition(run);
    // Uma navegação nova cancela a transição anterior: isso é esperado, não é erro.
    transition.ready.catch(() => {});
    transition.finished.catch(() => {});
    transition.updateCallbackDone.catch(() => {});
  } else {
    await run();
  }
}

/* ---------- Menu lateral ---------- */
function navLink(key, href, iconName, label, count) {
  return h(`a${current.nav === key ? ".active" : ""}`, { href }, icon(iconName), h("span", label),
    count !== undefined && count !== null ? h("span.count", fmt.n(count)) : null);
}

function renderSidebar() {
  const s = store.stats || {};
  const shelvesTotal = s.shelves ? Object.values(s.shelves).reduce((a, b) => a + b, 0) : null;
  const collectionLinks = store.collections.slice(0, 10).map((c) => {
    const link = h(`a${current.collection === c.id ? ".active" : ""}`, { href: `/colecoes/${c.id}`, dataset: { collection: c.id } },
      h("span.dot"), h("span", c.name), h("span.count", fmt.n(c.document_count)));
    link.addEventListener("dragover", (e) => {
      if (!e.dataTransfer.types.includes("application/x-document")) return;
      e.preventDefault();
      link.classList.add("drop-target");
    });
    link.addEventListener("dragleave", () => link.classList.remove("drop-target"));
    link.addEventListener("drop", async (e) => {
      e.preventDefault();
      link.classList.remove("drop-target");
      const id = e.dataTransfer.getData("application/x-document");
      if (!id) return;
      try {
        const { added } = await api(`collections/${c.id}/documents`, { method: "POST", body: { document_ids: [id] } });
        toast(added ? `Adicionado a “${c.name}”` : `Já está em “${c.name}”`);
        refreshCollections();
      } catch (err) {
        toast(errorMessage(err), "err");
      }
    });
    return link;
  });

  const cont = s.continue_reading?.[0];
  const continueCard = cont
    ? h("a.continue", { href: `/ler/${cont.id}`, title: "Continuar lendo" },
      cover({ cover_url: cont.cover_url, cover_color: cont.cover_color }, { mini: true }),
      h("div", h("div.label", "Continuar"), h("div.title", cont.title),
        h("div.meta", cont.kind === "pdf" && cont.page_count ? `pág. ${cont.page} de ${cont.page_count}` : `${Math.round((cont.progress || 0) * 100)}% lido`)))
    : null;

  fill(sidebar, 
    h("a.brand", { href: "/", "aria-label": `${store.brand.name}, início` }, brandMark(),
      h("div", h("div.brand-name", store.brand.name), h("div.brand-sub", store.brand.tagline || "Biblioteca de documentos"))),
    h("nav.nav", { "aria-label": "Seções" },
      navLink("library", "/", "library", "Biblioteca", s.total),
      navLink("shelves", "/estantes", "shelves", "Estantes", shelvesTotal),
      navLink("insights", "/estatisticas", "chart", "Estatísticas"),
      h("div.nav-label", "Coleções"),
      navLink("collections", "/colecoes", "collection", "Todas as coleções", store.collections.length),
      h("div.sub", collectionLinks),
      h("div.nav-label", "Aplicativo"),
      navLink("tools", "/ferramentas", "wrench", "Ferramentas"),
      navLink("integrations", "/integracoes", "plug", "Integrações"),
      navLink("system", "/sistema", "system", "Sistema"),
      navLink("preferences", "/preferencias", "settings", "Preferências"),
    ),
    h("div.sidebar-bottom",
      continueCard,
      h(`div.live${s.processing ? ".busy" : ".on"}#live`, h("i"), s.processing ? `Processando ${fmt.n(s.processing)} ${s.processing === 1 ? "documento" : "documentos"}` : "Tudo em dia"),
      userButton()),
  );
}

function userButton() {
  const theme = store.user.theme || "system";
  const btn = h("button.me", { type: "button", "aria-haspopup": "menu" }, avatar(store.user),
    h("span.who", h("strong", store.user.name), h("span", "Biblioteca neste computador")), icon("chevron-down", "sm"));
  btn.addEventListener("click", () => menu(btn, [
    { label: "Preferências", icon: "settings", onClick: () => navigate("/preferencias") },
    { label: "Abrir a pasta de dados", icon: "folder-open", onClick: () => window.lume?.openDataFolder() },
    { label: "Fazer backup agora…", icon: "archive", onClick: () => backup() },
    { label: "Atalhos de teclado", icon: "keyboard", onClick: () => showShortcuts() },
    "-",
    { label: "Tema", header: true },
    { label: "Igual ao sistema", icon: "monitor", checked: theme === "system", onClick: () => setTheme("system") },
    { label: "Claro", icon: "sun", checked: theme === "light", onClick: () => setTheme("light") },
    { label: "Escuro", icon: "moon", checked: theme === "dark", onClick: () => setTheme("dark") },
  ]));
  return btn;
}

/* ---------- Barra superior ---------- */
let searchInput;
function renderTopbar(route) {
  const libraryPage = route.page === "library";
  const query = new URLSearchParams(location.search).get("q") || "";
  searchInput = h("input", {
    type: "search", placeholder: "Buscar por título, autor, etiqueta ou trecho…", value: libraryPage ? query : "",
    "aria-label": "Buscar documentos", autocomplete: "off",
  });
  const push = debounce(() => {
    const url = new URL(location.href);
    if (searchInput.value.trim()) url.searchParams.set("q", searchInput.value.trim());
    else url.searchParams.delete("q");
    history.replaceState({}, "", url.pathname + url.search);
    window.dispatchEvent(new CustomEvent("library:query"));
  }, 280);
  searchInput.addEventListener("input", () => libraryPage && push());
  searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !libraryPage) navigate(`/?q=${encodeURIComponent(searchInput.value.trim())}`);
    if (e.key === "Escape") searchInput.blur();
  });
  fill(topbar, 
    h("button.btn.ghost.icon.menu-toggle", { "aria-label": "Abrir menu", onclick: () => sidebar.classList.toggle("open") }, icon("list")),
    h("label.search", icon("search"), searchInput, h("kbd", "/")),
    h("div.spacer"),
    h("button.btn.ghost.palette-btn", { type: "button", title: `Paleta de comandos (${MOD}+K)`, onclick: () => togglePalette({ navigate, setTheme }) },
      icon("command", "sm"), h("span", "Comandos"), h("kbd", `${MOD} K`)),
    addButton(),
  );
}

function addButton() {
  const group = h("div.split");
  const main = h("button.btn.primary", { onclick: () => pickFiles(), title: "Adicionar documentos (U)" }, icon("upload"), h("span", "Adicionar documentos"));
  const more = h("button.btn.primary.icon", { "aria-label": "Mais opções de importação", title: "Mais opções" }, icon("chevron-down", "sm"));
  more.addEventListener("click", () => menu(more, [
    { label: "Escolher arquivos…", icon: "file-up", onClick: () => pickFiles() },
    { label: "Importar uma pasta inteira…", icon: "folder-open", onClick: () => pickFolder() },
  ]));
  group.append(main, more);
  return group;
}

addEventListener("scroll", () => topbar.classList.toggle("scrolled", scrollY > 4), { passive: true });

document.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "k" && !app.hidden) {
    e.preventDefault();
    togglePalette({ navigate, setTheme });
    return;
  }
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName) || document.activeElement?.isContentEditable;
  if (typing || e.metaKey || e.ctrlKey || e.altKey || !app.offsetParent || document.querySelector(".modal-back, .palette-back")) return;
  if (e.key === "/") {
    e.preventDefault();
    searchInput?.focus();
  } else if (e.key === "?") {
    e.preventDefault();
    showShortcuts();
  } else if (e.key.toLowerCase() === "u") {
    pickFiles();
  } else if (e.key.toLowerCase() === "s" && current.nav !== null) {
    if (!["library", "collection"].includes(current.nav)) navigate("/");
    setTimeout(() => dispatchEvent(new CustomEvent("library:select")), current.nav === "library" || current.nav === "collection" ? 0 : 300);
  }
});

/* ---------- Tempo real ---------- */
on("import.failed", ({ file, message }) => toast(`${file}: ${message}`, "err"));
const statsSoon = debounce(() => refreshStats().catch(() => {}), 400);
const collectionsSoon = debounce(() => refreshCollections().catch(() => {}), 400);
on("document.created", statsSoon);
on("document.updated", statsSoon);
on("document.deleted", () => {
  statsSoon();
  collectionsSoon();
});
on("reading.updated", statsSoon);
on("collections.changed", collectionsSoon);
on("watch.imported", ({ folder, count }) => {
  const name = String(folder || "").split(/[\\/]/).filter(Boolean).pop() || "pasta monitorada";
  toast(`${count} ${count === 1 ? "documento novo" : "documentos novos"} de “${name}”`);
  statsSoon();
});
subscribe(() => !app.hidden && renderSidebar());

/* ---------- Início ---------- */
export function currentCollection() {
  return current.collection || null;
}

applyTheme();
initUploads({ currentCollection });
connect();
refreshAll().catch(() => {});
render();
