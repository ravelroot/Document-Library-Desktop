import { api } from "./api.js";
import { on } from "./realtime.js";
import { store } from "./store.js";
import { STAGES, errorMessage, fmt, h, icon, fill, toast } from "./ui.js";

/**
 * Importação de documentos. Pelos botões (diálogo nativo do Windows) o Lume copia direto do disco pelo caminho do
 * arquivo; arrastando para a janela, o arquivo é enviado para a API local. Nos dois casos o núcleo em Java calcula a
 * impressão digital e põe na fila de leitura.
 */

const CONCURRENCY = 2;
const items = [];
let tray;
let list;
let title;
let context = { currentCollection: () => null };
let running = 0;

export function initUploads(ctx) {
  context = ctx;
  initDropzone();
  on("document.updated", ({ id, status }) => track(id, status));
  on("document.created", ({ id, status }) => track(id, status));
}

const basename = (p) => String(p).split(/[\\/]/).pop();

export async function pickFiles() {
  const paths = await window.lume.pickFiles();
  if (paths?.length) addPaths(paths);
}

export async function pickFolder() {
  const result = await window.lume.pickFolder();
  if (!result) return;
  if (!result.files.length) {
    toast(`Nenhum PDF ou DOCX em “${basename(result.folder)}”`, "err");
    return;
  }
  toast(`${fmt.n(result.files.length)} ${result.files.length === 1 ? "arquivo encontrado" : "arquivos encontrados"} em “${basename(result.folder)}”`);
  addPaths(result.files);
}

function initDropzone() {
  const zone = h("div.dropzone", { "aria-hidden": "true" },
    h("div.box", h("div.art", icon("file-up")), h("h2", "Solte para adicionar à biblioteca"),
      h("p", `PDF ou DOCX, até ${store.maxUploadMb} MB cada. Pastas também valem. A capa e a descrição ficam prontas em instantes.`)));
  document.body.append(zone);
  let depth = 0;
  const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes("Files");
  addEventListener("dragenter", (e) => {
    if (!hasFiles(e) || document.getElementById("app").hidden) return;
    depth += 1;
    zone.classList.add("show");
  });
  addEventListener("dragleave", (e) => {
    if (!hasFiles(e)) return;
    depth = Math.max(0, depth - 1);
    if (!depth) zone.classList.remove("show");
  });
  addEventListener("dragover", (e) => hasFiles(e) && e.preventDefault());
  addEventListener("drop", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth = 0;
    zone.classList.remove("show");
    if (document.getElementById("app").hidden) return;
    const paths = [...e.dataTransfer.files].map((f) => window.lume.pathForFile(f)).filter(Boolean);
    if (paths.length) {
      // Pastas soltas na janela viram a lista dos PDFs e DOCX que estão dentro delas.
      window.lume.expandPaths(paths).then(addPaths).catch((err) => toast(errorMessage(err), "err"));
      return;
    }
    // Na janela do Edge o caminho no disco não é conhecido: o arquivo é enviado para o Lume, que copia para a biblioteca.
    collectFiles(e.dataTransfer).then(addFiles).catch((err) => toast(errorMessage(err), "err"));
  });
}

/** Arquivos (e o conteúdo das pastas) soltos na janela, sem temporários do Office nem ocultos. */
async function collectFiles(transfer) {
  const out = [];
  const visit = async (entry, depth) => {
    if (!entry || out.length >= 2000 || depth > 12 || entry.name.startsWith(".") || entry.name.startsWith("~$")) return;
    if (entry.isFile) {
      out.push(await new Promise((resolve, reject) => entry.file(resolve, reject)));
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
        if (!batch.length) break;
        for (const child of batch) await visit(child, depth + 1);
      }
    }
  };
  const entries = [...(transfer.items || [])].map((item) => item.webkitGetAsEntry?.()).filter(Boolean);
  if (!entries.length) return [...transfer.files];
  for (const entry of entries) await visit(entry, 0);
  return out;
}

export function addFiles(files) {
  const collection = context.currentCollection();
  let ignored = 0;
  for (const file of files) {
    if (/\.(pdf|docx)$/i.test(file.name)) items.unshift({ file, name: file.name, status: "copying", id: null, message: "", collection });
    else ignored += 1;
  }
  if (ignored) {
    items.unshift({ name: ignored === 1 ? "1 arquivo ignorado" : `${ignored} arquivos ignorados`, status: "error", message: "Só PDF e DOCX são aceitos" });
  }
  renderTray();
  pump();
}

