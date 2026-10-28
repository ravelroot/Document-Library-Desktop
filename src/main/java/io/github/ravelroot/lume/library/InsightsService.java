package io.github.ravelroot.lume.library;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;

/** Números da tela Estatísticas: o tamanho da biblioteca, o ritmo de leitura e o que mais aparece nela. */
@Service
public class InsightsService {

    private final Database db;

    public InsightsService(Database db) {
        this.db = db;
    }

    /** Últimos 12 meses (o atual por último), com zero onde não houve nada. */
    private List<Map<String, Object>> byMonth(String column, String table, String where) {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        Map<String, Long> counts = new HashMap<>();
        for (Row r : db.query("SELECT substr(" + column + ", 1, 7) AS month, count(*) AS n FROM " + table + " WHERE " + column
                + " IS NOT NULL" + (where.isEmpty() ? "" : " AND " + where) + " GROUP BY month")) {
            counts.put(r.str("month"), r.lng("n", 0));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            String month = now.minusMonths(i).toString();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", month);
            item.put("count", counts.getOrDefault(month, 0L));
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> insights() {
        Row docs = db.one("SELECT count(*) AS documents, coalesce(sum(page_count), 0) AS pages, coalesce(sum(word_count), 0) AS words, "
                + "coalesce(sum(size_bytes), 0) AS bytes, coalesce(sum(kind = 'pdf'), 0) AS pdf, coalesce(sum(kind = 'docx'), 0) AS docx FROM documents");
        Row notes = db.one("SELECT count(*) AS annotations, coalesce(sum(kind = 'highlight'), 0) AS highlights, "
                + "coalesce(sum(kind = 'note'), 0) AS notes, coalesce(sum(kind = 'bookmark'), 0) AS bookmarks FROM annotations");
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("documents", docs.lng("documents", 0));
        totals.put("pages", docs.lng("pages", 0));
        totals.put("words", docs.lng("words", 0));
        totals.put("bytes", docs.lng("bytes", 0));
        totals.put("annotations", notes.lng("annotations", 0));
        totals.put("highlights", notes.lng("highlights", 0));
        totals.put("notes", notes.lng("notes", 0));
        totals.put("bookmarks", notes.lng("bookmarks", 0));
        totals.put("collections", db.scalar("SELECT count(*) FROM collections"));
        totals.put("tags", db.scalar("SELECT count(*) FROM tags"));

        String yearStart = LocalDate.now(ZoneOffset.UTC).getYear() + "-01-01";
        Row shelves = db.one("SELECT coalesce(sum(shelf = 'lido'), 0) AS finished, coalesce(sum(shelf = 'lido' AND finished_at >= ?), 0) AS year, "
                + "coalesce(sum(shelf = 'lendo'), 0) AS reading, coalesce(sum(shelf = 'para_ler'), 0) AS wanted, "
                + "coalesce(sum(favorite = 1), 0) AS favorites FROM reading_states", yearStart);
        long pagesRead = db.scalar("SELECT coalesce(sum(CASE WHEN rs.shelf = 'lido' THEN coalesce(d.page_count, 0) "
                + "WHEN rs.last_opened_at IS NULL THEN 0 "
                + "WHEN d.kind = 'pdf' THEN rs.page "
                + "ELSE CAST(round(rs.progress * coalesce(d.page_count, 0)) AS INTEGER) END), 0) AS n "
                + "FROM reading_states rs JOIN documents d ON d.id = rs.document_id");
        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("finished_total", shelves.lng("finished", 0));
        reading.put("finished_this_year", shelves.lng("year", 0));
        reading.put("in_progress", shelves.lng("reading", 0));
        reading.put("want_to_read", shelves.lng("wanted", 0));
        reading.put("favorites", shelves.lng("favorites", 0));
        reading.put("pages_read", pagesRead);

        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("pdf", docs.lng("pdf", 0));
        kinds.put("docx", docs.lng("docx", 0));

        List<Map<String, Object>> topTags = db.query("SELECT t.name, t.slug, count(dt.document_id) AS count FROM tags t "
                + "JOIN document_tags dt ON dt.tag_id = t.id GROUP BY t.id ORDER BY count DESC, t.name LIMIT 10").stream().map(r -> {
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("name", r.str("name"));
                    tag.put("slug", r.str("slug"));
                    tag.put("count", r.lng("count", 0));
                    return tag;
                }).toList();
        List<Map<String, Object>> largest = db.query("SELECT id, title, kind, page_count, size_bytes FROM documents "
                + "ORDER BY coalesce(page_count, 0) DESC, size_bytes DESC LIMIT 5").stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", r.id());
                    item.put("title", r.str("title"));
                    item.put("kind", r.str("kind"));
                    item.put("page_count", r.integer("page_count"));
                    item.put("size_bytes", r.lng("size_bytes"));
                    return item;
                }).toList();
        List<Map<String, Object>> languages = db.query("SELECT CASE WHEN language = '' THEN 'indefinido' ELSE language END AS language, "
                + "count(*) AS count FROM documents GROUP BY 1 ORDER BY count DESC").stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("language", r.str("language"));
                    item.put("count", r.lng("count", 0));
                    return item;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("reading", reading);
        out.put("kinds", kinds);
        out.put("added_by_month", byMonth("created_at", "documents", ""));
        out.put("finished_by_month", byMonth("finished_at", "reading_states", "shelf = 'lido'"));
        out.put("top_tags", topTags);
        out.put("largest", largest);
        out.put("languages", languages);
        return out;
    }
}
