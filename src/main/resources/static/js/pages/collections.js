import { api } from "../api.js";
import { refreshCollections, store, subscribe } from "../store.js";
import { clear, fmt, formDialog, h, icon, fill } from "../ui.js";

export async function mount({ root, navigate }) {
  const grid = h("div.collections");
  root.append(
    h("div.page-head", h("div", h("h1", "Coleções"), h("p", "Agrupe documentos por assunto, projeto ou turma. Um documento pode estar em várias coleções.")),
      h("div.actions", h("button.btn.primary", { onclick: create }, icon("folder-plus", "sm"), "Nova coleção"))),
    grid,
  );

  async function create() {
    const created = await formDialog({
      title: "Nova coleção",
      submit: "Criar",
      fields: [
        { name: "name", label: "Nome", required: true, maxlength: 80, placeholder: "Ex.: Leituras do trimestre" },
        { name: "description", label: "Descrição", type: "textarea", maxlength: 300 },
      ],
      onSubmit: (values) => api("collections", { method: "POST", body: values }),
    });
    if (created) {
      await refreshCollections();
      navigate(`/colecoes/${created.id}`);
    }
  }

  function render() {
    fill(grid, 
      ...store.collections.map((c) => h("a.collection-tile", { href: `/colecoes/${c.id}` },
        h("div.mosaic", h("div.stack", [1, 2, 0].map((i) => {
          const cover = c.covers[i];
          return h("div", { style: { "--cover-color": cover?.color || "var(--surface-3)", visibility: cover || i === 0 ? "visible" : "hidden" } },
            cover?.url ? h("img", { src: cover.url, alt: "", loading: "lazy" }) : null);
        }))),
        h("div", h("h3", c.name), h("p", `${fmt.n(c.document_count)} ${c.document_count === 1 ? "documento" : "documentos"}${c.description ? ` · ${c.description}` : ""}`)))),
      h("button.collection-tile.new-tile", { type: "button", onclick: create, style: { border: 0, background: "none", padding: 0, textAlign: "left" } },
        h("div.mosaic", h("div", { style: { display: "grid", justifyItems: "center", gap: "8px" } }, icon("folder-plus", "lg"), "Nova coleção")),
        h("div", h("h3", "Criar coleção"), h("p", "Junte documentos relacionados"))),
    );
  }

  render();
  const unsubscribe = subscribe(render);
  await refreshCollections();
  return unsubscribe;
}
