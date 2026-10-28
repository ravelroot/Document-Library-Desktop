package io.github.ravelroot.lume.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Values;
import tools.jackson.core.JacksonException;

/**
 * Descrição de documentos por IA.
 *
 * <p>Provedores (escolhidos em Preferências → Descrição por IA):</p>
 * <ul>
 *   <li>local: resumo extrativo feito no próprio computador (padrão, sem chave e sem internet);</li>
 *   <li>anthropic: API de Mensagens da Anthropic (Claude);</li>
 *   <li>openai: qualquer API compatível com /v1/chat/completions (OpenAI, Ollama, LM Studio, OpenRouter, Groq...).</li>
 * </ul>
 * <p>Se o provedor externo falhar, a descrição cai para o resumo local e o motivo fica registrado na etapa.</p>
 */
@Service
public class AiService {

    static final String SYSTEM_PROMPT = "Você descreve documentos para uma biblioteca digital. Escreva sempre em português do "
            + "Brasil, com tom neutro e informativo, e responda apenas com JSON válido.";

    static final String USER_PROMPT = """
            Leia os dados do documento abaixo e devolva um JSON com dois campos:
            - "descricao": de 2 a 3 frases, no máximo 320 caracteres, dizendo do que o documento trata e para quem ele é útil.
              Não comece com "Este documento", não invente informações e não use aspas nem markdown.
            - "tags": de 2 a 4 palavras-chave curtas, em minúsculas.

            Título: {title}
            Autor: {author}
            Tipo: {kind}, {pages} páginas
            Sumário: {outline}

            Trecho do texto:
            <<<
            {excerpt}
            >>>""";

    private static final String TEST_SAMPLE = "A fotossíntese é o processo pelo qual plantas, algas e algumas bactérias "
            + "transformam luz em energia química. Ela acontece nos cloroplastos e libera oxigênio. O texto explica as etapas "
            + "clara e escura e a importância do processo para a vida no planeta, com exemplos para estudantes do ensino médio.";

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");
    private static final Pattern EDGE_QUOTES = Pattern.compile("^\"+|\"+$");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

    private final HttpTransport transport;

    public AiService(HttpTransport transport) {
        this.transport = transport;
    }

    /* ---------- Pedido ---------- */

    static String buildExcerpt(String text, int limit, boolean byLine) {
        String joined = String.join("\n", LocalSummarizer.cleanText(text, byLine));
        if (joined.length() <= limit) {
            return joined;
        }
        String head = joined.substring(0, (int) Math.floor(limit * 0.75));
        int middleStart = joined.length() / 2;
        int middleEnd = Math.min(joined.length(), middleStart + (int) Math.floor(limit * 0.25));
        return head + "\n[...]\n" + joined.substring(middleStart, middleEnd);
    }

