package io.github.ravelroot.lume.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;

/**
 * Busca em texto completo (SQLite FTS5, sem diferenciar acentos) e a listagem da biblioteca com filtros, ordenação e
 * paginação.
 */
@Service
public class SearchService {

    static final int SEARCH_TEXT_LIMIT = 300_000;
    static final List<String> SHELVES = java.util.Arrays.asList("para_ler", "lendo", "lido");
    private static final Pattern QUERY_TERM = Pattern.compile("(-?)\"([^\"]+)\"|(-?)([^\\s\"]+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern LIKE_SPECIAL = Pattern.compile("([\\\\%_])");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Map<String, String> ORDERS = Map.of(
            "recent", "d.created_at DESC",
            "title", "d.title_sort, d.created_at DESC",
            "author", "(d.author_sort = ''), d.author_sort, d.title_sort",
            "last_read", "(rs.last_opened_at IS NULL), rs.last_opened_at DESC, d.created_at DESC",
            "pages", "(d.page_count IS NULL), d.page_count DESC, d.created_at DESC",
            "size", "d.size_bytes DESC");

    private final Database db;
    private final DocumentSerializer serializer;

    public SearchService(Database db, DocumentSerializer serializer) {
        this.db = db;
        this.serializer = serializer;
    }

    /**
     * Converte o texto digitado numa consulta FTS5 segura: palavras viram prefixos, "aspas" viram frase e -palavra
     * exclui. Devolve null se não sobrar nenhum termo positivo.
     */
    public String ftsQuery(String raw) {
        List<String> positives = new ArrayList<>();
        List<String> negatives = new ArrayList<>();
        Matcher match = QUERY_TERM.matcher(raw == null ? "" : raw);
        while (match.find()) {
            boolean phrase = match.group(2) != null;
            String sign = phrase ? match.group(1) : match.group(3);
            boolean negative = sign != null && !sign.isEmpty();
            List<String> tokens = new ArrayList<>();
            Matcher token = TOKEN.matcher(phrase ? match.group(2) : match.group(4));
            while (token.find()) {
                tokens.add(token.group().toLowerCase(Locale.ROOT));
            }
            if (tokens.isEmpty()) {
                continue;
            }
            String expr;
            if (phrase || tokens.size() > 1) {
                expr = "\"" + String.join(" ", tokens) + "\"";
            } else {
                expr = tokens.get(0).length() >= 2 ? "\"" + tokens.get(0) + "\"*" : "\"" + tokens.get(0) + "\"";
            }
            (negative ? negatives : positives).add(expr);
        }
        if (positives.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(String.join(" AND ", positives));
        negatives.forEach(n -> out.append(" NOT ").append(n));
        return out.toString();
    }

    private Map<String, String> snippetsFor(List<String> ids, String match) {
        Map<String, String> map = new HashMap<>();
        if (ids.isEmpty() || match == null) {
            return map;
        }
        List<Object> args = new ArrayList<>();
        args.add(match);
        args.addAll(ids);
        List<Row> rows = db.query("SELECT document_id, snippet(documents_fts, 4, char(2), char(3), ' … ', 26) AS s FROM documents_fts "
                + "WHERE documents_fts MATCH ? AND document_id IN (" + DocumentSerializer.placeholders(ids.size()) + ")", args.toArray());
        for (Row r : rows) {
            String s = r.str("s");
            if (s == null || !s.contains("\u0002")) {
                continue;
            }
            String safe = Texts.escapeHtml(Texts.trim(SPACES.matcher(s).replaceAll(" ")));
            map.put(r.str("document_id"), safe.replace("\u0002", "<mark>").replace("\u0003", "</mark>"));
        }
        return map;
    }

    /** Lista com filtros (kind, status, shelf, favorite, collection, tag), busca (q), ordem (sort) e página. */
    public Map<String, Object> listDocuments(Map<String, String> params) {
        String q = Texts.trim(params.getOrDefault("q", ""));
        if (q.length() > 200) {
            q = q.substring(0, 200);
        }
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        StringBuilder join = new StringBuilder("LEFT JOIN reading_states rs ON rs.document_id = d.id");
        String kind = params.get("kind");
        if ("pdf".equals(kind) || "docx".equals(kind)) {
            where.add("d.kind = ?");
            args.add(kind);
        }
        String status = params.get("status");
        if ("processing".equals(status)) {
            where.add("d.status IN ('queued','extracting','describing')");
        } else if ("ready".equals(status) || "failed".equals(status)) {
            where.add("d.status = ?");
            args.add(status);
        }
        String shelf = params.get("shelf");
        if (SHELVES.contains(shelf)) {
            where.add("rs.shelf = ?");
            args.add(shelf);
        }
        if ("1".equals(params.get("favorite")) || "true".equals(params.get("favorite"))) {
            where.add("rs.favorite = 1");
        }
        String collection = params.get("collection");
        boolean inCollection = collection != null && !collection.isEmpty();
        if (inCollection) {
            join.append(" JOIN collection_items ci ON ci.document_id = d.id AND ci.collection_id = ?");
            args.add(0, collection);
        }
        String tag = params.get("tag");
        if (tag != null && !tag.isEmpty()) {
            where.add("EXISTS (SELECT 1 FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = d.id AND t.slug = ?)");
            args.add(tag);
        }

        String match = q.isEmpty() ? null : ftsQuery(q);
        String rankJoin = "";
        List<Object> rankArgs = new ArrayList<>();
        if (!q.isEmpty()) {
            String like = "%" + LIKE_SPECIAL.matcher(Texts.fold(q)).replaceAll("\\\\$1") + "%";
            if (match != null) {
                rankJoin = "LEFT JOIN (SELECT document_id, bm25(documents_fts, 0, 10.0, 5.0, 3.0, 1.0) AS rank FROM documents_fts "
                        + "WHERE documents_fts MATCH ?) f ON f.document_id = d.id";
                rankArgs.add(match);
                where.add("(f.document_id IS NOT NULL OR d.title_sort LIKE ? ESCAPE '\\')");
            } else {
                where.add("d.title_sort LIKE ? ESCAPE '\\'");
            }
            args.add(like);
        }

        String sort = params.get("sort");
        if (sort == null || sort.isEmpty()) {
            sort = !q.isEmpty() ? "relevance" : inCollection ? "collection" : "recent";
        }
        String order = switch (sort) {
            case "relevance" -> match != null ? "(f.rank IS NULL), f.rank, d.created_at DESC" : "d.created_at DESC";
            case "collection" -> inCollection ? "ci.position, ci.added_at, d.created_at DESC" : "d.created_at DESC";
            default -> ORDERS.getOrDefault(sort, ORDERS.get("recent"));
        };
        String from = "FROM documents d " + rankJoin + " " + join + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        List<Object> allArgs = new ArrayList<>(rankArgs);
        allArgs.addAll(args);

        double requestedSize = Values.number(params.get("page_size"));
        int pageSize = Values.clampInt(Double.isNaN(requestedSize) || requestedSize == 0 ? 48 : requestedSize, 1, 200);
        long count = db.scalar("SELECT count(*) AS n " + from, allArgs.toArray());
        int pages = (int) Math.max(1, Math.ceil(count / (double) pageSize));
        double requestedPage = Values.number(params.get("page"));
        int page = Values.clampInt(Double.isNaN(requestedPage) || requestedPage == 0 ? 1 : requestedPage, 1, pages);
        List<Object> pageArgs = new ArrayList<>(allArgs);
        pageArgs.add(pageSize);
        pageArgs.add((page - 1) * pageSize);
        List<Row> rows = db.query("SELECT d.* " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, String> snippets = match != null ? snippetsFor(rows.stream().map(Row::id).toList(), match) : Map.of();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", count);
        out.put("page", page);
        out.put("pages", pages);
        out.put("next", page < pages ? page + 1 : null);
        out.put("results", serializer.serialize(rows, false, snippets));
        return out;
    }

    /** Reconstrói a linha do documento no índice de busca (título, autor, descrições e etiquetas, texto). */
    public void refreshSearch(String id) {
        db.transaction(() -> {
            db.update("DELETE FROM documents_fts WHERE document_id = ?", id);
            Row d = db.one("SELECT * FROM documents WHERE id = ?", id);
            if (d == null) {
                return;
            }
            List<String> tags = db.query("SELECT t.name FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = ?", id)
                    .stream().map(r -> r.str("name")).toList();
            Row content = db.one("SELECT text FROM document_content WHERE document_id = ?", id);
            List<String> extra = new ArrayList<>();
            for (String part : new String[] {d.text("summary"), d.text("description"), String.join(" ", tags)}) {
                if (!part.isEmpty()) {
                    extra.add(part);
                }
            }
            String body = content == null ? "" : content.text("text");
            db.update("INSERT INTO documents_fts (document_id, title, author, extra, body) VALUES (?, ?, ?, ?, ?)",
                    id, d.str("title"), d.str("author"), String.join(" ", extra),
                    body.length() > SEARCH_TEXT_LIMIT ? body.substring(0, SEARCH_TEXT_LIMIT) : body);
        });
    }
}
