import { api } from "./api.js";
import { errorMessage, fmt, toast } from "./ui.js";

/* Ações compartilhadas entre a paleta de comandos, a página Ferramentas e a barra de seleção. */

export const EXPORT_FORMATS = [
  { id: "csv", label: "Planilha (CSV)", hint: "Excel, Google Planilhas, LibreOffice", icon: "sheet" },
  { id: "json", label: "JSON", hint: "Scripts, n8n, Make, Zapier", icon: "braces" },
  { id: "bibtex", label: "BibTeX", hint: "Zotero, Mendeley, JabRef, LaTeX", icon: "cap" },
  { id: "markdown", label: "Markdown", hint: "Notion, Obsidian, GitHub", icon: "file-text" },
];

/** Backup completo (banco + arquivos) num .zip escolhido pelo diálogo do Windows. */
export async function backup() {
  try {
    const result = await api("tools/backup", { method: "POST" });
    if (!result?.ok) return null;
    toast(`Backup salvo · ${fmt.n(result.documents)} documentos · ${fmt.bytes(result.bytes)}`);
    return result;
  } catch (err) {
    toast(errorMessage(err), "err");
    return null;
  }
}

/** Exporta o catálogo (ou só os documentos escolhidos) e salva com o diálogo nativo. */
export async function exportCatalog(format, ids) {
  try {
    const data = await api("tools/export/catalog", { query: { format, ids: ids?.length ? ids.join(",") : undefined } });
    const saved = await window.lume.saveText(data.filename, data.content);
    if (saved?.ok) toast(`Catálogo exportado · ${fmt.n(data.count)} ${data.count === 1 ? "documento" : "documentos"}`);
    return saved;
  } catch (err) {
    toast(errorMessage(err), "err");
    return null;
  }
}

/** Uma nota Markdown por documento, numa pasta (cofre do Obsidian, Logseq, Notion...). */
export async function exportMarkdownFolder() {
  try {
    const result = await api("tools/export/markdown-folder", { method: "POST" });
    if (!result?.ok) return null;
    toast(`${fmt.n(result.files)} notas salvas em ${result.folder}`);
    return result;
  } catch (err) {
    toast(errorMessage(err), "err");
    return null;
  }
}

/** Abre uma pasta ou arquivo no Explorador do Windows. */
export function openPath(path) {
  return Promise.resolve(window.lume?.openPath(path)).catch(() => null);
}
