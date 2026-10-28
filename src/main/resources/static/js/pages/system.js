import { api } from "../api.js";
import { on } from "../realtime.js";
import { debounce, fmt, h, icon, toast, fill } from "../ui.js";

const PROVIDERS = { local: "Resumo automático", anthropic: "Claude", openai: "API compatível com OpenAI" };

export async function mount({ root, navigate }) {
  const view = h("div");
  root.append(
    h("div.page-head", h("div", h("h1", "Sistema"), h("p", "O que o Lume está fazendo agora: leitura dos arquivos, descrições por IA, banco de dados e pasta da biblioteca.")),
      h("div.actions", h("button.btn", { onclick: () => window.lume.openDataFolder() }, icon("folder-open", "sm"), "Abrir a pasta de dados"))),
    view,
  );

  async function load() {
    render(await api("system"));
  }

  function service(ic, name, detail, pill) {
    return h("div.service", h("span.ic", icon(ic)), h("div", h("strong", name), h("span", detail)), pill);
  }

  function render(data) {
    const docs = data.documents;
    const processing = docs.queued + docs.extracting + docs.describing;
    const proc = data.processor;
    const ai = data.ai;
    const day = data.last_24h;
    const q = data.queues;
    const app = data.app;

    const services = h("div.panel", h("div.panel-head", h("h2", "Serviços")), h("div.panel-body", { style: { paddingTop: "4px", paddingBottom: "4px" } },
      service("cpu", "Leitor de PDF e capas (PDFBox)", proc.online
        ? `${fmt.n(proc.processed)} etapas nesta sessão · ${proc.busy} em andamento${proc.avg_ms ? ` · média ${fmt.ms(proc.avg_ms)}` : ""}${proc.restarts ? ` · reiniciado ${proc.restarts}×` : ""}`
        : proc.error || "Inicia sozinho quando houver um documento para ler",
      h(`span.status-pill.${proc.online ? "ok" : "warn"}`, proc.online ? "Ativo" : "Em espera")),
      service("layers", "Fila de processamento", `Leitura: ${q.extract.waiting} na fila, ${q.extract.running} em andamento · Descrição: ${q.describe.waiting} na fila, ${q.describe.running} em andamento · ${q.delayed} aguardando nova tentativa`,
        h(`span.status-pill.${processing ? "warn" : "ok"}`, processing ? `${processing} processando` : "Em dia")),
      service("sparkles", "Descrição por IA", `${ai.label}${ai.model ? ` · ${ai.model}` : ""}${ai.base_url ? ` · ${ai.base_url}` : ""}`,
        h(`span.status-pill.${ai.configured ? "ok" : "err"}`, ai.configured ? "Configurado" : "Falta configurar")),
      service("disk", "Banco de dados (SQLite)", `${fmt.bytes(app.db_bytes)} · busca em texto completo com FTS5`, h("span.status-pill.ok", "Local")),
    ));

    const about = h("div.panel", h("div.panel-head", h("h2", "Este computador")), h("div.panel-body",
      h("dl.kv",
        h("dt", "Pasta da biblioteca"), h("dd", h("code", { style: { fontFamily: "var(--mono)", fontSize: "12px", wordBreak: "break-all" } }, app.data_dir)),
        h("dt", "Modo"), h("dd", app.portable ? "Portátil (dados ao lado do Lume.exe)" : "Instalação do usuário"),
        h("dt", "Versão"), h("dd", `Lume ${app.version || "—"} · Java ${app.java || "—"} · Spring Boot ${app.spring_boot || "—"}`),
        h("dt", "Arquivos guardados"), h("dd", `${fmt.n(data.storage.count)} documentos · ${fmt.bytes(data.storage.bytes)}`)),
      h("div.chips", { style: { marginTop: "14px" } },
        h("button.btn.sm", { onclick: () => navigate("/preferencias#ia") }, icon("settings", "sm"), "Configurar a IA"),
        h("button.btn.sm", { onclick: () => window.lume.openDataFolder() }, icon("folder-open", "sm"), "Abrir a pasta"))));

    const jobs = h("div.panel", h("div.panel-head", h("h2", "Últimas etapas processadas")),
      h("div", { style: { overflowX: "auto" } }, h("table.table",
        h("thead", h("tr", h("th", "Documento"), h("th", "Etapa"), h("th", "Resultado"), h("th", "Tentativa"), h("th", "Duração"), h("th", "Quando"))),
        h("tbody", data.jobs.length ? data.jobs.map((j) => h("tr.row", { onclick: () => navigate(`/documento/${j.document_id}`) },
          h("td", h("strong", { style: { fontWeight: 600 } }, j.document_title), j.error ? h("div", { style: { fontSize: "12px", color: j.status === "succeeded" ? "var(--muted)" : "var(--danger)" } }, j.error.slice(0, 160)) : null),
          h("td", j.stage_label, j.details?.provider ? h("div.muted", { style: { fontSize: "12px" } }, j.details.model || PROVIDERS[j.details.provider] || j.details.provider) : null),
          h("td", h(`span.status-pill.${j.status === "succeeded" ? "ok" : j.status === "failed" ? "err" : "warn"}`, j.status_label)),
          h("td.num", j.attempt),
          h("td.num", fmt.ms(j.duration_ms)),
          h("td.muted", fmt.ago(j.started_at)))) : h("tr", h("td", { colspan: 6 }, h("span.muted", "Nada processado ainda.")))))));

    const failures = data.failures.length ? h("div.panel", { style: { marginBottom: "18px" } },
      h("div.panel-head", h("h2", `Documentos com erro (${data.failures.length})`), h("button.btn.sm", { onclick: retryAll }, icon("retry", "sm"), "Tentar todos de novo")),
      h("div.panel-body", data.failures.map((f) => h("div.service", h("span.ic", icon("alert")),
        h("div", h("strong", h("a", { href: `/documento/${f.id}` }, f.title)), h("span", f.detail || "Erro desconhecido")), h("span.muted", { style: { fontSize: "12px" } }, fmt.ago(f.updated_at)))))) : null;

    fill(view,
      h("div.kpis",
        kpi("Documentos", fmt.n(data.storage.count), fmt.bytes(data.storage.bytes)),
        kpi("Prontos", fmt.n(docs.ready), `${fmt.n(processing)} processando`),
        kpi("Com erro", fmt.n(docs.failed), docs.failed ? "veja abaixo" : "nenhum"),
        kpi("Etapas em 24 h", fmt.n(day.succeeded), `${fmt.n(day.failed)} falhas · ${fmt.n(day.retried)} novas tentativas`),
        kpi("Leitura do arquivo", fmt.ms(day.extract_ms), `tempo médio · descrição em ${fmt.ms(day.describe_ms)}`)),
      h("div.two-col", services, about),
      failures,
      jobs,
    );
  }

  function kpi(label, value, sub) {
    return h("div.kpi", h("div.label", label), h("div.value", value), h("div.sub", sub));
  }

  async function retryAll() {
    const { requeued } = await api("system/retry-failed", { method: "POST" });
    toast(`${requeued} ${requeued === 1 ? "documento voltou" : "documentos voltaram"} para a fila`);
    load();
  }

  await load();
  const soon = debounce(() => load().catch(() => {}), 800);
  const offs = [on("document.updated", soon), on("document.created", soon), on("document.deleted", soon), on("system.changed", soon)];
  const timer = setInterval(soon, 10000);
  return () => {
    offs.forEach((off) => off());
    clearInterval(timer);
  };
}
