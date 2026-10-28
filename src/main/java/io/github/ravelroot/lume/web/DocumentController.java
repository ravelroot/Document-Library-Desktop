package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.library.AnnotationService;
import io.github.ravelroot.lume.library.BatchService;
import io.github.ravelroot.lume.library.DiscoveryService;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.library.ReadingService;
import io.github.ravelroot.lume.library.SearchService;
import io.github.ravelroot.lume.pipeline.PipelineService;

/** Documentos: lista e busca, detalhes, arquivo original, capa, versão de leitura, etiquetas, leitura e anotações. */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private static final Pattern MEDIA_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,120}$");
    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "gif", "image/gif", "bmp", "image/bmp", "webp", "image/webp");
    private static final Map<String, String> FILE_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final LibraryService library;
    private final SearchService search;
    private final ReadingService reading;
    private final AnnotationService annotations;
    private final DocumentStore store;
    private final PipelineService pipeline;
    private final BatchService batch;
    private final DiscoveryService discovery;

    public DocumentController(LibraryService library, SearchService search, ReadingService reading, AnnotationService annotations,
                              DocumentStore store, PipelineService pipeline, BatchService batch, DiscoveryService discovery) {
        this.library = library;
        this.search = search;
        this.reading = reading;
        this.annotations = annotations;
        this.store = store;
        this.pipeline = pipeline;
        this.batch = batch;
        this.discovery = discovery;
    }

    @GetMapping("/documents")
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        return search.listDocuments(query);
    }

    /** Ações em lote na seleção da biblioteca. */
    @PostMapping("/documents/batch")
    public Map<String, Object> batch(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        return batch.apply(body.get("ids"), Values.text(body.get("action")), body.get("value"));
    }

    /** Perguntar ao documento (resumo local ou o provedor de IA configurado). */
    @PostMapping("/documents/{id}/ask")
    public Map<String, Object> ask(@PathVariable String id, @RequestBody(required = false) String raw) {
        return discovery.ask(Params.uuid(id), Params.body(raw).get("question"));
    }

    @GetMapping("/documents/{id}/related")
    public List<Map<String, Object>> related(@PathVariable String id, @RequestParam(required = false) String limit) {
        double requested = Values.number(limit);
        return discovery.related(Params.uuid(id), Double.isNaN(requested) || requested == 0 ? 6 : (int) requested);
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return library.getDocument(Params.uuid(id));
    }

    @RequestMapping(path = "/documents/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) String raw) {
        return library.updateDocument(Params.uuid(id), Params.body(raw));
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        library.deleteDocument(Params.uuid(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{id}/file")
    public void file(@PathVariable String id, @RequestParam(required = false) String download, HttpServletRequest request,
                     HttpServletResponse response) throws IOException {
        Row d = store.requireRow(Params.uuid(id));
        boolean attachment = "1".equals(download) || "true".equals(download);
        FileResponder.send(request, response, store.files(d).file(), FILE_TYPES.getOrDefault(d.str("kind"), "application/octet-stream"),
                d.str("original_name"), attachment, "no-cache");
    }

    @GetMapping("/documents/{id}/cover")
    public void cover(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Row d = store.requireRow(Params.uuid(id));
        FileResponder.send(request, response, store.files(d).cover(), "image/jpeg", null, false, "private, max-age=31536000, immutable");
    }

    @GetMapping("/documents/{id}/reader")
    public Map<String, Object> reader(@PathVariable String id) throws IOException {
        Row d = store.requireRow(Params.uuid(id));
        Path file = store.files(d).reader();
        if (!d.bool("has_reader_html") || !Files.exists(file)) {
            throw ApiException.notFound("Este documento não tem versão em HTML.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("html", Files.readString(file, StandardCharsets.UTF_8));
        out.put("outline", Json.parse(d.str("outline"), List.of()));
        return out;
    }

    @GetMapping("/documents/{id}/media/{name}")
    public void media(@PathVariable String id, @PathVariable String name, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Row d = store.requireRow(Params.uuid(id));
        if (!MEDIA_NAME.matcher(name).matches()) {
            throw ApiException.notFound("Não encontrado.");
        }
        Path folder = store.files(d).media().toAbsolutePath().normalize();
        Path file = folder.resolve(name).normalize();
        if (!file.startsWith(folder) || file.equals(folder)) {
            throw ApiException.notFound("Não encontrado.");
        }
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        FileResponder.send(request, response, file, MEDIA_TYPES.getOrDefault(extension, "application/octet-stream"), null, false,
                "private, max-age=86400");
    }

    @PostMapping("/documents/{id}/reprocess")
    public Map<String, Object> reprocess(@PathVariable String id) {
        Row d = store.requireRow(Params.uuid(id));
        String status = d.str("status");
        if ("extracting".equals(status) || "describing".equals(status)) {
            throw ApiException.conflict("O documento já está sendo processado.");
        }
        pipeline.requeue(d.id(), "extract");
        return library.getDocument(d.id());
    }

    @PostMapping("/documents/{id}/describe")
    public Map<String, Object> describe(@PathVariable String id) {
        Row d = store.requireRow(Params.uuid(id));
        if (!"ready".equals(d.str("status"))) {
            throw ApiException.conflict("Espere o documento terminar de processar.");
        }
        pipeline.requeue(d.id(), "describe");
        return library.getDocument(d.id());
    }

    @PutMapping("/documents/{id}/tags")
    public Map<String, Object> tags(@PathVariable String id, @RequestBody(required = false) String raw) {
        String docId = Params.uuid(id);
        Map<String, Object> body = Params.body(raw);
        library.setTags(docId, body.get("tags") == null ? List.of() : body.get("tags"), "usuario");
        store.emit(store.requireRow(docId), "document.updated");
        return library.getDocument(docId);
    }

    @GetMapping("/documents/{id}/reading")
    public Map<String, Object> getReading(@PathVariable String id) {
        return reading.getReading(Params.uuid(id));
    }

    @RequestMapping(path = "/documents/{id}/reading", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> updateReading(@PathVariable String id, @RequestBody(required = false) String raw) {
        return reading.updateReading(Params.uuid(id), Params.body(raw));
    }

    @PostMapping("/documents/{id}/open")
    public Map<String, Object> open(@PathVariable String id) {
        return reading.openDocument(Params.uuid(id));
    }

    /* ---------- Anotações ---------- */

    @GetMapping("/documents/{id}/annotations")
    public List<Map<String, Object>> listAnnotations(@PathVariable String id) {
        return annotations.listAnnotations(Params.uuid(id));
    }

    @PostMapping("/documents/{id}/annotations")
    public ResponseEntity<Map<String, Object>> createAnnotation(@PathVariable String id, @RequestBody(required = false) String raw) {
        return ResponseEntity.status(201).body(annotations.createAnnotation(Params.uuid(id), Params.body(raw)));
    }

    @GetMapping("/documents/{id}/annotations/export")
    public Map<String, Object> exportAnnotations(@PathVariable String id) {
        return annotations.annotationsMarkdown(Params.uuid(id));
    }

    @RequestMapping(path = "/annotations/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> updateAnnotation(@PathVariable String id, @RequestBody(required = false) String raw) {
        return annotations.updateAnnotation(Params.uuid(id), Params.body(raw));
    }

    @DeleteMapping("/annotations/{id}")
    public ResponseEntity<Void> deleteAnnotation(@PathVariable String id) {
        annotations.deleteAnnotation(Params.uuid(id));
        return ResponseEntity.noContent().build();
    }
}
