package io.github.ravelroot.lume.library;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;

/** Coleções: nome único (sem diferenciar maiúsculas), descrição e ordem própria dos documentos. */
@Service
public class CollectionService {

    private final Database db;
    private final EventBus events;

    public CollectionService(Database db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    public Row requireCollection(String id) {
        Row c = db.one("SELECT * FROM collections WHERE id = ?", String.valueOf(id));
        if (c == null) {
            throw ApiException.notFound("Coleção não encontrada.");
        }
        return c;
    }

    private Map<String, Object> collectionData(Row c) {
        long count = db.scalar("SELECT count(*) AS n FROM collection_items WHERE collection_id = ?", c.id());
        List<Map<String, Object>> covers = db.query("SELECT d.id, d.title, d.cover_color, d.cover_version FROM collection_items ci "
                + "JOIN documents d ON d.id = ci.document_id WHERE ci.collection_id = ? ORDER BY ci.position, ci.added_at LIMIT 4", c.id())
                .stream().map(d -> {
                    Map<String, Object> cover = new LinkedHashMap<>();
                    cover.put("id", d.id());
                    cover.put("title", d.str("title"));
                    cover.put("color", d.str("cover_color"));
                    cover.put("url", DocumentSerializer.coverUrl(d));
                    return cover;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id());
        out.put("name", c.str("name"));
        out.put("description", c.str("description"));
        out.put("document_count", count);
        out.put("covers", covers);
        out.put("created_at", c.str("created_at"));
        out.put("updated_at", c.str("updated_at"));
        return out;
    }

    public List<Map<String, Object>> listCollections() {
        return db.query("SELECT * FROM collections ORDER BY lower(name)").stream().map(this::collectionData).toList();
    }

    public Map<String, Object> getCollection(String id) {
        return collectionData(requireCollection(id));
    }

    private Map<String, Object> collectionFields(Map<String, Object> body, Row current) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (body.containsKey("name") || current == null) {
            String name = Texts.singleLine(body.get("name"), 80);
            if (name.isEmpty()) {
                throw ApiException.field("name", "Dê um nome à coleção.");
            }
            Row clash = db.one("SELECT id FROM collections WHERE lower(name) = lower(?) AND id != ?", name, current == null ? "" : current.id());
            if (clash != null) {
                throw ApiException.field("name", "Você já tem uma coleção com esse nome.");
            }
            fields.put("name", name);
        }
        if (body.containsKey("description")) {
            String description = Texts.trim(Values.text(body.get("description")));
            fields.put("description", description.length() > 300 ? description.substring(0, 300) : description);
        }
        return fields;
    }

    public Map<String, Object> createCollection(Map<String, Object> body) {
        Map<String, Object> fields = collectionFields(body, null);
        String id = Ids.uuid();
        String stamp = Times.now();
        db.update("INSERT INTO collections (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, fields.get("name"), fields.getOrDefault("description", ""), stamp, stamp);
        events.emit("collections.changed");
        return collectionData(requireCollection(id));
    }

    public Map<String, Object> updateCollection(String id, Map<String, Object> body) {
        Row current = requireCollection(id);
        Map<String, Object> fields = collectionFields(body, current);
        if (!fields.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE collections SET ");
            fields.keySet().forEach(key -> sql.append(key).append(" = ?, "));
            sql.append("updated_at = ? WHERE id = ?");
            Object[] args = new Object[fields.size() + 2];
            int i = 0;
            for (Object value : fields.values()) {
                args[i++] = value;
            }
            args[i++] = Times.now();
            args[i] = id;
            db.update(sql.toString(), args);
        }
        events.emit("collections.changed");
        return collectionData(requireCollection(id));
    }

    public void deleteCollection(String id) {
        requireCollection(id);
        db.update("DELETE FROM collections WHERE id = ?", id);
        events.emit("collections.changed");
    }

    /** Põe documentos no fim da coleção; os que já estavam são ignorados. Devolve quantos entraram. */
    public int addToCollection(String collectionId, List<String> documentIds) {
        requireCollection(collectionId);
        int added = db.transaction(() -> {
            int count = 0;
            long last = db.scalar("SELECT coalesce(max(position), 0) AS p FROM collection_items WHERE collection_id = ?", collectionId);
            for (String docId : documentIds) {
                if (db.one("SELECT id FROM documents WHERE id = ?", docId) == null) {
                    continue;
                }
                int changed = db.update("INSERT OR IGNORE INTO collection_items (collection_id, document_id, position, added_at) VALUES (?, ?, ?, ?)",
                        collectionId, docId, last + 1, Times.now());
                if (changed > 0) {
                    count++;
                    last++;
                }
            }
            if (count > 0) {
                db.update("UPDATE collections SET updated_at = ? WHERE id = ?", Times.now(), collectionId);
            }
            return count;
        });
        if (added > 0) {
            events.emit("collections.changed");
        }
        return added;
    }

    public void removeFromCollection(String collectionId, String documentId) {
        requireCollection(collectionId);
        db.update("DELETE FROM collection_items WHERE collection_id = ? AND document_id = ?", collectionId, documentId);
        events.emit("collections.changed");
    }

    /** Nova ordem: os documentos citados vêm primeiro, na ordem da lista; os demais depois, na ordem antiga. */
    public void orderCollection(String collectionId, List<String> documentIds) {
        requireCollection(collectionId);
        if (documentIds.size() > 5000) {
            throw new ApiException("Lista de documentos inválida.");
        }
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < documentIds.size(); i++) {
            positions.putIfAbsent(documentIds.get(i), i + 1);
        }
        db.transaction(() -> {
            for (Row item : db.query("SELECT document_id, position FROM collection_items WHERE collection_id = ?", collectionId)) {
                int position = positions.getOrDefault(item.str("document_id"), positions.size() + item.integer("position", 0));
                db.update("UPDATE collection_items SET position = ? WHERE collection_id = ? AND document_id = ?",
                        position, collectionId, item.str("document_id"));
            }
        });
    }
}
