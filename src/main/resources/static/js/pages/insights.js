import { api } from "../api.js";
import { on } from "../realtime.js";
import { debounce, fill, fmt, h, icon } from "../ui.js";

const MONTHS = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

function monthLabel(key) {
  const [, m] = String(key).split("-");
  return MONTHS[Number(m) - 1] || key;
}

function bars(series, alt) {
  const max = Math.max(1, ...series.map((s) => s.count));
  return h("div.bars", series.map((s) => h("div.col", { title: `${s.month}: ${fmt.n(s.count)}` },
    h("span.v", s.count ? fmt.n(s.count) : ""),
    h(`i.b${alt ? ".alt" : ""}`, { style: { height: `${Math.max(1.5, (s.count / max) * 100)}%` } }),
    h("span.m", monthLabel(s.month)))));
}

function hbars(rows) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return rows.map((r) => h("div.hbar", h("span", { title: r.label }, r.label), h("span.track", h("i", { style: { width: `${(r.value / max) * 100}%` } })), h("span.num", fmt.n(r.value))));
}

const LANGS = { pt: "Português", en: "Inglês", es: "Espanhol", fr: "Francês", de: "Alemão", it: "Italiano" };

export async function mount({ root }) {
  const view = h("div");
  root.append(h("div.page-head", h("div", h("span.eyebrow", "Panorama"), h("h1", "Estatísticas"),
    h("p", "Como a sua biblioteca cresce e o que você tem lido. Os números vêm do banco local e se atualizam sozinhos."))), view);

  function kpi(label, value, sub) {
    return h("div.kpi", h("div.label", label), h("div.value", value), h("div.sub", sub));
  }

  function render(d) {
    const t = d.totals;
    const r = d.reading;
    const year = new Date().getFullYear();
    if (!t.documents) {
      fill(view, h("div.empty", h("div.art", icon("chart")), h("h3", "Ainda não há o que medir"),
        h("p", "Importe alguns documentos e volte aqui para ver quanto você leu, os assuntos que mais aparecem e como a biblioteca cresce.")));
      return;
    }
    const langs = (d.languages || []).filter((l) => l.language).map((l) => ({ label: LANGS[l.language] || fmt.lang(l.language), value: l.count }));
    fill(view,
      h("div.kpis",
        kpi("Documentos", fmt.n(t.documents), `${fmt.n(d.kinds.pdf)} PDF · ${fmt.n(d.kinds.docx)} DOCX · ${fmt.bytes(t.bytes)}`),
        kpi("Páginas", fmt.n(t.pages), `${fmt.n(t.words)} palavras na biblioteca`),
        kpi(`Lidos em ${year}`, fmt.n(r.finished_this_year), `${fmt.n(r.finished_total)} no total`),
        kpi("Em leitura", fmt.n(r.in_progress), `${fmt.n(r.want_to_read)} na fila “Quero ler”`),
        kpi("Páginas lidas", fmt.n(r.pages_read), `${fmt.n(r.favorites)} favoritos`),
        kpi("Anotações", fmt.n(t.annotations), `${fmt.n(t.highlights)} destaques · ${fmt.n(t.notes)} notas · ${fmt.n(t.bookmarks)} marcadores`)),
      h("div.two-col",
        h("div.panel", h("div.panel-head", h("h2", "Documentos adicionados"), h("span.legend", h("span", h("i", { style: { background: "var(--ardosia)" } }), "últimos 12 meses"))),
          h("div.panel-body", bars(d.added_by_month))),
        h("div.panel", h("div.panel-head", h("h2", "Leituras concluídas"), h("span.legend", h("span", h("i", { style: { background: "var(--signal)" } }), "estante “Lidos”"))),
          h("div.panel-body", bars(d.finished_by_month, true)))),
      h("div.two-col",
        h("div.panel", h("div.panel-head", h("h2", "Assuntos mais frequentes"), h("a.btn.ghost.sm", { href: "/" }, "Ver biblioteca")),
          h("div.panel-body", d.top_tags.length ? hbars(d.top_tags.map((tag) => ({ label: tag.name, value: tag.count })))
            : h("p.muted", "As etiquetas aparecem aqui quando os documentos forem descritos."))),
        h("div.panel", h("div.panel-head", h("h2", "Formatos, idiomas e maiores arquivos")),
          h("div.panel-body",
            hbars([{ label: "PDF", value: d.kinds.pdf }, { label: "DOCX", value: d.kinds.docx }, ...langs]),
            h("div.section-label", { style: { marginTop: "16px" } }, "Maiores documentos"),
            d.largest.map((doc) => h("div.hbar", h("a", { href: `/documento/${doc.id}`, title: doc.title, style: { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } }, doc.title),
              h("span.muted", { style: { fontSize: "12px" } }, doc.page_count ? `${fmt.n(doc.page_count)} págs.` : doc.kind.toUpperCase()),
              h("span.num", fmt.bytes(doc.size_bytes))))))),
      h("p.muted", { style: { fontSize: "12.5px", marginTop: "18px" } },
        `${fmt.n(t.collections)} coleções · ${fmt.n(t.tags)} etiquetas diferentes. Para levar estes dados para uma planilha, use Ferramentas → Exportar catálogo.`),
    );
  }

  const load = async () => render(await api("insights"));
  await load();
  const soon = debounce(() => load().catch(() => {}), 800);
  const offs = ["document.created", "document.updated", "document.deleted", "reading.updated"].map((type) => on(type, soon));
  return () => offs.forEach((off) => off());
}
