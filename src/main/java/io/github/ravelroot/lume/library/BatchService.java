package io.github.ravelroot.lume.library;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.pipeline.PipelineService;

/**
 * Ações em lote da biblioteca (seleção de vários documentos): estante, favorito, etiquetas, coleção, excluir e
 * processar de novo. Cada ação reaproveita os serviços de um documento só, então os eventos da janela são os mesmos.
 */
@Service
public class BatchService {

    public static final int MAX_IDS = 500;
    static final List<String> ACTIONS = List.of("shelf", "favorite", "unfavorite", "tags_add", "collection_add", "collection_remove",
            "delete", "reprocess", "redescribe");

    private final Database db;
    private final LibraryService library;
    private final ReadingService reading;
    private final CollectionService collections;
    private final EventBus events;
    private final ObjectProvider<PipelineService> pipeline;

    public BatchService(Database db, LibraryService library, ReadingService reading, CollectionService collections, EventBus events,
                        ObjectProvider<PipelineService> pipeline) {
        this.db = db;
        this.library = library;
        this.reading = reading;
        this.collections = collections;
        this.events = events;
        this.pipeline = pipeline;
    }

    /** Executa a ação nos documentos que existem; devolve {ok, affected}. */
    public Map<String, Object> apply(Object rawIds, String action, Object value) {
        if (!ACTIONS.contains(action)) {
            throw new ApiException("Ação em lote desconhecida.");
        }
        if (!(rawIds instanceof List<?> list) || list.isEmpty()) {
            throw new ApiException("Escolha pelo menos um documento.");
        }
        if (list.size() > MAX_IDS) {
            throw new ApiException("Escolha no máximo " + MAX_IDS + " documentos por vez.");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : list) {
            if (Ids.isUuid(item)) {
                unique.add(String.valueOf(item));
            }
        }
        List<String> ids = new ArrayList<>();
        if (!unique.isEmpty()) {
            List<Row> rows = db.query("SELECT id FROM documents WHERE id IN (" + DocumentSerializer.placeholders(unique.size()) + ")",
                    unique.toArray());
            List<String> found = rows.stream().map(Row::id).toList();
            unique.stream().filter(found::contains).forEach(ids::add);
        }
        int affected = switch (action) {
            case "shelf" -> {
                String shelf = Values.truthy(value) ? Values.text(value) : "";
                if (!shelf.isEmpty() && !SearchService.SHELVES.contains(shelf)) {
                    throw new ApiException("Estante inválida.");
                }
                ids.forEach(id -> reading.updateReading(id, Map.of("shelf", shelf)));
                yield ids.size();
            }
            case "favorite", "unfavorite" -> {
                boolean on = action.equals("favorite");
                ids.forEach(id -> reading.updateReading(id, Map.of("favorite", on)));
                yield ids.size();
            }
            case "tags_add" -> {
                List<?> names = value instanceof List<?> l ? l : Values.truthy(value) ? List.of(Values.text(value)) : List.of();
                if (names.isEmpty()) {
                    throw new ApiException("Informe a etiqueta.");
                }
                int count = 0;
                for (String id : ids) {
                    if (library.addTags(id, names) > 0) {
                        count++;
                    }
                }
                yield count;
            }
            case "collection_add" -> {
                String collection = Values.text(value);
                if (!Ids.isUuid(collection)) {
                    throw new ApiException("Escolha uma coleção.");
                }
                yield collections.addToCollection(collection, ids);
            }
            case "collection_remove" -> {
                String collection = Values.text(value);
                if (!Ids.isUuid(collection)) {
                    throw new ApiException("Escolha uma coleção.");
                }
                collections.requireCollection(collection);
                int removed = ids.isEmpty() ? 0 : db.update("DELETE FROM collection_items WHERE collection_id = ? AND document_id IN ("
                        + DocumentSerializer.placeholders(ids.size()) + ")", concat(collection, ids));
                if (removed > 0) {
                    events.emit("collections.changed");
                }
                yield removed;
            }
            case "delete" -> {
                ids.forEach(library::deleteDocument);
                if (!ids.isEmpty()) {
                    events.emit("collections.changed");
                }
                yield ids.size();
            }
            case "reprocess", "redescribe" -> {
                PipelineService queue = pipeline.getObject();
                int count = 0;
                for (String id : ids) {
                    Row d = db.one("SELECT status FROM documents WHERE id = ?", id);
                    String status = d == null ? "" : d.str("status");
                    if ("extracting".equals(status) || "describing".equals(status)) {
                        continue;
                    }
                    if (action.equals("redescribe") && !"ready".equals(status)) {
                        continue;
                    }
                    queue.requeue(id, action.equals("reprocess") ? "extract" : "describe");
                    count++;
                }
                yield count;
            }
            default -> 0;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("affected", affected);
        return out;
    }

    private static Object[] concat(String first, List<String> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            args[i + 1] = rest.get(i);
        }
        return args;
    }
}
