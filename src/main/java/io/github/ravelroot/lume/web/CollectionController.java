package io.github.ravelroot.lume.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.library.CollectionService;

/** Coleções e a ordem dos documentos dentro delas. */
@RestController
@RequestMapping("/api/v1/collections")
public class CollectionController {

    private final CollectionService collections;

    public CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return collections.listCollections();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) String raw) {
        return ResponseEntity.status(201).body(collections.createCollection(Params.body(raw)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return collections.getCollection(Params.uuid(id));
    }

    @RequestMapping(path = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) String raw) {
        return collections.updateCollection(Params.uuid(id), Params.body(raw));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        collections.deleteCollection(Params.uuid(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/documents")
    public Map<String, Object> add(@PathVariable String id, @RequestBody(required = false) String raw) {
        List<String> ids = Params.uuids(Params.body(raw).get("document_ids"));
        if (ids.isEmpty()) {
            throw new ApiException("Escolha pelo menos um documento.");
        }
        return Map.of("added", collections.addToCollection(Params.uuid(id), ids));
    }

    @DeleteMapping("/{id}/documents/{doc}")
    public ResponseEntity<Void> remove(@PathVariable String id, @PathVariable String doc) {
        collections.removeFromCollection(Params.uuid(id), Params.uuid(doc));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/order")
    public ResponseEntity<Void> order(@PathVariable String id, @RequestBody(required = false) String raw) {
        collections.orderCollection(Params.uuid(id), Params.uuids(Params.body(raw).get("document_ids")));
        return ResponseEntity.noContent().build();
    }
}
