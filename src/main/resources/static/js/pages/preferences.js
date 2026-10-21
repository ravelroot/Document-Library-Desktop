import { api } from "../api.js";
import { setTheme } from "../main.js";
import { setUser, store } from "../store.js";
import { avatar, errorMessage, fmt, h, icon, toast, fill } from "../ui.js";

const PROVIDERS = [
  ["local", "Resumo automático (no computador)", "Escolhe as frases que melhor representam o texto. Não precisa de internet nem de chave."],
  ["anthropic", "Claude (Anthropic)", "Descrições escritas pelo Claude. Precisa de uma chave de API da Anthropic."],
  ["openai", "API compatível com OpenAI", "OpenAI, Ollama, LM Studio, OpenRouter, Groq... Informe o endereço e o modelo."],
];

export async function mount({ root }) {
  const view = h("div");
  root.append(h("div.page-head", h("div", h("h1", "Preferências"), h("p", "Seu nome, aparência, descrição por IA e onde a biblioteca fica guardada."))), view);
  let aiConfig = await api("settings/ai");
  const info = await window.lume.info();

  function render() {
    const user = store.user;
    const name = h("input.input", { id: "pref-name", value: user.name, maxlength: 120 });
    const themeSeg = h("div.seg", { role: "group", "aria-label": "Tema" },
      [["system", "Igual ao Windows", "monitor"], ["light", "Claro", "sun"], ["dark", "Escuro", "moon"]].map(([value, label, ic]) =>
        h(`button${user.theme === value ? ".on" : ""}`, { type: "button", onclick: async () => { await setTheme(value); render(); } }, icon(ic, "sm"), label)));

    fill(view, h("div.settings",
      h("nav", h("a", { href: "#perfil" }, "Perfil"), h("a", { href: "#aparencia" }, "Aparência"), h("a", { href: "#ia" }, "Descrição por IA"),
        h("a", { href: "#dados" }, "Pasta de dados"), h("a", { href: "#atalhos" }, "Atalhos"), h("a", { href: "#sobre" }, "Sobre")),
      h("div",
        h("section.panel#perfil", h("div.panel-head", h("h2", "Perfil")), h("div.panel-body",
          h("div", { style: { display: "flex", gap: "14px", alignItems: "center", marginBottom: "18px" } }, avatar(user, "lg"),
            h("div", h("strong", user.name), h("div.muted", "Usado na saudação da biblioteca"))),
          h("div.field", h("label", { for: "pref-name" }, "Como quer ser chamado?"), name),
          h("button.btn.primary", { onclick: async () => {
            try {
              setUser(await api("me", { method: "PATCH", body: { name: name.value.trim() } }));
              toast("Nome atualizado");
              render();
            } catch (err) {
              toast(errorMessage(err), "err");
            }
          } }, "Salvar"))),
        h("section.panel#aparencia", h("div.panel-head", h("h2", "Aparência")), h("div.panel-body",
          h("p.muted", { style: { marginBottom: "12px" } }, "O tema vale para todo o aplicativo. No leitor, escolha também a cor da página (papel, sépia ou noite)."), themeSeg)),
        aiPanel(),
        h("section.panel#dados", h("div.panel-head", h("h2", "Pasta de dados")), h("div.panel-body",
          h("p.muted", { style: { marginBottom: "10px" } }, "Os documentos importados, as capas e o banco (lume.db) ficam aqui. Faça cópia desta pasta para ter um backup completo."),
          h("div.token-box", { style: { marginBottom: "12px" } }, info.data_dir),
          h("p.muted", { style: { marginBottom: "12px", fontSize: "13px" } }, info.portable
            ? "Modo portátil: os dados ficam na pasta Lume-dados, ao lado do Lume.exe."
            : "Para usar o Lume num pendrive, crie uma pasta chamada Lume-dados ao lado do Lume.exe antes de abrir."),
          h("button.btn", { onclick: () => window.lume.openDataFolder() }, icon("folder-open", "sm"), "Abrir no Explorador"))),
        h("section.panel#atalhos", h("div.panel-head", h("h2", "Atalhos de teclado")), h("div.panel-body", h("div.shortcuts",
          h("kbd", "/"), "Buscar na biblioteca",
          h("kbd", "U"), "Adicionar documentos",
          h("span", h("kbd", "←"), " ", h("kbd", "→")), "Página anterior e próxima no leitor",
          h("span", h("kbd", "+"), " ", h("kbd", "−")), "Zoom (PDF) ou tamanho da letra (DOCX)",
          h("kbd", "F"), "Modo foco no leitor",
          h("span", h("kbd", "Ctrl"), " ", h("kbd", "F")), "Buscar dentro do documento",
          h("kbd", "Esc"), "Sair do modo foco e depois do leitor"))),
        h("section.panel#sobre", h("div.panel-head", h("h2", "Sobre")), h("div.panel-body",
          h("dl.kv",
            h("dt", "Versão"), h("dd", `Lume ${info.version}`),
            h("dt", "Componentes"), h("dd", `Java ${info.java} · Spring Boot ${info.spring_boot} · Tomcat ${info.tomcat} · PDFBox ${info.pdfbox} · SQLite ${info.sqlite}`),
            h("dt", "Sistema"), h("dd", info.platform),
            h("dt", "Código-fonte"), h("dd", h("a", { href: "https://github.com/ravelroot/Document-Library-Desktop", target: "_blank" }, "github.com/ravelroot/Document-Library-Desktop")),
            h("dt", "Licença"), h("dd", "MIT · Copyright (©) 2026 Erik Pimenta e Clara Portes"))))),
    ));
    view.querySelectorAll(".settings section").forEach((s) => (s.style.marginBottom = "18px"));
  }

  function aiPanel() {
    const cfg = aiConfig;
    const provider = h("select.input", { id: "ai-provider" }, PROVIDERS.map(([value, label]) => h("option", { value, selected: value === cfg.provider }, label)));
    const hint = h("p.muted", { style: { fontSize: "13px", margin: "6px 0 14px" } });
    const model = h("input.input", { id: "ai-model", value: cfg.model || "", placeholder: "ex.: claude-haiku-5-5, gpt-4o-mini, llama3.1" });
    const baseUrl = h("input.input", { id: "ai-url", value: cfg.base_url || "", placeholder: "Padrão do provedor (ou http://localhost:11434/v1 para o Ollama)" });
    const key = h("input.input", { id: "ai-key", type: "password", autocomplete: "off", placeholder: cfg.has_key ? "•••••••• (guardada; deixe em branco para manter)" : "Cole a chave aqui" });
    const result = h("div", { hidden: true });
    const external = h("div",
      h("div.field", h("label", { for: "ai-model" }, "Modelo"), model),
      h("div.field", h("label", { for: "ai-url" }, "Endereço da API"), baseUrl),
      h("div.field", h("label", { for: "ai-key" }, "Chave de API"), key,
        h("span.hint", "A chave fica cifrada com o cofre do Windows (DPAPI) e nunca aparece de novo.")));
    const sync = () => {
      const [, , text] = PROVIDERS.find(([v]) => v === provider.value);
      hint.textContent = text;
      external.hidden = provider.value === "local";
    };
    provider.addEventListener("change", sync);
    sync();

    const values = () => {
      const body = { provider: provider.value, model: model.value.trim(), base_url: baseUrl.value.trim() };
      if (key.value.trim()) body.api_key = key.value.trim();
      return body;
    };
    const save = h("button.btn.primary", { onclick: async () => {
      try {
        aiConfig = await api("settings/ai", { method: "PUT", body: values() });
        store.ai = aiConfig;
        toast("Configuração da IA salva. Vale para os próximos documentos e para “Gerar de novo”.");
        render();
        location.hash = "ia";
      } catch (err) {
        toast(errorMessage(err), "err");
      }
    } }, "Salvar");
    const test = h("button.btn", { onclick: async () => {
      test.disabled = true;
      fill(result, h("div.notice.info", h("span.spinner"), h("div", "Testando…")));
      result.hidden = false;
      try {
        const answer = await api("settings/ai/test", { method: "POST", body: values() });
        fill(result, h(`div.notice${answer.ok ? ".info" : ""}`, icon(answer.ok ? "check" : "alert"), h("div", answer.message)));
      } catch (err) {
        fill(result, h("div.notice", icon("alert"), h("div", errorMessage(err))));
      } finally {
        test.disabled = false;
      }
    } }, icon("plug", "sm"), "Testar");
    const forget = cfg.has_key ? h("button.btn.ghost.danger", { onclick: async () => {
      aiConfig = await api("settings/ai", { method: "PUT", body: { api_key: "" } });
      toast("Chave apagada");
      render();
    } }, "Apagar a chave guardada") : null;

    return h("section.panel#ia", h("div.panel-head", h("h2", "Descrição por IA"),
      h(`span.status-pill.${cfg.configured ? "ok" : "err"}`, cfg.configured ? "Configurado" : "Falta configurar")),
      h("div.panel-body",
        h("div.field", h("label", { for: "ai-provider" }, "Quem escreve a descrição"), provider), hint,
        external,
        h("div.chips", save, test, forget),
        result,
        h("p.muted", { style: { fontSize: "12.5px", marginTop: "14px" } },
          "Se o provedor externo falhar ou estiver sem internet, o Lume usa o resumo automático e registra o motivo em Sistema. ",
          `Até ${fmt.n(cfg.max_input_chars)} caracteres de cada documento são enviados ao provedor externo.`)));
  }

  render();
  if (location.hash) setTimeout(() => document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: "start" }), 50);
  return null;
}
