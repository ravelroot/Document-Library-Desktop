package io.github.ravelroot.lume.integrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.settings.SettingsService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Pastas monitoradas: o Lume olha de tempos em tempos as pastas escolhidas (Downloads, uma pasta do OneDrive, do
 * Google Drive, do Dropbox...) e importa sozinho os PDFs e DOCX novos. A deduplicação por SHA-256 da importação evita
 * cópias repetidas; caminhos que já estão na biblioteca nem são lidos de novo.
 */
@Service
public class WatchFolderService {

    private static final Logger log = LoggerFactory.getLogger(WatchFolderService.class);

    static final String KEY = "watch_folders";
    public static final int MAX_FOLDERS = 10;
    /** Arquivo mexido há menos tempo que isso pode estar sendo baixado ou copiado: fica para a próxima varredura. */
    private static final long SETTLE_MS = 5_000;

    private final SettingsService settings;
    private final LibraryService library;
    private final Database db;
    private final EventBus events;
    private final DocumentStore store;
    private final int intervalS;
    private final Set<String> skipped = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService timer;

    public WatchFolderService(SettingsService settings, LibraryService library, Database db, EventBus events, DocumentStore store,
                              @Value("${lume.watch-interval-s:60}") int intervalS) {
        this.settings = settings;
        this.library = library;
        this.db = db;
        this.events = events;
        this.store = store;
        this.intervalS = intervalS;
    }

    public int intervalS() {
        return intervalS > 0 ? intervalS : 60;
    }

