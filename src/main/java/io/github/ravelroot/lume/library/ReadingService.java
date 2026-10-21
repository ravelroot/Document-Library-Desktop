package io.github.ravelroot.lume.library;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;

/** Estado de leitura: estante, página, progresso, posição no leitor, favorito e o "continuar lendo". */
@Service
public class ReadingService {

    private final Database db;
    private final DocumentStore store;
    private final EventBus events;

    public ReadingService(Database db, DocumentStore store, EventBus events) {
        this.db = db;
        this.store = store;
        this.events = events;
    }

    public Map<String, Object> getReading(String id) {
        store.requireRow(id);
        return DocumentSerializer.readingData(db.one("SELECT * FROM reading_states WHERE document_id = ?", id));
    }

    private void upsert(String id, Map<String, Object> fields) {
        String stamp = Times.now();
        db.transaction(() -> {
            db.update("INSERT INTO reading_states (document_id, updated_at) VALUES (?, ?) ON CONFLICT (document_id) DO NOTHING", id, stamp);
            if (!fields.isEmpty()) {
                StringBuilder sql = new StringBuilder("UPDATE reading_states SET ");
                fields.keySet().forEach(key -> sql.append(key).append(" = ?, "));
                sql.append("updated_at = ? WHERE document_id = ?");
                Object[] args = new Object[fields.size() + 2];
                int i = 0;
                for (Object value : fields.values()) {
                    args[i++] = value;
                }
                args[i++] = stamp;
                args[i] = id;
                db.update(sql.toString(), args);
            }
        });
    }

    public Map<String, Object> updateReading(String id, Map<String, Object> body) {
        Row d = store.requireRow(id);
        Map<String, Object> current = getReading(id);
        Map<String, Object> fields = new LinkedHashMap<>();
        String stamp = Times.now();
        if (body.containsKey("shelf")) {
            String shelf = Values.truthy(body.get("shelf")) ? Values.text(body.get("shelf")) : "";
            if (!shelf.isEmpty() && !SearchService.SHELVES.contains(shelf)) {
                throw new ApiException("Estante inválida.");
            }
            fields.put("shelf", shelf);
            if (shelf.equals("lendo") && current.get("started_at") == null) {
                fields.put("started_at", stamp);
            }
            fields.put("finished_at", shelf.equals("lido") ? stamp : null);
        }
        if (body.containsKey("page")) {
            double requested = Values.number(body.get("page"));
            int page = (int) Math.max(1, Math.floor(Double.isNaN(requested) || requested == 0 ? 1 : requested));
            Integer pageCount = d.integer("page_count");
            if (pageCount != null && pageCount > 0) {
                page = Math.min(page, pageCount);
            }
            fields.put("page", page);
        }
        if (body.containsKey("progress")) {
            double progress = Values.number(body.get("progress"));
            fields.put("progress", Values.clamp(Double.isNaN(progress) ? 0 : progress, 0, 1));
        }
        if (body.containsKey("location")) {
            Object location = body.get("location");
            if (!(location instanceof Map<?, ?>) || Json.size(location) > 1000) {
                throw new ApiException("Posição inválida.");
            }
            fields.put("location", Json.write(location));
        }
        if (body.containsKey("favorite")) {
            fields.put("favorite", Values.truthy(body.get("favorite")) ? 1 : 0);
        }
        upsert(id, fields);
        events.emit("reading.updated", Map.of("id", id));
        return getReading(id);
    }

    /** Registra que o documento foi aberto no leitor: atualiza o "continuar lendo" e move para "Lendo". */
    public Map<String, Object> openDocument(String id) {
        store.requireRow(id);
        Map<String, Object> current = getReading(id);
        String stamp = Times.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("last_opened_at", stamp);
        Object shelf = current.get("shelf");
        if ("".equals(shelf) || "para_ler".equals(shelf)) {
            fields.put("shelf", "lendo");
            fields.put("started_at", current.get("started_at") != null ? current.get("started_at") : stamp);
        }
        upsert(id, fields);
        events.emit("reading.updated", Map.of("id", id));
        return getReading(id);
    }
}
