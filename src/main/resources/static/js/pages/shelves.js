import { api } from "../api.js";
import { progressOf } from "../components/card.js";
import { on } from "../realtime.js";
import { refreshStats } from "../store.js";
import { SHELVES, clear, cover, errorMessage, h, toast } from "../ui.js";

const HINTS = {
  para_ler: "Arraste para cá o que quer ler depois.",
  lendo: "Abrir um documento no leitor coloca ele aqui.",
  lido: "Marque como lido ao terminar.",
};

export async function mount({ root }) {
  const board = h("div.board");
  root.append(
    h("div.page-head", h("div", h("h1", "Estantes"), h("p", "Arraste um documento de uma estante para outra para mudar o estado da leitura."))),
    board,
  );

  async function load() {
    const lists = await Promise.all(
      Object.keys(SHELVES).map((shelf) => api("documents", { query: { scope: "all", shelf, page_size: 200, sort: "last_read" } })),
    );
    clear(board);
    Object.keys(SHELVES).forEach((shelf, index) => board.append(column(shelf, lists[index])));
  }

  function column(shelf, data) {
    const body = h("div.column-body");
    const col = h("section.column", { style: { "--shelf-color": `var(--shelf-${shelf})` }, "aria-label": SHELVES[shelf] },
      h("header.column-head", h("span.dot"), shelf === "lido" ? "Lidos" : SHELVES[shelf], h("span.n", data.count)), body);
    if (!data.results.length) body.append(h("div.column-empty", HINTS[shelf]));
    for (const doc of data.results) {
      const progress = progressOf(doc);
      const card = h("a.shelf-card", { href: `/documento/${doc.id}`, draggable: "true", dataset: { id: doc.id } },
        cover(doc, { mini: true }),
        h("div", { style: { minWidth: 0 } }, h("strong", doc.title), h("span", doc.author || doc.kind.toUpperCase()),
          shelf === "lendo" && progress > 0 ? h("div.bar", { style: { marginTop: "7px" } }, h("i", { style: { width: `${progress * 100}%` } })) : null));
      card.addEventListener("dragstart", (e) => {
        e.dataTransfer.setData("application/x-document", doc.id);
        e.dataTransfer.setData("text/shelf", shelf);
      });
      body.append(card);
    }
    col.addEventListener("dragover", (e) => {
      if (!e.dataTransfer.types.includes("application/x-document")) return;
      e.preventDefault();
      col.classList.add("over");
    });
    col.addEventListener("dragleave", (e) => !col.contains(e.relatedTarget) && col.classList.remove("over"));
    col.addEventListener("drop", async (e) => {
      e.preventDefault();
      col.classList.remove("over");
      const id = e.dataTransfer.getData("application/x-document");
      if (!id || e.dataTransfer.getData("text/shelf") === shelf) return;
      try {
        await api(`documents/${id}/reading`, { method: "PATCH", body: { shelf } });
        toast(`Movido para “${shelf === "lido" ? "Lidos" : SHELVES[shelf]}”`);
        refreshStats();
        load();
      } catch (err) {
        toast(errorMessage(err), "err");
      }
    });
    return col;
  }

  await load();
  const off = on("reading.updated", () => load());
  return off;
}
