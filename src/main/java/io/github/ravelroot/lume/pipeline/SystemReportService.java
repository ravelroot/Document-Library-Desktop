package io.github.ravelroot.lume.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.AppInfo;
import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.processing.DocumentProcessor;
import io.github.ravelroot.lume.settings.SettingsService;

/** Tela Sistema: filas, documentos por estado, últimas etapas, falhas, espaço ocupado e versões. */
@Service
public class SystemReportService {

    private static final List<String> STATUSES = List.of("queued", "extracting", "describing", "ready", "failed");

    private final Database db;
    private final PipelineService pipeline;
    private final DocumentProcessor processor;
    private final SettingsService settings;
    private final AppInfo appInfo;
    private final LumeProperties properties;

    public SystemReportService(Database db, PipelineService pipeline, DocumentProcessor processor, SettingsService settings,
                               AppInfo appInfo, LumeProperties properties) {
        this.db = db;
        this.pipeline = pipeline;
        this.processor = processor;
        this.settings = settings;
        this.appInfo = appInfo;
        this.properties = properties;
    }

    private static Long round(Double value) {
        return value == null ? null : Math.round(value);
    }

    public Map<String, Object> report() {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        STATUSES.forEach(s -> byStatus.put(s, 0L));
        for (Row r : db.query("SELECT status, count(*) AS n FROM documents GROUP BY status")) {
            byStatus.put(r.str("status"), r.lng("n"));
        }
        Row storage = db.one("SELECT coalesce(sum(size_bytes), 0) AS bytes, count(*) AS count FROM documents");
        Row day = db.one("SELECT sum(status = 'succeeded') AS succeeded, sum(status = 'failed') AS failed, sum(status = 'retrying') AS retried, "
                + "avg(CASE WHEN stage = 'extract' AND status = 'succeeded' THEN duration_ms END) AS extract_ms, "
                + "avg(CASE WHEN stage = 'describe' AND status = 'succeeded' THEN duration_ms END) AS describe_ms "
                + "FROM jobs WHERE started_at >= ?", Times.ago(24L * 3600 * 1000));
        List<Map<String, Object>> jobs = db.query("SELECT j.*, d.title AS document_title FROM jobs j JOIN documents d ON d.id = j.document_id "
                + "ORDER BY j.started_at DESC, j.id DESC LIMIT 25").stream().map(j -> {
                    Map<String, Object> job = new LinkedHashMap<>();
                    job.put("id", j.lng("id"));
                    job.put("document_id", j.str("document_id"));
                    job.put("document_title", j.str("document_title"));
                    job.put("stage", j.str("stage"));
                    job.put("stage_label", PipelineService.STAGE_LABELS.get(j.str("stage")));
                    job.put("status", j.str("status"));
                    job.put("status_label", PipelineService.JOB_LABELS.get(j.str("status")));
                    job.put("attempt", j.integer("attempt"));
                    job.put("started_at", j.str("started_at"));
                    job.put("duration_ms", j.lng("duration_ms"));
                    job.put("error", j.str("error"));
                    job.put("details", Json.parseObject(j.str("details")));
                    return job;
                }).toList();
        List<Map<String, Object>> failures = db.query("SELECT id, title, status_detail, updated_at FROM documents WHERE status = 'failed' "
                + "ORDER BY updated_at DESC LIMIT 20").stream().map(d -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", d.id());
                    item.put("title", d.str("title"));
                    item.put("detail", d.str("status_detail"));
                    item.put("updated_at", d.str("updated_at"));
                    return item;
                }).toList();
        long dbBytes = 0;
        for (String suffix : List.of("", "-wal")) {
            try {
                dbBytes += Files.size(properties.dataDir().resolve("lume.db" + suffix));
            } catch (java.io.IOException ignored) {
                // o arquivo ainda não existe
            }
        }
        Map<String, Object> app = new LinkedHashMap<>(appInfo.toMap());
        app.put("data_dir", Path.of(String.valueOf(app.get("data_dir"))).toString());
        app.put("db_bytes", dbBytes);

        Map<String, Object> last24h = new LinkedHashMap<>();
        last24h.put("succeeded", day.lng("succeeded", 0));
        last24h.put("failed", day.lng("failed", 0));
        last24h.put("retried", day.lng("retried", 0));
        last24h.put("extract_ms", round(day.dbl("extract_ms")));
        last24h.put("describe_ms", round(day.dbl("describe_ms")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", app);
        out.put("processor", processor.health());
        out.put("ai", settings.aiPublic());
        out.put("queues", pipeline.stats());
        out.put("documents", byStatus);
        out.put("storage", Map.of("bytes", storage.lng("bytes", 0), "count", storage.lng("count", 0)));
        out.put("last_24h", last24h);
        out.put("jobs", jobs);
        out.put("failures", failures);
        return out;
    }
}
