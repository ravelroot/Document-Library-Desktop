import { api } from "./api.js";
import { EXPORT_FORMATS, backup, exportCatalog } from "./actions.js";
import { refreshCollections, store } from "./store.js";
import { cover, debounce, errorMessage, fill, formDialog, h, icon, modal, toast } from "./ui.js";
import { pickFiles, pickFolder } from "./upload.js";

/* Paleta de comandos (Ctrl+K): vai para qualquer tela, executa ações e encontra documentos pelo título ou pelo texto. */

const fold = (text) => String(text || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
const isMac = /Mac/i.test(navigator.platform || "");
export const MOD = isMac ? "⌘" : "Ctrl";

let open = null;

function commands(navigate, setTheme) {
  const go = (path) => () => navigate(path);
  return [
    { group: "Ir para", icon: "library", label: "Biblioteca", keys: "inicio documentos", run: go("/") },
    { group: "Ir para", icon: "shelves", label: "Estantes", keys: "quero ler lendo lidos", run: go("/estantes") },
    { group: "Ir para", icon: "collection", label: "Coleções", run: go("/colecoes") },
    { group: "Ir para", icon: "chart", label: "Estatísticas", keys: "painel numeros leitura", run: go("/estatisticas") },
    { group: "Ir para", icon: "wrench", label: "Ferramentas", keys: "backup exportar manutencao", run: go("/ferramentas") },
    { group: "Ir para", icon: "plug", label: "Integrações", keys: "webhook pasta monitorada obsidian zotero", run: go("/integracoes") },
    { group: "Ir para", icon: "system", label: "Sistema", keys: "fila processamento", run: go("/sistema") },
    { group: "Ir para", icon: "settings", label: "Preferências", keys: "ia tema nome", run: go("/preferencias") },
    { group: "Ações", icon: "upload", label: "Adicionar documentos…", keys: "importar enviar arquivo", hint: "U", run: () => pickFiles() },
    { group: "Ações", icon: "folder-open", label: "Importar uma pasta inteira…", keys: "importar", run: () => pickFolder() },
    { group: "Ações", icon: "folder-plus", label: "Nova coleção…", run: newCollection },
    { group: "Ações", icon: "square-check", label: "Selecionar documentos", keys: "lote varios multiplos", run: () => { navigate("/"); setTimeout(() => dispatchEvent(new CustomEvent("library:select")), 300); } },
    { group: "Ações", icon: "archive", label: "Fazer backup completo…", keys: "copia seguranca zip", run: backup },
    ...EXPORT_FORMATS.map((f) => ({ group: "Ações", icon: f.icon, label: `Exportar catálogo em ${f.label}…`, keys: f.hint, run: () => exportCatalog(f.id) })),
    { group: "Ações", icon: "folder-open", label: "Abrir a pasta de dados", run: () => window.lume?.openDataFolder() },
    { group: "Aparência", icon: "monitor", label: "Tema igual ao Windows", keys: "sistema", run: () => setTheme("system") },
    { group: "Aparência", icon: "sun", label: "Tema claro", run: () => setTheme("light") },
    { group: "Aparência", icon: "moon", label: "Tema escuro", keys: "noite", run: () => setTheme("dark") },
    { group: "Ajuda", icon: "keyboard", label: "Atalhos de teclado", hint: "?", run: () => showShortcuts() },
  ];
}

async function newCollection() {
  const created = await formDialog({
    title: "Nova coleção",
    fields: [
      { name: "name", label: "Nome", required: true, maxlength: 80, placeholder: "Ex.: Leituras do trimestre" },
      { name: "description", label: "Descrição", type: "textarea", maxlength: 300 },
    ],
    submit: "Criar",
    onSubmit: (values) => api("collections", { method: "POST", body: values }),
  });
  if (created) {
    await refreshCollections();
    toast(`Coleção “${created.name}” criada`);
  }
}

function matches(item, words) {
  const text = fold(`${item.label} ${item.keys || ""} ${item.group}`);
  return words.every((w) => text.includes(w));
}

export function openPalette({ navigate, setTheme }) {
  if (open) return;
  const all = commands(navigate, setTheme);
  const input = h("input", { type: "text", placeholder: "Busque um documento, uma tela ou uma ação…", "aria-label": "Comando", autocomplete: "off", spellcheck: "false" });
  const list = h("div.palette-list", { role: "listbox" });
  const back = h("div.palette-back", { role: "dialog", "aria-modal": "true", "aria-label": "Paleta de comandos" },
    h("div.palette",
      h("div.palette-input", icon("command"), input, h("kbd", "Esc")),
      list,
      h("div.palette-foot",
        h("span", h("kbd", "↑"), h("kbd", "↓"), "navegar"),
        h("span", h("kbd", "Enter"), "abrir"),
        h("span", h("kbd", `${MOD} K`), "abrir ou fechar a paleta"))));
  let items = [];
  let docs = [];
  let active = 0;
  let token = 0;

  const close = () => {
    back.remove();
    open = null;
    document.removeEventListener("keydown", onKey, true);
  };

  function render() {
    const q = fold(input.value.trim());
    const words = q.split(/\s+/).filter(Boolean);
    const cmds = (words.length ? all.filter((c) => matches(c, words)) : all).slice(0, words.length ? 8 : 40);
    const cols = words.length ? store.collections.filter((c) => matches({ label: c.name, group: "coleção" }, words)).slice(0, 4) : [];
    items = [
      ...docs.map((d) => ({ group: "Documentos", doc: d, label: d.title, sub: [d.author, d.kind?.toUpperCase(), d.page_count ? `${d.page_count} págs.` : null].filter(Boolean).join(" · "), run: () => navigate(`/documento/${d.id}`) })),
      ...cols.map((c) => ({ group: "Coleções", icon: "collection", label: c.name, sub: `${c.document_count} documentos`, run: () => navigate(`/colecoes/${c.id}`) })),
      ...cmds,
    ];
    if (input.value.trim().length >= 2) {
      items.push({ group: "Busca", icon: "search", label: `Buscar “${input.value.trim()}” na biblioteca`, sub: "Título, autor, etiquetas e texto completo", run: () => navigate(`/?q=${encodeURIComponent(input.value.trim())}`) });
    }
    active = Math.min(active, Math.max(0, items.length - 1));
    let group = null;
    const nodes = [];
    items.forEach((item, i) => {
      if (item.group !== group) {
        group = item.group;
        nodes.push(h("div.palette-group", group));
      }
      const btn = h(`button.palette-item${i === active ? ".active" : ""}`, { type: "button", role: "option", "aria-selected": String(i === active) },
        h("span.ic", item.doc ? cover(item.doc, { mini: true }) : icon(item.icon || "arrow-right", "sm")),
        h("span", { style: { minWidth: 0 } }, h("strong", item.label), item.sub ? h("span.sub", item.sub) : null),
        item.hint ? h("kbd", item.hint) : i === active ? icon("enter", "sm") : h("span"));
      btn.addEventListener("mousemove", () => {
        if (active === i) return;
        active = i;
        list.querySelectorAll(".palette-item").forEach((el, j) => el.classList.toggle("active", j === i));
      });
      btn.addEventListener("click", () => runItem(item));
      nodes.push(btn);
    });
    fill(list, nodes.length ? nodes : h("div.palette-empty", "Nada encontrado. Tente outras palavras."));
    list.querySelector(".palette-item.active")?.scrollIntoView({ block: "nearest" });
  }

  const searchDocs = debounce(async () => {
    const q = input.value.trim();
    const mine = ++token;
    if (q.length < 2) {
      docs = [];
      render();
      return;
    }
    try {
      const data = await api("documents", { query: { q, page_size: 6, sort: "relevance", scope: "mine" } });
      if (mine !== token) return;
      docs = data.results;
      render();
    } catch {
      /* a busca é um extra: os comandos continuam funcionando */
    }
  }, 160);

  function runItem(item) {
    close();
    try {
      const result = item.run?.();
      if (result?.catch) result.catch((err) => toast(errorMessage(err), "err"));
    } catch (err) {
      toast(errorMessage(err), "err");
    }
  }

  function onKey(e) {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      close();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!items.length) return;
      active = (active + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
      render();
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (items[active]) runItem(items[active]);
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      close();
    }
  }

  input.addEventListener("input", () => {
    active = 0;
    render();
    searchDocs();
  });
  back.addEventListener("mousedown", (e) => e.target === back && close());
  document.addEventListener("keydown", onKey, true);
  document.body.append(back);
  open = { close };
  render();
  input.focus();
}

export function togglePalette(ctx) {
  if (open) open.close();
  else openPalette(ctx);
}

export function showShortcuts() {
  const row = (keys, label) => [h("span", keys.map((k, i) => [i ? " " : null, h("kbd", k)])), h("span", label)];
  modal({
    title: "Atalhos de teclado",
    wide: true,
    body: h("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px 32px" } },
      h("div",
        h("div.section-label", "Em qualquer tela"),
        h("div.shortcuts",
          row([MOD, "K"], "Paleta de comandos"),
          row(["/"], "Buscar na biblioteca"),
          row(["U"], "Adicionar documentos"),
          row(["S"], "Selecionar vários documentos"),
          row(["?"], "Esta lista de atalhos"))),
      h("div",
        h("div.section-label", "No leitor"),
        h("div.shortcuts",
          row(["←", "→"], "Página anterior e próxima"),
          row(["+", "−"], "Zoom ou tamanho da letra"),
          row(["F"], "Modo foco"),
          row([MOD, "F"], "Buscar no documento"),
          row(["Esc"], "Sair do foco e do leitor")))),
    actions: [{ label: "Fechar", kind: "primary" }],
  });
}
