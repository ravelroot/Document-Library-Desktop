package io.github.ravelroot.lume.library;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;

/** Destaques, notas e marcadores do leitor, com exportação em Markdown. */
@Service
public class AnnotationService {

    public static final List<String> COLORS = java.util.Arrays.asList("salvia", "rose", "ardosia", "camurca");
    private static final List<String> KINDS = java.util.Arrays.asList("note", "highlight", "bookmark");
    private static final DateTimeFormatter BRAZIL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Database db;
    private final DocumentStore store;

    public AnnotationService(Database db, DocumentStore store) {
        this.db = db;
        this.store = store;
    }

    private static Map<String, Object> annotationData(Row a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("document", a.str("document_id"));
        out.put("kind", a.str("kind"));
        out.put("page", a.integer("page"));
        out.put("anchor", Json.parse(a.str("anchor"), new LinkedHashMap<>()));
        out.put("quote", a.str("quote"));
        out.put("note", a.str("note"));
        out.put("color", a.str("color"));
        out.put("created_at", a.str("created_at"));
        out.put("updated_at", a.str("updated_at"));
        return out;
    }

    public List<Map<String, Object>> listAnnotations(String documentId) {
        store.requireRow(documentId);
        return db.query("SELECT * FROM annotations WHERE document_id = ? ORDER BY page, created_at", documentId)
                .stream().map(AnnotationService::annotationData).toList();
    }

    private record Fields(String kind, String color, String anchor, String quote, String note, Integer page) {
    }

    private static Object pick(Map<String, Object> body, String key, Object fallback) {
        Object value = body.get(key);
        return value != null ? value : fallback;
    }

    private Fields validate(Map<String, Object> body, Row existing) {
        Object kind = pick(body, "kind", existing == null ? null : existing.str("kind"));
        if (!KINDS.contains(kind)) {
            throw new ApiException("Tipo de anotação inválido.");
        }
        Object color = pick(body, "color", existing == null ? "salvia" : existing.str("color"));
        if (!COLORS.contains(color)) {
            throw new ApiException("Cor inválida.");
        }
        Object anchor = pick(body, "anchor", existing == null ? new LinkedHashMap<>() : Json.parse(existing.str("anchor"), new LinkedHashMap<>()));
        if (!(anchor instanceof Map<?, ?>) || Json.size(anchor) > 8000) {
            throw new ApiException("Âncora inválida.");
        }
        String quote = Values.text(pick(body, "quote", existing == null ? "" : existing.str("quote")));
        String note = Values.text(pick(body, "note", existing == null ? "" : existing.str("note")));
        if (quote.length() > 4000 || note.length() > 8000) {
            throw new ApiException("Texto longo demais.");
        }
        if ("note".equals(kind) && Texts.trim(note).isEmpty()) {
            throw ApiException.field("note", "Escreva a nota.");
        }
        Object page = body.containsKey("page") ? body.get("page") : existing == null ? null : existing.integer("page");
        Integer pageNumber = null;
        if (page != null && !"".equals(page)) {
            double value = Values.number(page);
            pageNumber = (int) Math.max(1, Math.floor(Double.isNaN(value) || value == 0 ? 1 : value));
        }
        return new Fields((String) kind, (String) color, Json.write(anchor), Texts.trim(quote), Texts.trim(note), pageNumber);
    }

    public Map<String, Object> createAnnotation(String documentId, Map<String, Object> body) {
        store.requireRow(documentId);
        long total = db.scalar("SELECT count(*) AS n FROM annotations WHERE document_id = ?", documentId);
        if (total >= 2000) {
            throw new ApiException("Limite de anotações neste documento atingido.");
        }
        Fields data = validate(body, null);
        String id = Ids.uuid();
        String stamp = Times.now();
        db.update("INSERT INTO annotations (id, document_id, kind, page, anchor, quote, note, color, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, documentId, data.kind(), data.page(), data.anchor(), data.quote(), data.note(), data.color(), stamp, stamp);
        return annotationData(db.one("SELECT * FROM annotations WHERE id = ?", id));
    }

    public Map<String, Object> updateAnnotation(String id, Map<String, Object> body) {
        Row existing = db.one("SELECT * FROM annotations WHERE id = ?", id);
        if (existing == null) {
            throw ApiException.notFound("Anotação não encontrada.");
        }
        Fields data = validate(body, existing);
        db.update("UPDATE annotations SET kind = ?, page = ?, anchor = ?, quote = ?, note = ?, color = ?, updated_at = ? WHERE id = ?",
                data.kind(), data.page(), data.anchor(), data.quote(), data.note(), data.color(), Times.now(), id);
        return annotationData(db.one("SELECT * FROM annotations WHERE id = ?", id));
    }

    public void deleteAnnotation(String id) {
        if (db.update("DELETE FROM annotations WHERE id = ?", id) == 0) {
            throw ApiException.notFound("Anotação não encontrada.");
        }
    }

    /** As anotações em Markdown, para salvar ou colar em outro lugar. */
    public Map<String, Object> annotationsMarkdown(String documentId) {
        Row d = store.requireRow(documentId);
        List<Map<String, Object>> items = listAnnotations(documentId);
        List<String> lines = new ArrayList<>(List.of("# " + d.str("title"), ""));
        if (!d.text("author").isEmpty()) {
            lines.add("*" + d.str("author") + "*");
            lines.add("");
        }
        lines.add("Anotações exportadas do Lume em " + LocalDate.now().format(BRAZIL_DATE) + ".");
        lines.add("");
        for (Map<String, Object> a : items) {
            String where = a.get("page") != null ? "Página " + a.get("page") : "bookmark".equals(a.get("kind")) ? "Marcador" : "Trecho";
            lines.add("## " + where);
            String quote = Values.text(a.get("quote"));
            String note = Values.text(a.get("note"));
            if (!quote.isEmpty()) {
                lines.add("");
                lines.add("> " + quote.replace("\n", "\n> "));
            }
            if (!note.isEmpty()) {
                lines.add("");
                lines.add(note);
            }
            lines.add("");
        }
        if (items.isEmpty()) {
            lines.add("_Nenhuma anotação._");
        }
        String base = Texts.trim(d.str("title").replaceAll("[\\\\/:*?\"<>|]+", " "));
        base = base.length() > 80 ? base.substring(0, 80) : base;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", (base.isEmpty() ? "anotacoes" : base) + " - anotações.md");
        out.put("content", String.join("\n", lines));
        return out;
    }
}