    static String buildPrompt(DocumentFacts doc, String text, int limit) {
        List<String> titles = doc.outline() == null ? List.of() : doc.outline().stream()
                .limit(30).filter(t -> t != null && !t.isEmpty()).toList();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", doc.title());
        values.put("author", doc.author() == null || doc.author().isEmpty() ? "não informado" : doc.author());
        values.put("kind", String.valueOf(doc.kind()).toUpperCase(Locale.ROOT));
        values.put("pages", doc.pageCount() == null || doc.pageCount() == 0 ? "?" : String.valueOf(doc.pageCount()));
        values.put("outline", titles.isEmpty() ? "não informado" : String.join("; ", titles));
        values.put("excerpt", buildExcerpt(text, limit, "docx".equals(doc.kind())));
        Matcher m = PLACEHOLDER.matcher(USER_PROMPT);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Lê {"descricao", "tags"} da resposta do modelo (aceita o JSON dentro de ```json ... ```). */
    static Answer parseAnswer(String raw) throws ProviderException {
        Matcher match = JSON_OBJECT.matcher(raw == null ? "" : raw);
        if (!match.find()) {
            throw new ProviderException("A resposta não veio em JSON.");
        }
        Map<String, Object> data;
        try {
            Object parsed = Json.MAPPER.readValue(match.group(), Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new ProviderException("A resposta não veio em JSON.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            data = map;
        } catch (JacksonException e) {
            throw new ProviderException("JSON inválido na resposta: " + e.getOriginalMessage());
        }
        Object description = Values.truthy(data.get("descricao")) ? data.get("descricao") : data.get("description");
        String summary = EDGE_QUOTES.matcher(Texts.trim(Values.truthy(description) ? Values.text(description) : "")).replaceAll("");
        if (summary.length() < 20) {
            throw new ProviderException("A descrição veio vazia.");
        }
        List<String> tags = new ArrayList<>();
        if (data.get("tags") instanceof List<?> list) {
            for (Object tag : list) {
                String clean = Texts.trim(Values.text(tag)).toLowerCase(Locale.ROOT);
                clean = clean.length() > 40 ? clean.substring(0, 40) : clean;
                if (!clean.isEmpty() && tags.size() < 4) {
                    tags.add(clean);
                }
            }
        }
        return new Answer(LocalSummarizer.shorten(summary, 420), tags);
    }

    record Answer(String summary, List<String> tags) {
    }

    /* ---------- Provedores ---------- */

    private Map<String, Object> postJson(String url, Map<String, String> headers, Map<String, Object> body, int timeoutS)
            throws ProviderException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Endereço inválido: " + url);
        }
        Map<String, String> allHeaders = new LinkedHashMap<>();
        allHeaders.put("content-type", "application/json");
        allHeaders.putAll(headers);
        HttpTransport.Reply reply;
        try {
            reply = transport.post(uri, allHeaders, Json.write(body), Duration.ofSeconds(timeoutS));
        } catch (HttpTimeoutException e) {
            throw new ProviderException("Não foi possível falar com " + uri.getAuthority() + ": sem resposta em " + timeoutS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Não foi possível falar com " + uri.getAuthority() + ": operação interrompida");
        } catch (IOException e) {
            throw new ProviderException("Não foi possível falar com " + uri.getAuthority() + ": " + networkReason(e));
        }
        if (reply.status() >= 400) {
            String text = reply.body() == null ? "" : reply.body();
            throw new ProviderException("O serviço respondeu " + reply.status() + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        Object parsed = Json.parse(reply.body(), null);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ProviderException("O serviço devolveu uma resposta que não é JSON.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private static String networkReason(IOException e) {
        if (e instanceof ConnectException) {
            return "conexão recusada (ECONNREFUSED)";
        }
        if (e instanceof UnknownHostException) {
            return "endereço não encontrado (ENOTFOUND)";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? "falha de rede" : message;
    }

    private static String base(AiConfig cfg) {
        String url = cfg.baseUrl().isEmpty() ? AiConfig.DEFAULT_URLS.get(cfg.provider()) : cfg.baseUrl();
        return TRAILING_SLASHES.matcher(url).replaceAll("");
    }

    private String callAnthropic(AiConfig cfg, String system, String prompt, int maxTokens) throws ProviderException {
        if (cfg.apiKey().isEmpty()) {
            throw new ProviderException("Falta a chave de API da Anthropic.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        Map<String, Object> data = postJson(base(cfg) + "/v1/messages",
                Map.of("x-api-key", cfg.apiKey(), "anthropic-version", "2023-06-01"), body, cfg.timeoutS());
        StringBuilder text = new StringBuilder();
        if (data.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") != null) {
                    text.append(b.get("text"));
                }
            }
        }
        return text.toString();
    }

    private String callOpenAi(AiConfig cfg, String system, String prompt, int maxTokens) throws ProviderException {
        if (cfg.model().isEmpty()) {
            throw new ProviderException("Informe o nome do modelo (ex.: o modelo no OpenAI ou no Ollama).");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", prompt)));
        Map<String, String> headers = cfg.apiKey().isEmpty() ? Map.of() : Map.of("authorization", "Bearer " + cfg.apiKey());
        Map<String, Object> data = postJson(base(cfg) + "/chat/completions", headers, body, cfg.timeoutS());
        if (!(data.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new ProviderException("A API não devolveu nenhuma resposta.");
        }
        if (choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") != null) {
            return String.valueOf(message.get("content"));
        }
        return "";
    }

    private String call(AiConfig cfg, String prompt) throws ProviderException {
        return call(cfg, SYSTEM_PROMPT, prompt, 500);
    }

    private String call(AiConfig cfg, String system, String prompt, int maxTokens) throws ProviderException {
        return switch (cfg.provider()) {
            case "anthropic" -> callAnthropic(cfg, system, prompt, maxTokens);
            case "openai" -> callOpenAi(cfg, system, prompt, maxTokens);
            default -> throw new ProviderException("Provedor desconhecido: " + cfg.provider());
        };
    }

    private static boolean external(AiConfig cfg) {
        return "anthropic".equals(cfg.provider()) || "openai".equals(cfg.provider());
    }

    /* ---------- Descrição ---------- */

    public Description describeLocal(DocumentFacts doc, String text, List<String> warnings) {
        List<String> headings = doc.outline() == null ? List.of() : doc.outline();
        boolean byLine = "docx".equals(doc.kind());
        String summary = LocalSummarizer.summarize(text, doc.title(), headings, byLine);
        if (summary.isEmpty()) {
            summary = String.valueOf(doc.kind()).toUpperCase(Locale.ROOT) + " sem texto extraível (provavelmente digitalizado como imagem).";
        }
        List<String> tags = LocalSummarizer.keywords(text, doc.title(), headings, doc.author(), byLine);
        return new Description(summary, tags, "local", "", LocalSummarizer.guessLanguage(text), List.copyOf(warnings));
    }

    /** Gera a descrição. Nunca lança: em último caso devolve o resumo local com o aviso do que falhou. */
    public Description describe(DocumentFacts doc, String text, AiConfig cfg) {
        String value = text == null ? "" : text;
        String language = LocalSummarizer.guessLanguage(value);
        if (!external(cfg) || Texts.trim(value).length() < 200) {
            return describeLocal(doc, value, List.of());
        }
        try {
            Answer answer = parseAnswer(call(cfg, buildPrompt(doc, value, cfg.maxInputChars())));
            return new Description(answer.summary(), answer.tags(), cfg.provider(), cfg.model(), language, List.of());
        } catch (ProviderException e) {
            return describeLocal(doc, value, List.of(cfg.label() + " falhou: " + e.getMessage()));
        }
    }

    /** Teste rápido do provedor (botão "Testar" nas Preferências). */
    public Map<String, Object> testProvider(AiConfig cfg) {
        if (!external(cfg)) {
            return Map.of("ok", true, "message", "O resumo automático funciona sem internet e sem chave.");
        }
        long started = System.nanoTime();
        try {
            DocumentFacts sample = new DocumentFacts("Fotossíntese em 5 minutos", "", "pdf", 3, List.of());
            Answer answer = parseAnswer(call(cfg, buildPrompt(sample, TEST_SAMPLE, 2000)));
            double seconds = (System.nanoTime() - started) / 1e9;
            return Map.of("ok", true, "message", String.format(Locale.ROOT, "Funcionou em %.1f s: “%s”", seconds,
                    LocalSummarizer.shorten(answer.summary(), 140)));
        } catch (ProviderException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    /* ---------- Perguntar ao documento ---------- */

    static final String QA_SYSTEM_PROMPT = "Você responde perguntas sobre um documento usando somente os trechos numerados "
            + "que recebe. Escreva em português do Brasil, de forma direta e curta (no máximo 5 frases), e cite os trechos usados "
            + "como [1], [2]. Se os trechos não trazem a resposta, diga isso com franqueza. Não use markdown.";

    /** Resposta para "Perguntar ao documento". */
    public record QaAnswer(String answer, String mode, String model, List<PassageFinder.Passage> passages, String warning) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answer", answer);
            out.put("mode", mode);
            out.put("model", model);
            out.put("passages", passages.stream().map(PassageFinder.Passage::toMap).toList());
            out.put("warning", warning);
            return out;
        }
    }

    static String buildQaPrompt(DocumentFacts doc, String question, List<PassageFinder.Passage> passages) {
        StringBuilder out = new StringBuilder();
        out.append("Documento: ").append(doc.title());
        if (doc.author() != null && !doc.author().isEmpty()) {
            out.append(" (").append(doc.author()).append(")");
        }
        out.append("\n\nTrechos:\n");
        for (int i = 0; i < passages.size(); i++) {
            PassageFinder.Passage p = passages.get(i);
            out.append('[').append(i + 1).append(']');
            if (p.page() != null) {
                out.append(" (pág. ").append(p.page()).append(')');
            }
            out.append(' ').append(p.text()).append("\n\n");
        }
        out.append("Pergunta: ").append(question);
        return out.toString();
    }

    /**
     * Responde uma pergunta sobre o documento. No resumo local, a resposta são as frases do trecho mais relevante; com um
     * provedor externo, o modelo lê os melhores trechos e responde citando-os. Nunca lança por falha do provedor.
     *
     * @param summary resumo já guardado, usado quando nenhum trecho combina com a pergunta
     */
    public QaAnswer answerQuestion(DocumentFacts doc, String text, String summary, String question, AiConfig cfg) {
        boolean byPage = "pdf".equals(doc.kind());
        List<PassageFinder.Passage> chunks = PassageFinder.chunks(text, byPage);
        List<PassageFinder.Passage> ranked = PassageFinder.rank(chunks, question, 6);
        List<PassageFinder.Passage> shown = ranked.size() > 4 ? ranked.subList(0, 4) : ranked;
        String warning = null;
        if (external(cfg) && cfg.configured()) {
            List<PassageFinder.Passage> context = ranked.isEmpty() ? chunks.subList(0, Math.min(6, chunks.size())) : ranked;
            try {
                String answer = Texts.trim(call(cfg, QA_SYSTEM_PROMPT, buildQaPrompt(doc, question, context), 700));
                if (answer.isEmpty()) {
                    throw new ProviderException("A resposta veio vazia.");
                }
                return new QaAnswer(answer, cfg.provider(), cfg.model(), List.copyOf(context.size() > 6 ? context.subList(0, 6) : context), null);
            } catch (ProviderException e) {
                warning = cfg.label() + " falhou: " + e.getMessage() + " Resposta feita no computador.";
            }
        }
        if (shown.isEmpty()) {
            String fallback = summary == null || summary.isBlank()
                    ? "Não encontrei trechos que respondam a essa pergunta. Tente outras palavras."
                    : "Não encontrei um trecho específico sobre isso. Em resumo, o documento trata de: " + summary;
            List<PassageFinder.Passage> intro = chunks.subList(0, Math.min(2, chunks.size()));
            return new QaAnswer(fallback, "local", "", List.copyOf(intro), warning);
        }
        return new QaAnswer(PassageFinder.bestSentences(shown.get(0), question), "local", "", List.copyOf(shown), warning);
    }
}
