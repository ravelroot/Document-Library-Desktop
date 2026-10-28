package io.github.ravelroot.lume.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.ai.AiService;
import io.github.ravelroot.lume.ai.DocumentFacts;
import io.github.ravelroot.lume.ai.PassageFinder;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.settings.SettingsService;

/** "Perguntar ao documento" e "Documentos parecidos" (etiquetas em comum + busca em texto completo). */
@Service
public class DiscoveryService {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final Database db;
    private final DocumentStore store;
    private final DocumentSerializer serializer;
    private final AiService ai;
    private final SettingsService settings;

    public DiscoveryService(Database db, DocumentStore store, DocumentSerializer serializer, AiService ai, SettingsService settings) {
        this.db = db;
        this.store = store;
        this.serializer = serializer;
        this.ai = ai;
        this.settings = settings;
    }

    /* ---------- Perguntar ao documento ---------- */

    public Map<String, Object> ask(String id, Object rawQuestion) {
        Row d = store.requireRow(id);
        String question = Texts.singleLine(rawQuestion);
        if (question.length() < 3) {
            throw ApiException.field("question", "Escreva a pergunta (pelo menos 3 letras).");
        }
        if (question.length() > 500) {
            throw ApiException.field("question", "A pergunta pode ter até 500 caracteres.");
        }
        if (!"ready".equals(d.str("status"))) {
            throw ApiException.conflict("Espere o documento terminar de processar.");
        }
        Row content = db.one("SELECT text FROM document_content WHERE document_id = ?", id);
        String text = content == null ? "" : content.text("text");
        if (Texts.trim(text.replace('\f', ' ')).length() < 20) {
            throw new ApiException("Este documento não tem texto para consultar (provavelmente foi digitalizado como imagem).");
        }
        List<String> outline = Json.parseList(d.str("outline")).stream()
                .map(item -> item instanceof Map<?, ?> m && m.get("title") != null ? String.valueOf(m.get("title")) : "")
                .toList();
        DocumentFacts facts = new DocumentFacts(d.str("title"), d.text("author"), d.str("kind"), d.integer("page_count"), outline);
        return ai.answerQuestion(facts, text, d.text("summary"), question, settings.aiConfig()).toMap();
    }

    /* ---------- Documentos parecidos ---------- */

    public List<Map<String, Object>> related(String id, int limit) {
        Row d = store.requireRow(id);
        int max = Math.max(1, Math.min(24, limit));
        Map<String, Double> scores = new HashMap<>();
        // Etiquetas em comum (as da pessoa valem um pouco mais).
        for (Row r : db.query("SELECT other.document_id AS id, sum(CASE WHEN other.source = 'usuario' THEN 4.0 ELSE 3.0 END) AS s "
                + "FROM document_tags mine JOIN document_tags other ON other.tag_id = mine.tag_id AND other.document_id != mine.document_id "
                + "WHERE mine.document_id = ? GROUP BY other.document_id", id)) {
            scores.merge(r.id(), r.dbl("s"), Double::sum);
        }
        // Busca em texto completo com os termos do título e das etiquetas.
        Set<String> terms = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        sources.add(d.text("title"));
        db.query("SELECT t.name FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = ?", id)
                .forEach(r -> sources.add(r.str("name")));
        for (String source : sources) {
            Matcher m = TOKEN.matcher(source.toLowerCase(Locale.ROOT));
            while (m.find()) {
                String token = m.group();
                if (token.length() >= 4 && PassageFinder.terms(token).size() == 1 && terms.size() < 14) {
                    terms.add(token);
                }
            }
        }
        if (!terms.isEmpty()) {
            String match = String.join(" OR ", terms.stream().map(t -> "\"" + t + "\"" + (t.length() >= 5 ? "*" : "")).toList());
            List<Row> hits = db.query("SELECT document_id FROM documents_fts WHERE documents_fts MATCH ? AND document_id != ? "
                    + "ORDER BY bm25(documents_fts, 0, 10.0, 5.0, 3.0, 1.0) LIMIT 30", match, id);
            for (int i = 0; i < hits.size(); i++) {
                scores.merge(hits.get(i).str("document_id"), 2.5 * (1 - i / 30.0), Double::sum);
            }
        }
        scores.remove(id);
        if (scores.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(scores.keySet());
        List<Row> rows = new ArrayList<>(db.query("SELECT * FROM documents WHERE status = 'ready' AND id IN ("
                + DocumentSerializer.placeholders(ids.size()) + ")", ids.toArray()));
        rows.sort((a, b) -> {
            int byScore = Double.compare(scores.get(b.id()), scores.get(a.id()));
            return byScore != 0 ? byScore : a.text("title_sort").compareTo(b.text("title_sort"));
        });
        return serializer.serialize(rows.size() > max ? rows.subList(0, max) : rows, false, Map.of());
    }
}
