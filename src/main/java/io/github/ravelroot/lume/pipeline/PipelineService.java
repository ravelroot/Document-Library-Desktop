package io.github.ravelroot.lume.pipeline;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.ai.AiService;
import io.github.ravelroot.lume.ai.Description;
import io.github.ravelroot.lume.ai.DocumentFacts;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.ProcessingRejected;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.docx.DocxExtractor;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.library.SearchService;
import io.github.ravelroot.lume.processing.Cover;
import io.github.ravelroot.lume.processing.CoverRequest;
import io.github.ravelroot.lume.processing.DocumentProcessor;
import io.github.ravelroot.lume.processing.ExtractionResult;
import io.github.ravelroot.lume.processing.HtmlSanitizer;
import io.github.ravelroot.lume.processing.OutlineItem;
import io.github.ravelroot.lume.processing.Palette;
import io.github.ravelroot.lume.settings.SettingsService;

/**
 * Pipeline de processamento, dentro do próprio aplicativo:
 * importado → extract (texto, sumário, capa e HTML de leitura) → describe (descrição e etiquetas por IA) → pronto.
 *
 * <p>Cada etapa vira uma linha em jobs. Falhas passageiras (o leitor de PDF travou, a rede caiu) tentam de novo com
 * espera crescente (5 s, 15 s, 45 s); arquivos corrompidos ou protegidos por senha falham na hora, com a mensagem
 * para a pessoa. Ao abrir o aplicativo, o que ficou pela metade volta para a fila.</p>
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    public static final int MAX_ATTEMPTS = 3;
    static final int MAX_TEXT_CHARS = 2_000_000;
    static final int COVER_WIDTH = 480;
    private static final List<String> STAGES = List.of("extract", "describe");
    private static final Map<String, Integer> CONCURRENCY = Map.of("extract", 1, "describe", 2);
    private static final Map<String, List<String>> CLAIMABLE = Map.of("extract", List.of("queued", "extracting"), "describe", List.of("describing"));
    private static final Map<String, String> RUNNING = Map.of("extract", "extracting", "describe", "describing");
    public static final Map<String, String> STAGE_LABELS = Map.of("extract", "Leitura do arquivo", "describe", "Descrição por IA");
    public static final Map<String, String> JOB_LABELS =
            Map.of("running", "Em andamento", "succeeded", "Concluída", "retrying", "Nova tentativa", "failed", "Falhou");
    private static final Pattern BAD_TITLES =
            Pattern.compile("^(untitled|sem t[ií]tulo|documento\\d*|microsoft word\\s*-.*|.*\\.(docx?|pdf|tmp))$", Pattern.CASE_INSENSITIVE);

    private final Database db;
    private final EventBus events;
    private final DocumentStore store;
    private final LibraryService library;
    private final SearchService search;
    private final SettingsService settings;
    private final AiService ai;
    private final DocumentProcessor processor;
    private final DocxExtractor docx;
    private final Long fixedRetryDelayMs;

    private final Map<String, Deque<String>> waiting = new HashMap<>();
    private final Map<String, Set<String>> running = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> delayed = new HashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon("lume-pipeline"));
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(daemon("lume-agenda"));
    private volatile boolean stopped;

    public PipelineService(Database db, EventBus events, DocumentStore store, LibraryService library, SearchService search,
                           SettingsService settings, AiService ai, DocumentProcessor processor, DocxExtractor docx,
                           LumeProperties properties) {
        this.db = db;
        this.events = events;
        this.store = store;
        this.library = library;
        this.search = search;
        this.settings = settings;
        this.ai = ai;
        this.processor = processor;
        this.docx = docx;
        this.fixedRetryDelayMs = properties.retryDelayMs();
        for (String stage : STAGES) {
            waiting.put(stage, new ArrayDeque<>());
            running.put(stage, new HashSet<>());
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Espera antes da nova tentativa: 5 s, 15 s, 45 s... até 5 minutos. */
    public static long backoff(int attempt) {
        return (long) Math.min(300, 5 * Math.pow(3, Math.max(0, attempt - 1))) * 1000;
    }

    static String goodMetadata(String value, int limit) {
        String text = Texts.singleLine(value);
        if (text.length() < 3 || BAD_TITLES.matcher(text).matches()) {
            return "";
        }
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /* ---------- Fila ---------- */

    public void enqueue(String stage, String id) {
        enqueue(stage, id, 0);
    }

    public synchronized void enqueue(String stage, String id, long delayMs) {
        if (stopped) {
            return;
        }
        if (delayMs > 0) {
            ScheduledFuture<?> previous = delayed.remove(id);
            if (previous != null) {
                previous.cancel(false);
            }
            delayed.put(id, timers.schedule(() -> {
                synchronized (this) {
                    delayed.remove(id);
                }
                enqueue(stage, id, 0);
            }, delayMs, TimeUnit.MILLISECONDS));
            return;
        }
        if (!waiting.get(stage).contains(id) && !running.get(stage).contains(id)) {
            waiting.get(stage).add(id);
        }
        pump();
    }

    private synchronized void pump() {
        for (String stage : STAGES) {
            while (!stopped && running.get(stage).size() < CONCURRENCY.get(stage) && !waiting.get(stage).isEmpty()) {
                String id = waiting.get(stage).poll();
                running.get(stage).add(id);
                workers.submit(() -> {
                    try {
                        handle(stage, id);
                    } catch (RuntimeException | Error e) {
                        log.error("[pipeline] {} {}", stage, id, e);
                    } finally {
                        synchronized (this) {
                            running.get(stage).remove(id);
                        }
                        events.emit("system.changed");
                        pump();
                    }
                });
            }
        }
    }

    /** Marca o documento como em processamento. Pedido repetido ou atrasado não faz nada. */
    private Row claim(String id, String stage) {
        return db.transaction(() -> {
            Row d = store.row(id);
            if (d == null || !CLAIMABLE.get(stage).contains(d.str("status"))) {
                return null;
            }
            db.update("UPDATE documents SET status = ?, attempts = attempts + 1, status_detail = '', updated_at = ? WHERE id = ?",
                    RUNNING.get(stage), Times.now(), id);
            return store.row(id);
        });
    }

    /** Registro de uma etapa na tabela jobs. */
    private final class Job {
        private final long id;
        private final long started = System.nanoTime();

        Job(Row doc, String stage) {
            this.id = db.insert("INSERT INTO jobs (document_id, stage, status, attempt, started_at) VALUES (?, ?, 'running', ?, ?)",
                    doc.id(), stage, doc.integer("attempts", 1), Times.now());
        }

        void finish(String status, String error, Map<String, ?> details) {
            String message = error == null ? "" : error;
            db.update("UPDATE jobs SET status = ?, error = ?, details = ?, finished_at = ?, duration_ms = ? WHERE id = ?",
                    status, message.length() > 2000 ? message.substring(0, 2000) : message, Json.write(details), Times.now(),
                    (System.nanoTime() - started) / 1_000_000, id);
        }
    }

    private void fail(String id, String message) {
        db.update("UPDATE documents SET status = 'failed', status_detail = ?, updated_at = ? WHERE id = ?",
                message.length() > 1000 ? message.substring(0, 1000) : message, Times.now(), id);
        store.emitUpdated(id);
    }

    private void handle(String stage, String id) {
        Row doc = claim(id, stage);
        if (doc == null) {
            return;
        }
        store.emitUpdated(id);
        if (stage.equals("extract")) {
            runExtract(doc);
        } else {
            runDescribe(doc);
        }
    }

    /* ---------- Leitura do arquivo ---------- */

    private Cover typographicCover(String title, String author, String label) throws Exception {
        String name = title == null || title.isEmpty() ? "Documento sem título" : title;
        Palette.Tone tone = Palette.toneFor(name);
        Cover cover = processor.renderCover(new CoverRequest(name, author == null ? "" : author, label, tone.hex(), tone.ink(), COVER_WIDTH));
        return cover.withStyle(tone.hex(), true);
    }

    private void runExtract(Row doc) {
        Job job = new Job(doc, "extract");
        DocumentStore.Files files = store.files(doc);
        ExtractionResult result;
        try {
            if ("pdf".equals(doc.str("kind"))) {
                result = processor.extractPdf(files.file(), MAX_TEXT_CHARS, COVER_WIDTH);
                if (result.cover() == null) {
                    String title = firstNonEmpty(goodMetadata(result.title(), 300), doc.str("title"));
                    String author = firstNonEmpty(goodMetadata(result.author(), 200), doc.text("author"));
                    result.cover(typographicCover(title, author, "PDF"));
                }
            } else {
                result = docx.extract(files.file(), files.folder(), MAX_TEXT_CHARS);
                boolean locked = doc.bool("metadata_locked");
                String title = locked ? doc.str("title") : firstNonEmpty(goodMetadata(result.title(), 300), doc.str("title"));
                String author = locked ? doc.text("author") : firstNonEmpty(goodMetadata(result.author(), 200), doc.text("author"));
                result.cover(typographicCover(title, author, "DOCX"));
            }
            applyExtraction(doc, result, files);
        } catch (ProcessingRejected e) {
            job.finish("failed", e.getMessage(), Map.of());
            fail(doc.id(), e.getMessage());
            return;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int attempts = doc.integer("attempts", 1);
            if (attempts >= MAX_ATTEMPTS) {
                job.finish("failed", message, Map.of());
                fail(doc.id(), "Não foi possível ler o arquivo depois de várias tentativas. Use “Ler o arquivo de novo” mais tarde.");
                return;
            }
            long delay = fixedRetryDelayMs != null ? fixedRetryDelayMs : backoff(attempts);
            String seconds = BigDecimal.valueOf(delay).movePointLeft(3).stripTrailingZeros().toPlainString();
            job.finish("retrying", message, Map.of("retry_in_s", new BigDecimal(seconds)));
            db.update("UPDATE documents SET status = 'queued', status_detail = ?, updated_at = ? WHERE id = ?",
                    "Nova tentativa em " + seconds + " s", Times.now(), doc.id());
            store.emitUpdated(doc.id());
            enqueue("extract", doc.id(), delay);
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pages", result.pageCount());
        details.put("words", result.wordCount());
        details.put("processor_ms", result.elapsedMs());
        details.put("warnings", result.warnings());
        job.finish("succeeded", "", details);
        store.emitUpdated(doc.id());
        enqueue("describe", doc.id());
    }

    private void applyExtraction(Row doc, ExtractionResult result, DocumentStore.Files files) throws IOException {
        if (!Files.isDirectory(files.folder())) {
            return; // o documento foi excluído enquanto era lido
        }
        boolean hasHtml = false;
        if (result.html() != null) {
            Files.writeString(files.reader(), HtmlSanitizer.readerHtml(result.html(), doc.id()), StandardCharsets.UTF_8);
            hasHtml = true;
        }
        Cover cover = result.cover();
        if (cover != null && cover.bytes() != null) {
            Files.write(files.cover(), cover.bytes());
        }
        Map<String, Object> metadata = result.metadata();
        List<Map<String, Object>> outline = result.outline().stream().limit(500).map(OutlineItem::toMap).toList();
        final boolean readerHtml = hasHtml;
        db.transaction(() -> {
            Row current = store.row(doc.id());
            if (current == null) {
                return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("page_count", result.pageCount() == null || result.pageCount() == 0 ? null : result.pageCount());
            fields.put("word_count", result.wordCount());
            String language = result.language() == null ? "" : result.language();
            fields.put("language", language.length() > 16 ? language.substring(0, 16) : language);
            fields.put("outline", Json.write(outline));
            fields.put("metadata", Json.write(metadata));
            fields.put("has_reader_html", readerHtml ? 1 : 0);
            fields.put("status", "describing");
            fields.put("attempts", 0);
            fields.put("status_detail", "");
            fields.put("updated_at", Times.now());
            if (cover != null && cover.bytes() != null) {
                String color = cover.color() == null ? "" : cover.color();
                fields.put("cover_width", cover.width() > 0 ? cover.width() : null);
                fields.put("cover_height", cover.height() > 0 ? cover.height() : null);
                fields.put("cover_color", color.length() > 7 ? color.substring(0, 7) : color);
                fields.put("cover_generated", cover.generated() ? 1 : 0);
                fields.put("cover_version", current.integer("cover_version", 0) + 1);
            }
            if (!current.bool("metadata_locked")) {
                String title = goodMetadata(result.title(), 300);
                String author = goodMetadata(result.author(), 200);
                if (!title.isEmpty()) {
                    fields.put("title", title);
                    fields.put("title_sort", Texts.fold(title));
                }
                if (!author.isEmpty()) {
                    fields.put("author", author);
                    fields.put("author_sort", Texts.fold(author));
                }
            }
            List<Object> args = new ArrayList<>(fields.values());
            args.add(doc.id());
            db.update("UPDATE documents SET " + String.join(", ", fields.keySet().stream().map(k -> k + " = ?").toList()) + " WHERE id = ?",
                    args.toArray());
            db.update("INSERT INTO document_content (document_id, text, text_truncated, updated_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (document_id) DO UPDATE SET text = excluded.text, text_truncated = excluded.text_truncated, "
                    + "updated_at = excluded.updated_at", doc.id(), result.text(), result.textTruncated() ? 1 : 0, Times.now());
            search.refreshSearch(doc.id());
        });
    }

    /* ---------- Descrição ---------- */

    private void runDescribe(Row doc) {
        Job job = new Job(doc, "describe");
        Row content = db.one("SELECT text FROM document_content WHERE document_id = ?", doc.id());
        String text = content == null ? "" : content.text("text");
        List<String> outline = Json.parseList(doc.str("outline")).stream()
                .map(item -> item instanceof Map<?, ?> m && m.get("title") != null ? String.valueOf(m.get("title")) : "")
                .toList();
        DocumentFacts info = new DocumentFacts(doc.str("title"), doc.text("author"), doc.str("kind"), doc.integer("page_count"), outline);
        Description result;
        try {
            result = ai.describe(info, text, settings.aiConfig());
        } catch (RuntimeException e) {
            result = ai.describeLocal(info, text, List.of("Falha inesperada: " + e.getMessage()));
        }
        final Description description = result;
        db.transaction(() -> {
            Row current = store.row(doc.id());
            if (current == null) {
                return;
            }
            String stamp = Times.now();
            String language = !current.text("language").isEmpty() ? current.text("language")
                    : description.language() == null ? "" : description.language();
            db.update("UPDATE documents SET summary = ?, summary_source = ?, summary_model = ?, summary_at = ?, language = ?, "
                    + "status = 'ready', status_detail = '', attempts = 0, processed_at = coalesce(processed_at, ?), updated_at = ? WHERE id = ?",
                    description.summary(), description.source(), description.model() == null ? "" : description.model(), stamp, language,
                    stamp, stamp, doc.id());
            if (!description.tags().isEmpty()) {
                library.setTags(doc.id(), description.tags(), "ia");
            }
            search.refreshSearch(doc.id());
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", description.source());
        details.put("model", description.model());
        details.put("fallback", !description.warnings().isEmpty());
        job.finish("succeeded", String.join("; ", description.warnings()), details);
        store.emitUpdated(doc.id());
        // Documento pronto: avisa as integrações (webhooks) e a janela.
        events.emit("document.ready", Map.of("id", doc.id()));
    }

    /* ---------- Controle ---------- */

    /** Põe de novo na fila (botões "Ler o arquivo de novo" e "Gerar de novo"). */
    public void requeue(String id, String stage) {
        String status = stage.equals("extract") ? "queued" : "describing";
        db.update("UPDATE documents SET status = ?, status_detail = '', attempts = 0, updated_at = ? WHERE id = ?", status, Times.now(), id);
        store.emitUpdated(id);
        enqueue(stage, id);
    }

    /** Ao abrir: etapas que estavam rodando quando o app fechou viram falha e os documentos voltam para a fila. */
    public void recover() {
        db.update("UPDATE jobs SET status = 'failed', error = 'O aplicativo foi fechado durante a etapa.', finished_at = ? WHERE status = 'running'",
                Times.now());
        for (Row d : db.query("SELECT id, status FROM documents WHERE status IN ('queued', 'extracting', 'describing') ORDER BY created_at")) {
            if ("describing".equals(d.str("status"))) {
                enqueue("describe", d.id());
            } else {
                db.update("UPDATE documents SET status = 'queued' WHERE id = ?", d.id());
                enqueue("extract", d.id());
            }
        }
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String stage : STAGES) {
            out.put(stage, Map.of("waiting", waiting.get(stage).size(), "running", running.get(stage).size()));
        }
        out.put("delayed", delayed.size());
        return out;
    }

    public synchronized boolean idle() {
        return STAGES.stream().allMatch(stage -> waiting.get(stage).isEmpty() && running.get(stage).isEmpty()) && delayed.isEmpty();
    }

    @PreDestroy
    public void stop() {
        synchronized (this) {
            stopped = true;
            delayed.values().forEach(timer -> timer.cancel(false));
            delayed.clear();
            waiting.values().forEach(Deque::clear);
        }
        timers.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Usado pelos testes para esvaziar a fila entre um caso e outro. */
    public synchronized void reset() {
        delayed.values().forEach(timer -> timer.cancel(false));
        delayed.clear();
        waiting.values().forEach(Deque::clear);
    }
}
