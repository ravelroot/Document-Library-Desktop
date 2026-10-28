package io.github.ravelroot.lume.integrations;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.ai.HttpTransport;
import io.github.ravelroot.lume.ai.LocalSummarizer;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.settings.SecretVault;
import io.github.ravelroot.lume.settings.SettingsService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Webhooks: avisa outros serviços (n8n, Make, Zapier, Slack, Discord, um script próprio...) quando algo acontece na
 * biblioteca. O corpo é JSON; com um segredo, vai junto a assinatura HMAC-SHA256 em {@code X-Lume-Signature}.
 * Slack e Discord recebem uma frase pronta no formato que cada um espera.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    static final String KEY = "webhooks";
    public static final int MAX_WEBHOOKS = 10;
    public static final List<String> FORMATS = List.of("json", "slack", "discord");
    public static final Map<String, String> EVENTS = eventLabels();
    private static final Pattern HTTP_URL = Pattern.compile("^https?://[^\\s/$.?#].\\S*$", Pattern.CASE_INSENSITIVE);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SettingsService settings;
    private final Database db;
    private final EventBus events;
    private final SecretVault vault;
    private volatile HttpTransport transport;
    private ExecutorService sender;
    private Runnable unsubscribe;

    public WebhookService(SettingsService settings, Database db, EventBus events, SecretVault vault, HttpTransport transport) {
        this.settings = settings;
        this.db = db;
        this.events = events;
        this.vault = vault;
        this.transport = transport;
    }

    private static Map<String, String> eventLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("document.ready", "Documento pronto");
        labels.put("document.created", "Documento importado");
        labels.put("document.deleted", "Documento excluído");
        labels.put("annotation.created", "Nova anotação");
        labels.put("shelf.finished", "Leitura concluída");
        return labels;
    }

    public static List<Map<String, Object>> eventList() {
        List<Map<String, Object>> out = new ArrayList<>();
        EVENTS.forEach((id, label) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("label", label);
            out.add(item);
        });
        return out;
    }

    /** Troca o cliente HTTP (os testes usam um falso). Devolve o anterior. */
    public HttpTransport useTransport(HttpTransport replacement) {
        HttpTransport previous = transport;
        transport = replacement;
        return previous;
    }

    @PostConstruct
    void start() {
        sender = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lume-webhooks");
            thread.setDaemon(true);
            return thread;
        });
        unsubscribe = events.subscribe(this::onEvent);
    }

    @PreDestroy
    void stop() {
        if (unsubscribe != null) {
            unsubscribe.run();
        }
        if (sender != null) {
            sender.shutdownNow();
        }
    }

    /* ---------- Configuração ---------- */

    @SuppressWarnings("unchecked")
    synchronized List<Map<String, Object>> stored() {
        Object value = settings.get(KEY, List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
        }
        return out;
    }

    private synchronized void save(List<Map<String, Object>> hooks) {
        settings.set(KEY, hooks);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> hook : stored()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", hook.get("id"));
            item.put("url", hook.get("url"));
            item.put("format", hook.get("format"));
            item.put("events", hook.get("events"));
            item.put("enabled", !Boolean.FALSE.equals(hook.get("enabled")));
            item.put("has_secret", Values.truthy(hook.get("secret")));
            item.put("last_status", hook.get("last_status"));
            item.put("last_error", hook.get("last_error"));
            item.put("last_at", hook.get("last_at"));
            out.add(item);
        }
        return out;
    }

    static String detectFormat(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("hooks.slack.com")) {
            return "slack";
        }
        if (lower.contains("discord.com/api/webhooks") || lower.contains("discordapp.com/api/webhooks")) {
            return "discord";
        }
        return "json";
    }

    private static String validUrl(Object raw) {
        String url = Texts.trim(Values.text(raw));
        if (url.length() > 1000 || !HTTP_URL.matcher(url).matches()) {
            throw ApiException.field("url", "Informe um endereço que comece com http:// ou https://.");
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.field("url", "Endereço inválido.");
        }
        return url;
    }

    private static List<String> validEvents(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String event = Values.text(item);
                if (EVENTS.containsKey(event) && !out.contains(event)) {
                    out.add(event);
                }
            }
        }
        if (out.isEmpty()) {
            throw ApiException.field("events", "Escolha pelo menos um evento.");
        }
        return out;
    }

    private static String validFormat(Object raw, String url) {
        String format = Values.truthy(raw) ? Values.text(raw) : detectFormat(url);
        if (!FORMATS.contains(format)) {
            throw ApiException.field("format", "Formato inválido (json, slack ou discord).");
        }
        return format;
    }

    public synchronized void add(Map<String, Object> body) {
        String url = validUrl(body.get("url"));
        List<Map<String, Object>> hooks = stored();
        if (hooks.size() >= MAX_WEBHOOKS) {
            throw new ApiException("Cadastre no máximo " + MAX_WEBHOOKS + " webhooks.");
        }
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("id", Ids.uuid());
        hook.put("url", url);
        hook.put("format", validFormat(body.get("format"), url));
        hook.put("events", body.containsKey("events") ? validEvents(body.get("events")) : List.of("document.ready"));
        hook.put("enabled", true);
        String secret = Texts.trim(Values.text(body.get("secret")));
        hook.put("secret", secret.isEmpty() ? "" : vault.seal(secret));
        hook.put("last_status", null);
        hook.put("last_error", null);
        hook.put("last_at", null);
        hook.put("created_at", Times.now());
        hooks.add(hook);
        save(hooks);
    }

    public synchronized void update(String id, Map<String, Object> body) {
        List<Map<String, Object>> hooks = stored();
        Map<String, Object> hook = hooks.stream().filter(h -> id.equals(h.get("id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("Webhook não encontrado."));
        if (body.containsKey("url")) {
            hook.put("url", validUrl(body.get("url")));
        }
        if (body.containsKey("format")) {
            hook.put("format", validFormat(body.get("format"), Values.text(hook.get("url"))));
        }
        if (body.containsKey("events")) {
            hook.put("events", validEvents(body.get("events")));
        }
        if (body.containsKey("enabled")) {
            hook.put("enabled", Values.truthy(body.get("enabled")));
        }
        if (body.containsKey("secret")) {
            String secret = Texts.trim(Values.text(body.get("secret")));
            hook.put("secret", secret.isEmpty() ? "" : vault.seal(secret));
        }
        save(hooks);
    }

    public synchronized void remove(String id) {
        List<Map<String, Object>> hooks = stored();
        if (!hooks.removeIf(h -> id.equals(h.get("id")))) {
            throw ApiException.notFound("Webhook não encontrado.");
        }
        save(hooks);
    }

    private synchronized void record(String id, Integer status, String error) {
        List<Map<String, Object>> hooks = stored();
        for (Map<String, Object> hook : hooks) {
            if (id.equals(hook.get("id"))) {
                hook.put("last_status", status);
                hook.put("last_error", error);
                hook.put("last_at", Times.now());
            }
        }
        save(hooks);
    }

    /* ---------- Entrega ---------- */

    private void onEvent(Map<String, Object> event) {
        String type = Values.text(event.get("type"));
        if (!EVENTS.containsKey(type) || sender == null) {
            return;
        }
        List<Map<String, Object>> targets = stored().stream()
                .filter(h -> !Boolean.FALSE.equals(h.get("enabled")))
                .filter(h -> h.get("events") instanceof List<?> list && list.contains(type))
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // Os dados do documento são lidos agora (no "excluído" ele já não existe).
        Map<String, Object> payload = payload(type, event);
        for (Map<String, Object> hook : targets) {
            sender.submit(() -> deliver(hook, type, payload));
        }
    }

    private Map<String, Object> payload(String type, Map<String, Object> event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", type);
        out.put("sent_at", Times.now());
        out.put("app", "Lume");
        String documentId = Values.text("annotation.created".equals(type) ? event.get("document_id") : event.get("id"));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        if (!"document.deleted".equals(type)) {
            Row d = db.one("SELECT * FROM documents WHERE id = ?", documentId);
            if (d != null) {
                document.put("title", d.str("title"));
                document.put("author", d.text("author"));
                document.put("kind", d.str("kind"));
                document.put("page_count", d.integer("page_count"));
                document.put("summary", d.text("summary"));
                document.put("tags", db.query("SELECT t.name FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = ? "
                        + "ORDER BY t.name", documentId).stream().map(r -> r.str("name")).toList());
            }
        }
        out.put("document", document);
        if ("annotation.created".equals(type)) {
            Row a = db.one("SELECT * FROM annotations WHERE id = ?", Values.text(event.get("id")));
            if (a != null) {
                Map<String, Object> annotation = new LinkedHashMap<>();
                annotation.put("id", a.id());
                annotation.put("kind", a.str("kind"));
                annotation.put("page", a.integer("page"));
                annotation.put("quote", a.text("quote"));
                annotation.put("note", a.text("note"));
                annotation.put("color", a.str("color"));
                out.put("annotation", annotation);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String message(String type, Map<String, Object> payload) {
        Map<String, Object> d = payload.get("document") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String title = Values.truthy(d.get("title")) ? "“" + d.get("title") + "”" : "um documento";
        String summary = LocalSummarizer.shorten(Values.text(d.get("summary")), 180);
        return switch (type) {
            case "document.ready" -> "📄 Pronto no Lume: " + title + (summary.isEmpty() ? "" : " — " + summary);
            case "document.created" -> "📥 Importado no Lume: " + title;
            case "document.deleted" -> "🗑️ Um documento foi excluído do Lume.";
            case "annotation.created" -> {
                Map<String, Object> a = payload.get("annotation") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                String quote = LocalSummarizer.shorten(Values.text(a.get("quote")), 160);
                String note = LocalSummarizer.shorten(Values.text(a.get("note")), 160);
                yield "🖍️ Nova anotação em " + title + (quote.isEmpty() ? "" : ": “" + quote + "”") + (note.isEmpty() ? "" : " — " + note);
            }
            case "shelf.finished" -> "✅ Leitura concluída: " + title;
            default -> "🔔 Teste do Lume: o webhook está funcionando.";
        };
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Envia e registra o resultado. Devolve {ok, status, message}. */
    Map<String, Object> deliver(Map<String, Object> hook, String type, Map<String, Object> payload) {
        String id = Values.text(hook.get("id"));
        String format = Values.text(hook.get("format"));
        String body = switch (format) {
            case "slack" -> Json.write(Map.of("text", message(type, payload)));
            case "discord" -> Json.write(Map.of("content", message(type, payload)));
            default -> Json.write(payload);
        };
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("user-agent", "Lume-Desktop");
        headers.put("x-lume-event", type);
        String secret = vault.open(Values.text(hook.get("secret")));
        if (!secret.isEmpty()) {
            headers.put("x-lume-signature", sign(secret, body));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpTransport.Reply reply = transport.post(URI.create(Values.text(hook.get("url"))), headers, body, TIMEOUT);
            boolean ok = reply.status() >= 200 && reply.status() < 300;
            String error = ok ? null : "O serviço respondeu " + reply.status() + ": " + LocalSummarizer.shorten(Values.text(reply.body()), 200);
            record(id, reply.status(), error);
            out.put("ok", ok);
            out.put("status", reply.status());
            out.put("message", ok ? "Entregue (HTTP " + reply.status() + ")." : error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(id, null, "Envio interrompido.");
            out.put("ok", false);
            out.put("status", null);
            out.put("message", "Envio interrompido.");
        } catch (IOException | IllegalArgumentException e) {
            String reason = "Não foi possível entregar: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            record(id, null, reason);
            out.put("ok", false);
            out.put("status", null);
            out.put("message", reason);
            log.info("[webhook] {}", reason);
        }
        return out;
    }

    /** Botão "Testar": envio na hora, com um documento de exemplo. */
    public Map<String, Object> test(String id) {
        Map<String, Object> hook = stored().stream().filter(h -> id.equals(h.get("id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("Webhook não encontrado."));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "test");
        payload.put("sent_at", Times.now());
        payload.put("app", "Lume");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", "00000000-0000-0000-0000-000000000000");
        document.put("title", "Documento de teste");
        document.put("author", "Lume");
        document.put("kind", "pdf");
        document.put("page_count", 1);
        document.put("summary", "Mensagem de teste enviada pela tela Integrações do Lume.");
        document.put("tags", List.of("teste"));
        payload.put("document", document);
        return deliver(hook, "test", payload);
    }
}