    @PostConstruct
    void start() {
        if (intervalS <= 0) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lume-pastas-monitoradas");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                scan(false);
            } catch (RuntimeException e) {
                log.warn("[pastas] varredura falhou: {}", e.getMessage());
            }
        }, Math.min(20, intervalS), intervalS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) {
            timer.shutdownNow();
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

    private synchronized void save(List<Map<String, Object>> folders) {
        settings.set(KEY, folders);
    }

    /** Lista para a tela Integrações (com o nome da coleção e se a pasta ainda existe). */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> folder : stored()) {
            Map<String, Object> item = new LinkedHashMap<>();
            String collection = Values.truthy(folder.get("collection")) ? Values.text(folder.get("collection")) : null;
            Row c = collection == null ? null : db.one("SELECT name FROM collections WHERE id = ?", collection);
            Path path = toPath(Values.text(folder.get("path")));
            item.put("id", folder.get("id"));
            item.put("path", folder.get("path"));
            item.put("collection", c == null ? null : collection);
            item.put("collection_name", c == null ? null : c.str("name"));
            item.put("enabled", !Boolean.FALSE.equals(folder.get("enabled")));
            item.put("exists", path != null && Files.isDirectory(path));
            item.put("last_scan_at", folder.get("last_scan_at"));
            item.put("imported_total", (long) Values.number(folder.get("imported_total")));
            out.add(item);
        }
        return out;
    }

    private static Path toPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String collectionId(Object value) {
        return Ids.isUuid(value) ? String.valueOf(value) : null;
    }

    public synchronized void add(String rawPath, Object collection) {
        Path path = toPath(rawPath);
        if (path == null || !Files.isDirectory(path)) {
            throw ApiException.field("path", "Essa pasta não existe.");
        }
        String collectionId = collectionId(collection);
        if (collectionId != null && db.one("SELECT id FROM collections WHERE id = ?", collectionId) == null) {
            throw ApiException.notFound("Coleção não encontrada.");
        }
        Path data = store.dataDir();
        if (data != null && (path.startsWith(data) || data.startsWith(path))) {
            throw ApiException.field("path", "Escolha uma pasta fora da pasta de dados do Lume.");
        }
        List<Map<String, Object>> folders = stored();
        for (Map<String, Object> folder : folders) {
            if (path.toString().equalsIgnoreCase(Values.text(folder.get("path")))) {
                throw ApiException.field("path", "Essa pasta já está sendo monitorada.");
            }
        }
        if (folders.size() >= MAX_FOLDERS) {
            throw new ApiException("Monitore no máximo " + MAX_FOLDERS + " pastas.");
        }
        Map<String, Object> folder = new LinkedHashMap<>();
        folder.put("id", Ids.uuid());
        folder.put("path", path.toString());
        folder.put("collection", collectionId);
        folder.put("enabled", true);
        folder.put("last_scan_at", null);
        folder.put("imported_total", 0);
        folder.put("created_at", Times.now());
        folders.add(folder);
        save(folders);
    }

    public synchronized void update(String id, Map<String, Object> body) {
        List<Map<String, Object>> folders = stored();
        Map<String, Object> folder = folders.stream().filter(f -> id.equals(f.get("id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("Pasta não encontrada."));
        if (body.containsKey("enabled")) {
            folder.put("enabled", Values.truthy(body.get("enabled")));
        }
        if (body.containsKey("collection")) {
            String collectionId = collectionId(body.get("collection"));
            if (collectionId != null && db.one("SELECT id FROM collections WHERE id = ?", collectionId) == null) {
                throw ApiException.notFound("Coleção não encontrada.");
            }
            folder.put("collection", collectionId);
        }
        save(folders);
    }

    public synchronized void remove(String id) {
        List<Map<String, Object>> folders = stored();
        if (!folders.removeIf(f -> id.equals(f.get("id")))) {
            throw ApiException.notFound("Pasta não encontrada.");
        }
        save(folders);
    }

    /* ---------- Varredura ---------- */

    /** Resultado de uma varredura. */
    public record ScanResult(int imported, int duplicates, int failed) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("imported", imported);
            out.put("duplicates", duplicates);
            out.put("failed", failed);
            return out;
        }
    }

    private static String signature(Path file) {
        try {
            return file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return file + "|?";
        }
    }

    /**
     * Varre as pastas ativas e importa o que é novo.
     *
     * @param manual true no botão "Verificar agora" (não espera o arquivo "assentar")
     */
    public synchronized ScanResult scan(boolean manual) {
        List<Map<String, Object>> folders = stored();
        if (folders.isEmpty()) {
            return new ScanResult(0, 0, 0);
        }
        Set<String> known = new HashSet<>();
        for (Row r : db.query("SELECT source_path FROM documents WHERE source_path != ''")) {
            known.add(r.str("source_path"));
        }
        int imported = 0;
        int duplicates = 0;
        int failed = 0;
        long now = System.currentTimeMillis();
        for (Map<String, Object> folder : folders) {
            if (Boolean.FALSE.equals(folder.get("enabled"))) {
                continue;
            }
            Path path = toPath(Values.text(folder.get("path")));
            if (path == null || !Files.isDirectory(path)) {
                continue;
            }
            String collection = collectionId(folder.get("collection"));
            if (collection != null && db.one("SELECT id FROM collections WHERE id = ?", collection) == null) {
                collection = null;
            }
            int here = 0;
            for (Path file : library.scanFolder(path, 5000)) {
                String absolute = file.toAbsolutePath().toString();
                if (known.contains(absolute)) {
                    continue;
                }
                String sig = signature(file);
                if (skipped.contains(sig)) {
                    continue;
                }
                try {
                    if (!manual && now - Files.getLastModifiedTime(file).toMillis() < SETTLE_MS) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                try {
                    LibraryService.ImportResult result = library.importFile(file, collection);
                    known.add(absolute);
                    if (result.duplicate()) {
                        duplicates++;
                        skipped.add(sig);
                    } else {
                        imported++;
                        here++;
                    }
                } catch (ApiException e) {
                    failed++;
                    skipped.add(sig);
                    log.info("[pastas] {} não foi importado: {}", file.getFileName(), e.getMessage());
                } catch (RuntimeException e) {
                    failed++;
                    skipped.add(sig);
                    log.warn("[pastas] {} falhou: {}", file.getFileName(), e.getMessage());
                }
            }
            folder.put("last_scan_at", Times.now());
            folder.put("imported_total", (long) Values.number(folder.get("imported_total")) + here);
            if (here > 0) {
                events.emit("watch.imported", Map.of("folder", path.toString(), "count", here));
            }
        }
        // Grava só o progresso (a lista pode ter mudado durante a varredura).
        List<Map<String, Object>> current = stored();
        for (Map<String, Object> folder : current) {
            folders.stream().filter(f -> f.get("id") != null && f.get("id").equals(folder.get("id"))).findFirst().ifPresent(f -> {
                folder.put("last_scan_at", f.get("last_scan_at"));
                folder.put("imported_total", f.get("imported_total"));
            });
        }
        save(current);
        return new ScanResult(imported, duplicates, failed);
    }
}