export function addPaths(paths) {
  const collection = context.currentCollection();
  const accepted = [];
  let ignored = 0;
  for (const path of paths) {
    if (/\.(pdf|docx)$/i.test(path)) accepted.push(path);
    else ignored += 1;
  }
  for (const path of accepted) items.unshift({ path, name: basename(path), status: "copying", id: null, message: "", collection });
  if (ignored) {
    items.unshift({ name: ignored === 1 ? "1 arquivo ignorado" : `${ignored} arquivos ignorados`, status: "error", message: "Só PDF e DOCX são aceitos" });
  }
  renderTray();
  pump();
}

function pump() {
  while (running < CONCURRENCY) {
    const next = items.find((i) => i.status === "copying" && !i.started);
    if (!next) break;
    next.started = true;
    running += 1;
    send(next).finally(() => {
      running -= 1;
      pump();
    });
  }
}

async function send(item) {
  try {
    const [result] = item.file
      ? [await window.lume.upload(item.file, { collection: item.collection })]
      : await window.lume.importFiles([item.path], { collection: item.collection });
    if (!result.ok) throw new Error(result.error);
    const doc = result.document;
    item.id = doc.id;
    item.duplicate = result.duplicate;
    item.status = result.duplicate ? "duplicate" : doc.status;
    window.dispatchEvent(new CustomEvent("library:uploaded", { detail: { ...doc, duplicate: result.duplicate } }));
  } catch (err) {
    item.status = "error";
    item.message = errorMessage(err);
  }
  renderTray();
}

function track(id, status) {
  const item = items.find((i) => i.id === id);
  if (!item || item.status === "duplicate") return;
  item.status = status;
  if (status === "failed") {
    api(`documents/${id}`).then((doc) => {
      item.message = doc.status_detail;
      renderItem(item);
    }).catch(() => {});
  }
  renderItem(item);
  renderTitle();
}

function stageText(item) {
  if (item.status === "copying") return item.started ? "Copiando para a biblioteca…" : "Aguardando";
  if (item.status === "duplicate") return "Já estava na sua biblioteca";
  if (item.status === "error") return item.message;
  if (item.status === "failed") return item.message || "Não foi possível ler o arquivo";
  return STAGES[item.status] || item.status;
}

function renderItem(item) {
  if (!item.el) return;
  const done = item.status === "ready" || item.status === "duplicate";
  const bad = item.status === "error" || item.status === "failed";
  const pct = item.status === "copying" ? (item.started ? 25 : 5) : { queued: 45, extracting: 65, describing: 88 }[item.status] ?? 100;
  fill(item.el,
    h(`span.ic${done ? ".ok" : bad ? ".err" : ""}`, done ? icon("check") : bad ? icon("alert") : h("span.spinner")),
    h("div", { style: { minWidth: 0 } },
      h("div.name", { title: item.path || item.name }, item.name),
      h(`div.stage${bad ? ".err" : ""}`, stageText(item)),
      !done && !bad ? h("div.bar", h("i", { style: { width: `${pct}%` } })) : null),
    done && item.id ? h("a.btn.sm.ghost", { href: `/documento/${item.id}` }, "Abrir") : h("span"),
  );
}

function renderTitle() {
  if (!title) return;
  const active = items.filter((i) => !["ready", "duplicate", "error", "failed"].includes(i.status)).length;
  title.textContent = active
    ? `Processando ${active} de ${items.length} ${items.length === 1 ? "arquivo" : "arquivos"}`
    : `${items.length} ${items.length === 1 ? "arquivo adicionado" : "arquivos adicionados"}`;
}

function renderTray() {
  if (!items.length) {
    tray?.remove();
    tray = null;
    return;
  }
  if (!tray) {
    title = h("span");
    list = h("div.tray-list");
    const toggle = h("button.btn.ghost.icon.sm", { "aria-label": "Recolher" }, icon("chevron-down"));
    toggle.addEventListener("click", () => {
      list.hidden = !list.hidden;
      toggle.style.transform = list.hidden ? "rotate(180deg)" : "";
    });
    const close = h("button.btn.ghost.icon.sm", { "aria-label": "Fechar", style: { marginLeft: "0" } }, icon("x"));
    close.addEventListener("click", () => {
      items.splice(0, items.length, ...items.filter((i) => i.status === "copying"));
      renderTray();
    });
    tray = h("section.tray", { "aria-label": "Importações" }, h("div.tray-head", icon("upload", "sm"), title, toggle, close), list);
    document.body.append(tray);
  }
  list.replaceChildren(...items.slice(0, 300).map((item) => {
    item.el ||= h("div.tray-item");
    renderItem(item);
    return item.el;
  }));
  renderTitle();
}
