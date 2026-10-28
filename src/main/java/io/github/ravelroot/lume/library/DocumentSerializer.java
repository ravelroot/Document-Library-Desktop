package io.github.ravelroot.lume.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;

/** Transforma as linhas de documents no JSON que a interface usa (com etiquetas, coleções e estado de leitura). */
@Component
public class DocumentSerializer {

    public static final Map<String, String> STATUS_LABELS = Map.of(
            "queued", "Na fila",
            "extracting", "Lendo o documento",
            "describing", "Escrevendo a descrição",
            "ready", "Pronto",
            "failed", "Falhou");

    private final Database db;

    public DocumentSerializer(Database db) {
        this.db = db;
    }

    static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public static String coverUrl(Row d) {
        return d.integer("cover_version", 0) > 0 ? "/api/v1/documents/" + d.id() + "/cover?v=" + d.integer("cover_version", 0) : null;
    }

    public static Map<String, Object> emptyReading() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shelf", "");
        out.put("page", 1);
        out.put("progress", 0);
        out.put("location", new LinkedHashMap<>());
        out.put("favorite", false);
        out.put("started_at", null);
        out.put("finished_at", null);
        out.put("last_opened_at", null);
        return out;
    }

    public static Map<String, Object> readingData(Row r) {
        if (r == null) {
            return emptyReading();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shelf", r.str("shelf"));
        out.put("page", r.integer("page"));
        out.put("progress", r.dbl("progress"));
        out.put("location", Json.parse(r.str("location"), new LinkedHashMap<>()));
        out.put("favorite", r.bool("favorite"));
        out.put("started_at", r.str("started_at"));
        out.put("finished_at", r.str("finished_at"));
        out.put("last_opened_at", r.str("last_opened_at"));
        return out;
    }

    private Map<String, Map<String, Object>> readingFor(List<String> ids) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Row r : db.query("SELECT * FROM reading_states WHERE document_id IN (" + placeholders(ids.size()) + ")", ids.toArray())) {
            map.put(r.str("document_id"), readingData(r));
        }
        return map;
    }

    private Map<String, List<Map<String, Object>>> tagsFor(List<String> ids) {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        ids.forEach(id -> map.put(id, new ArrayList<>()));
        if (ids.isEmpty()) {
            return map;
        }
        for (Row r : db.query("SELECT dt.document_id, t.name, t.slug, dt.source FROM document_tags dt JOIN tags t ON t.id = dt.tag_id "
                + "WHERE dt.document_id IN (" + placeholders(ids.size()) + ") ORDER BY t.name", ids.toArray())) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("name", r.str("name"));
            tag.put("slug", r.str("slug"));
            tag.put("source", r.str("source"));
            map.get(r.str("document_id")).add(tag);
        }
        return map;
    }

    private Map<String, List<String>> collectionsFor(List<String> ids) {
        Map<String, List<String>> map = new HashMap<>();
        ids.forEach(id -> map.put(id, new ArrayList<>()));
        if (ids.isEmpty()) {
            return map;
        }
        for (Row r : db.query("SELECT document_id, collection_id FROM collection_items WHERE document_id IN (" + placeholders(ids.size()) + ")",
                ids.toArray())) {
            map.get(r.str("document_id")).add(r.str("collection_id"));
        }
        return map;
    }

    public List<Map<String, Object>> serialize(List<Row> rows, boolean detail, Map<String, String> snippets) {
        List<String> ids = rows.stream().map(Row::id).toList();
        Map<String, Map<String, Object>> readings = readingFor(ids);
        Map<String, List<Map<String, Object>>> tags = tagsFor(ids);
        Map<String, List<String>> collections = collectionsFor(ids);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Row d : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", d.id());
            data.put("title", d.str("title"));
            data.put("author", d.str("author"));
            data.put("description", d.str("description"));
            data.put("summary", d.str("summary"));
            data.put("summary_source", d.str("summary_source"));
            data.put("summary_model", d.str("summary_model"));
            data.put("summary_at", d.str("summary_at"));
            data.put("kind", d.str("kind"));
            data.put("original_name", d.str("original_name"));
            data.put("size_bytes", d.lng("size_bytes"));
            data.put("page_count", d.integer("page_count"));
            data.put("word_count", d.integer("word_count"));
            data.put("language", d.str("language"));
            data.put("cover_url", coverUrl(d));
            data.put("cover_width", d.integer("cover_width"));
            data.put("cover_height", d.integer("cover_height"));
            data.put("cover_color", d.str("cover_color"));
            data.put("cover_generated", d.bool("cover_generated"));
            data.put("has_reader_html", d.bool("has_reader_html"));
            data.put("file_url", "/api/v1/documents/" + d.id() + "/file");
            data.put("status", d.str("status"));
            data.put("status_label", STATUS_LABELS.getOrDefault(d.str("status"), d.str("status")));
            data.put("status_detail", d.str("status_detail"));
            data.put("tags", tags.getOrDefault(d.id(), List.of()));
            data.put("collections", collections.getOrDefault(d.id(), List.of()));
            data.put("reading", readings.getOrDefault(d.id(), emptyReading()));
            data.put("snippet", snippets.get(d.id()));
            data.put("created_at", d.str("created_at"));
            data.put("updated_at", d.str("updated_at"));
            data.put("processed_at", d.str("processed_at"));
            if (detail) {
                data.put("outline", Json.parse(d.str("outline"), List.of()));
                data.put("metadata", Json.parse(d.str("metadata"), Map.of()));
                data.put("sha256", d.str("sha256"));
                data.put("attempts", d.integer("attempts"));
                data.put("source_path", d.str("source_path"));
            }
            out.add(data);
        }
        return out;
    }

    public Map<String, Object> serializeOne(Row row, boolean detail) {
        return serialize(List.of(row), detail, Map.of()).get(0);
    }
}
