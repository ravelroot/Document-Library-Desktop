package io.github.ravelroot.lume.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.ImportRejected;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.docx.DocxPackage;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.pipeline.PipelineService;

/**
 * Regras da biblioteca: importação de arquivos (com deduplicação por SHA-256), metadados, etiquetas e o painel.
 * A busca fica no {@link SearchService}; leitura, anotações e coleções nos serviços próprios.
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    public static final int MAX_FILE_MB = 500;
    private static final byte[] OLE_MAGIC = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private static final Pattern DOCUMENT_FILE = Pattern.compile("\\.(pdf|docx)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Database db;
    private final DocumentStore store;
    private final DocumentSerializer serializer;
    private final SearchService search;
    private final CollectionService collections;
    private final EventBus events;
    private final ObjectProvider<PipelineService> pipeline;

    public LibraryService(Database db, DocumentStore store, DocumentSerializer serializer, SearchService search,
                          CollectionService collections, EventBus events, ObjectProvider<PipelineService> pipeline) {
        this.db = db;
        this.store = store;
        this.serializer = serializer;
        this.search = search;
        this.collections = collections;
        this.events = events;
        this.pipeline = pipeline;
    }

    /** Resultado de uma importação: o documento e se ele já estava na biblioteca. */
    public record ImportResult(Map<String, Object> document, boolean duplicate) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("document", document);
            out.put("duplicate", duplicate);
            return out;
        }
    }

    private record Copy(Path tmp, String sha256, long size) {
    }

    /* ---------- Importação ---------- */

    /** Descobre o tipo pelo conteúdo (não pela extensão) e recusa .doc antigo, zip que não é Word e o resto. */
    public String detectKind(Path file, String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(1024);
        } catch (IOException e) {
            throw new ImportRejected("Não foi possível ler o arquivo (" + e.getMessage() + ").");
        }
        if (ext.equals(".doc") || (head.length >= 8 && Arrays.equals(Arrays.copyOf(head, 8), OLE_MAGIC))) {
            throw new ImportRejected("Arquivos .doc antigos não são suportados. Abra no Word e salve como .docx.");
        }
        if (new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
            return "pdf";
        }
        if (head.length >= 4 && head[0] == 0x50 && head[1] == 0x4b && head[2] == 0x03 && head[3] == 0x04) {
            List<String> entries;
            try {
                entries = DocxPackage.entryNames(file);
            } catch (IOException | RuntimeException e) {
                throw new ImportRejected("O arquivo .docx está corrompido.");
            }
            if (entries.contains("word/document.xml")) {
                return "docx";
            }
            throw new ImportRejected("Este arquivo compactado não é um documento do Word (.docx).");
        }
        if (ext.equals(".pdf") || ext.equals(".docx")) {
            throw new ImportRejected("O conteúdo não corresponde a um arquivo " + ext + ". Ele pode estar corrompido.");
        }
        throw new ImportRejected("Formato não suportado. Use arquivos PDF ou DOCX.");
    }

    /** Copia para a pasta temporária calculando o SHA-256 no caminho. */
    private Copy copyWithHash(InputStream source) {
        Path target = store.tmpDir().resolve(Ids.uuid() + ".part");
        long limit = MAX_FILE_MB * 1024L * 1024L;
        long size = 0;
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (source) {
            Files.createDirectories(store.tmpDir());
            try (OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = source.read(buffer)) > 0) {
                    size += n;
                    if (size > limit) {
                        throw new ImportRejected("O arquivo passa do limite de " + MAX_FILE_MB + " MB.");
                    }
                    hash.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
        } catch (ImportRejected e) {
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            deleteQuietly(target);
            String reason = e instanceof NoSuchFileException ? "O arquivo não existe mais nesse lugar."
                    : e instanceof AccessDeniedException ? "Não foi possível ler o arquivo (acesso negado)."
                    : "Não foi possível ler o arquivo (" + e.getMessage() + ").";
            throw new ImportRejected(reason);
        }
        if (size == 0) {
            deleteQuietly(target);
            throw new ImportRejected("O arquivo está vazio.");
        }
        return new Copy(target, HexFormat.of().formatHex(hash.digest()), size);
    }

    /** Importa um arquivo do disco pelo caminho (arrastar e soltar, seletor, "Abrir com" no Explorador). */
    public ImportResult importFile(Path source, String collection) {
        Path fileName = source == null ? null : source.getFileName();
        String filename = fileName == null ? "" : fileName.toString();
        if (filename.isEmpty()) {
            throw new ImportRejected("Arquivo inválido.");
        }
        if (!Files.exists(source)) {
            throw new ImportRejected("O arquivo não foi encontrado.");
        }
        if (!Files.isRegularFile(source)) {
            throw new ImportRejected("Isso não é um arquivo.");
        }
        if (collection != null) {
            collections.requireCollection(collection);
        }
        InputStream in;
        try {
            in = Files.newInputStream(source);
        } catch (IOException e) {
            throw new ImportRejected("Não foi possível ler o arquivo (" + e.getMessage() + ").");
        }
        return finishImport(copyWithHash(in), filename, source.toAbsolutePath().toString(), collection);
    }

    /** Importa um arquivo enviado pela janela (bytes), quando o caminho no disco não é conhecido. */
    public ImportResult importStream(InputStream in, String filename, String collection) {
        String name = Texts.singleLine(filename, 255);
        if (name.isEmpty()) {
            throw new ImportRejected("Arquivo inválido.");
        }
        if (collection != null) {
            collections.requireCollection(collection);
        }
        return finishImport(copyWithHash(in), name, "", collection);
    }

    private ImportResult finishImport(Copy copy, String filename, String sourcePath, String collection) {
        try {
            String kind = detectKind(copy.tmp(), filename);
            Row existing = db.one("SELECT * FROM documents WHERE sha256 = ?", copy.sha256());
            if (existing != null) {
                if (collection != null) {
                    collections.addToCollection(collection, List.of(existing.id()));
                }
                return new ImportResult(serializer.serializeOne(existing, false), true);
            }
            String id = Ids.uuid();
            String stamp = Times.now();
            String title = Texts.titleFromFilename(filename);
            String finalTitle = title.length() > 300 ? title.substring(0, 300) : title;
            try {
                db.transaction(() -> {
                    db.update("INSERT INTO documents (id, title, title_sort, kind, original_name, source_path, size_bytes, sha256, status, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?)",
                            id, finalTitle, Texts.fold(finalTitle), kind, limit(filename, 255), limit(sourcePath, 1000), copy.size(),
                            copy.sha256(), stamp, stamp);
                    try {
                        Path folder = store.folderOf(id);
                        Files.createDirectories(folder);
                        Files.move(copy.tmp(), folder.resolve("original." + kind), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new IllegalStateException("Não foi possível guardar o arquivo na biblioteca: " + e.getMessage(), e);
                    }
                    if (collection != null) {
                        collections.addToCollection(collection, List.of(id));
                    }
                    search.refreshSearch(id);
                });
            } catch (RuntimeException e) {
                deleteTree(store.folderOf(id));
                Row twin = db.one("SELECT * FROM documents WHERE sha256 = ?", copy.sha256());
                if (twin != null) {
                    return new ImportResult(serializer.serializeOne(twin, false), true); // o mesmo arquivo chegou duas vezes
                }
                throw e;
            }
            Row created = store.row(id);
            store.emit(created, "document.created");
            PipelineService queue = pipeline.getIfAvailable();
            if (queue != null) {
                queue.enqueue("extract", id);
            }
            return new ImportResult(serializer.serializeOne(created, false), false);
        } finally {
            deleteQuietly(copy.tmp());
        }
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Lista os PDFs e DOCX de uma pasta (e subpastas), ignorando arquivos ocultos e temporários do Office. */
    public List<Path> scanFolder(Path folder, int limit) {
        List<Path> found = new ArrayList<>();
        visit(folder, 0, limit, found);
        return found;
    }

    public List<Path> scanFolder(Path folder) {
        return scanFolder(folder, 2000);
    }

    private void visit(Path dir, int depth, int limit, List<Path> found) {
        if (depth > 12 || found.size() >= limit) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (IOException | RuntimeException e) {
            return;
        }
        entries.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        for (Path entry : entries) {
            if (found.size() >= limit) {
                return;
            }
            String name = entry.getFileName().toString();
            if (name.startsWith(".") || name.startsWith("~$")) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                visit(entry, depth + 1, limit, found);
            } else if (Files.isRegularFile(entry) && DOCUMENT_FILE.matcher(name).find()) {
                found.add(entry);
            }
        }
    }

    /* ---------- Documentos ---------- */

    public Map<String, Object> getDocument(String id) {
        return serializer.serializeOne(store.requireRow(id), true);
    }

    public Map<String, Object> updateDocument(String id, Map<String, Object> body) {
        Row d = store.requireRow(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (body.containsKey("title")) {
            String title = Texts.singleLine(body.get("title"), 300);
            if (title.isEmpty()) {
                throw ApiException.field("title", "O título não pode ficar vazio.");
            }
            if (!title.equals(d.str("title"))) {
                changes.put("title", title);
            }
        }
        if (body.containsKey("author")) {
            String author = Texts.singleLine(body.get("author"), 200);
            if (!author.equals(d.str("author"))) {
                changes.put("author", author);
            }
        }
        if (body.containsKey("description")) {
            String description = Texts.trim(Values.truthy(body.get("description")) ? Values.text(body.get("description")) : "");
            description = limit(description, 5000);
            if (!description.equals(d.str("description"))) {
                changes.put("description", description);
            }
        }
        if (!changes.isEmpty()) {
            if (changes.containsKey("title") || changes.containsKey("author")) {
                changes.put("metadata_locked", 1);
            }
            if (changes.containsKey("title")) {
                changes.put("title_sort", Texts.fold(changes.get("title")));
            }
            if (changes.containsKey("author")) {
                changes.put("author_sort", Texts.fold(changes.get("author")));
            }
            changes.put("updated_at", Times.now());
            db.transaction(() -> {
                StringBuilder sql = new StringBuilder("UPDATE documents SET ");
                sql.append(String.join(", ", changes.keySet().stream().map(k -> k + " = ?").toList()));
                sql.append(" WHERE id = ?");
                List<Object> args = new ArrayList<>(changes.values());
                args.add(id);
                db.update(sql.toString(), args.toArray());
                search.refreshSearch(id);
            });
            store.emit(store.row(id), "document.updated");
        }
        return getDocument(id);
    }

    public void deleteDocument(String id) {
        Row d = store.requireRow(id);
        db.transaction(() -> {
            db.update("DELETE FROM documents WHERE id = ?", id);
            db.update("DELETE FROM documents_fts WHERE document_id = ?", id);
            db.update("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM document_tags)");
        });
        Path folder = store.folderOf(d.id()).toAbsolutePath().normalize();
        if (folder.startsWith(store.documentsDir()) && !folder.equals(store.documentsDir())) {
            deleteTree(folder);
        }
        events.emit("document.deleted", Map.of("id", id));
    }

    /* ---------- Etiquetas ---------- */

    /** Substitui as etiquetas de uma origem, mantendo as da outra (a IA não apaga o que a pessoa escolheu). */
    public void setTags(String id, Object names, String source) {
        store.requireRow(id);
        if (!(names instanceof List<?> list) || list.size() > 12) {
            throw new ApiException("Envie até 12 etiquetas.");
        }
        Map<String, String> clean = new LinkedHashMap<>(); // slug → nome
        for (Object raw : list) {
            String name = Texts.trim(SPACES.matcher(Values.text(raw)).replaceAll(" ")).toLowerCase(Locale.ROOT);
            name = limit(name, 40);
            String slug = Texts.slugify(name);
            if (!name.isEmpty() && !slug.isEmpty()) {
                clean.putIfAbsent(slug, name);
            }
        }
        db.transaction(() -> {
            if ("usuario".equals(source)) {
                db.update("DELETE FROM document_tags WHERE document_id = ?", id);
            } else {
                db.update("DELETE FROM document_tags WHERE document_id = ? AND source = ?", id, source);
            }
            List<String> existing = db.query("SELECT t.slug FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = ?", id)
                    .stream().map(r -> r.str("slug")).toList();
            String stamp = Times.now();
            int added = 0;
            for (Map.Entry<String, String> tag : clean.entrySet()) {
                if (added++ >= 12) {
                    break;
                }
                if (existing.contains(tag.getKey())) {
                    continue;
                }
                db.update("INSERT INTO tags (name, slug, created_at) VALUES (?, ?, ?) ON CONFLICT (slug) DO NOTHING",
                        tag.getValue(), tag.getKey(), stamp);
                long tagId = db.scalar("SELECT id FROM tags WHERE slug = ?", tag.getKey());
                db.update("INSERT OR IGNORE INTO document_tags (document_id, tag_id, source, created_at) VALUES (?, ?, ?, ?)",
                        id, tagId, source, stamp);
            }
            db.update("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM document_tags)");
            search.refreshSearch(id);
        });
    }

    /** Soma etiquetas da pessoa às que o documento já tem (as da IA ficam), até 12 por documento. Devolve quantas entraram. */
    public int addTags(String id, Object names) {
        store.requireRow(id);
        if (!(names instanceof List<?> list) || list.isEmpty() || list.size() > 12) {
            throw new ApiException("Envie de 1 a 12 etiquetas.");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Object raw : list) {
            String name = limit(Texts.trim(SPACES.matcher(Values.text(raw)).replaceAll(" ")).toLowerCase(Locale.ROOT), 40);
            String slug = Texts.slugify(name);
            if (!name.isEmpty() && !slug.isEmpty()) {
                clean.putIfAbsent(slug, name);
            }
        }
        if (clean.isEmpty()) {
            throw new ApiException("Informe o nome da etiqueta.");
        }
        int added = db.transaction(() -> {
            List<String> existing = db.query("SELECT t.slug FROM document_tags dt JOIN tags t ON t.id = dt.tag_id WHERE dt.document_id = ?", id)
                    .stream().map(r -> r.str("slug")).toList();
            String stamp = Times.now();
            int count = 0;
            for (Map.Entry<String, String> tag : clean.entrySet()) {
                if (existing.contains(tag.getKey()) || existing.size() + count >= 12) {
                    continue;
                }
                db.update("INSERT INTO tags (name, slug, created_at) VALUES (?, ?, ?) ON CONFLICT (slug) DO NOTHING",
                        tag.getValue(), tag.getKey(), stamp);
                long tagId = db.scalar("SELECT id FROM tags WHERE slug = ?", tag.getKey());
                count += db.update("INSERT OR IGNORE INTO document_tags (document_id, tag_id, source, created_at) VALUES (?, ?, 'usuario', ?)",
                        id, tagId, stamp);
            }
            search.refreshSearch(id);
            return count;
        });
        if (added > 0) {
            store.emitUpdated(id);
        }
        return added;
    }

    public List<Map<String, Object>> listTags() {
        return db.query("SELECT t.name, t.slug, count(dt.document_id) AS count FROM tags t JOIN document_tags dt ON dt.tag_id = t.id "
                + "GROUP BY t.id ORDER BY count DESC, t.name LIMIT 200").stream().map(r -> {
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("name", r.str("name"));
                    tag.put("slug", r.str("slug"));
                    tag.put("count", r.lng("count"));
                    return tag;
                }).toList();
    }

    /* ---------- Painel ---------- */

    public Map<String, Object> stats() {
        Row counts = db.one("SELECT count(*) AS total, sum(kind = 'pdf') AS pdf, sum(kind = 'docx') AS docx, "
                + "sum(status IN ('queued', 'extracting', 'describing')) AS processing, sum(status = 'failed') AS failed FROM documents");
        Map<String, Object> shelves = new LinkedHashMap<>();
        SearchService.SHELVES.forEach(shelf -> shelves.put(shelf, 0L));
        for (Row r : db.query("SELECT shelf, count(*) AS n FROM reading_states WHERE shelf != '' GROUP BY shelf")) {
            shelves.put(r.str("shelf"), r.lng("n"));
        }
        long favorites = db.scalar("SELECT count(*) AS n FROM reading_states WHERE favorite = 1");
        List<Map<String, Object>> recent = db.query("SELECT d.id, d.title, d.kind, d.page_count, d.cover_color, d.cover_version, rs.page, "
                + "rs.progress FROM reading_states rs JOIN documents d ON d.id = rs.document_id "
                + "WHERE rs.last_opened_at IS NOT NULL AND rs.shelf != 'lido' ORDER BY rs.last_opened_at DESC LIMIT 4").stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", r.id());
                    item.put("title", r.str("title"));
                    item.put("kind", r.str("kind"));
                    item.put("page", r.integer("page"));
                    item.put("page_count", r.integer("page_count"));
                    item.put("progress", r.dbl("progress"));
                    item.put("cover_url", DocumentSerializer.coverUrl(r));
                    item.put("cover_color", r.str("cover_color"));
                    return item;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", counts.lng("total", 0));
        out.put("pdf", counts.lng("pdf", 0));
        out.put("docx", counts.lng("docx", 0));
        out.put("processing", counts.lng("processing", 0));
        out.put("failed", counts.lng("failed", 0));
        out.put("favorites", favorites);
        out.put("shelves", shelves);
        out.put("continue_reading", recent);
        return out;
    }

    /* ---------- Arquivos ---------- */

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Não foi possível apagar {}: {}", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Temporário não apagado: {}", e.getMessage());
        }
    }
}
