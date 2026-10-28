package io.github.ravelroot.lume.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.AppInfo;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.SearchService;
import io.github.ravelroot.lume.pipeline.PipelineService;

/**
 * Ferramentas da biblioteca: backup completo em .zip, exportação do catálogo (CSV, JSON, BibTeX, Markdown), exportação
 * de notas para uma pasta de Markdown (Obsidian, Logseq, Notion) e manutenção do banco e da pasta de dados.
 */
@Service
public class ToolsService {

    private static final Logger log = LoggerFactory.getLogger(ToolsService.class);

    public static final List<String> FORMATS = List.of("csv", "json", "bibtex", "markdown");
    private static final Map<String, String> SHELF_LABELS = Map.of("para_ler", "Quero ler", "lendo", "Lendo", "lido", "Lido");
    private static final Map<String, String> COLOR_LABELS = Map.of("salvia", "sálvia", "rose", "rosé", "ardosia", "ardósia", "camurca", "camurça");
    private static final Pattern BAD_FILENAME = Pattern.compile("[\\\\/:*?\"<>|#^\\[\\]]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final DateTimeFormatter BRAZIL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final long TEMP_GRACE_MS = 10 * 60 * 1000L;

    private final Database db;
    private final DocumentStore store;
    private final SearchService search;
    private final ObjectProvider<PipelineService> pipeline;
    private final AppInfo appInfo;

    public ToolsService(Database db, DocumentStore store, SearchService search, ObjectProvider<PipelineService> pipeline, AppInfo appInfo) {
        this.db = db;
        this.store = store;
        this.search = search;
        this.pipeline = pipeline;
        this.appInfo = appInfo;
    }

    static String today() {
        return LocalDate.now().toString();
    }

    /* ---------- Catálogo ---------- */

    /** Uma linha por documento com tudo o que vai para os formatos de exportação. */
    List<Map<String, Object>> catalog(List<String> onlyIds) {
        List<Row> rows;
        if (onlyIds == null || onlyIds.isEmpty()) {
            rows = db.query("SELECT d.*, rs.shelf, rs.favorite, rs.progress, rs.page AS read_page, rs.finished_at FROM documents d "
                    + "LEFT JOIN reading_states rs ON rs.document_id = d.id ORDER BY d.title_sort");
        } else {
            rows = db.query("SELECT d.*, rs.shelf, rs.favorite, rs.progress, rs.page AS read_page, rs.finished_at FROM documents d "
                    + "LEFT JOIN reading_states rs ON rs.document_id = d.id WHERE d.id IN (" + String.join(",", onlyIds.stream().map(i -> "?").toList())
                    + ") ORDER BY d.title_sort", onlyIds.toArray());
        }
        Map<String, List<String>> tags = new HashMap<>();
        for (Row r : db.query("SELECT dt.document_id, t.name FROM document_tags dt JOIN tags t ON t.id = dt.tag_id ORDER BY t.name")) {
            tags.computeIfAbsent(r.str("document_id"), k -> new ArrayList<>()).add(r.str("name"));
        }
        Map<String, List<String>> collections = new HashMap<>();
        for (Row r : db.query("SELECT ci.document_id, c.name FROM collection_items ci JOIN collections c ON c.id = ci.collection_id ORDER BY lower(c.name)")) {
            collections.computeIfAbsent(r.str("document_id"), k -> new ArrayList<>()).add(r.str("name"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row d : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            String shelf = d.text("shelf");
            double progress = d.dbl("progress") == null ? 0 : d.dbl("progress");
            Integer pages = d.integer("page_count");
            if ("pdf".equals(d.str("kind")) && pages != null && pages > 0 && d.integer("read_page") != null && d.str("shelf") != null) {
                progress = Math.max(progress, d.integer("read_page") / (double) pages);
            }
            if ("lido".equals(shelf)) {
                progress = 1;
            }
            item.put("id", d.id());
            item.put("title", d.str("title"));
            item.put("author", d.text("author"));
            item.put("kind", d.str("kind"));
            item.put("pages", pages);
            item.put("words", d.integer("word_count"));
            item.put("size_bytes", d.lng("size_bytes"));
            item.put("shelf", shelf);
            item.put("favorite", d.bool("favorite"));
            item.put("progress_pct", (int) Math.round(Math.min(1, progress) * 100));
            item.put("tags", tags.getOrDefault(d.id(), List.of()));
            item.put("collections", collections.getOrDefault(d.id(), List.of()));
            item.put("language", d.text("language"));
            item.put("added_at", d.str("created_at"));
            item.put("finished_at", d.str("finished_at"));
            item.put("original_name", d.str("original_name"));
            item.put("description", !d.text("description").isEmpty() ? d.text("description") : d.text("summary"));
            item.put("summary", d.text("summary"));
            item.put("metadata", Json.parseObject(d.str("metadata")));
            out.add(item);
        }
        return out;
    }

    private static String date(Object iso) {
        String value = iso == null ? "" : String.valueOf(iso);
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(";") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    static String toCsv(List<Map<String, Object>> items) {
        StringBuilder out = new StringBuilder("﻿");
        out.append(String.join(";", List.of("Título", "Autor", "Tipo", "Páginas", "Palavras", "Tamanho (bytes)", "Estante", "Favorito",
                "Progresso (%)", "Etiquetas", "Coleções", "Idioma", "Adicionado em", "Terminado em", "Arquivo original", "Descrição")));
        out.append("\r\n");
        for (Map<String, Object> d : items) {
            List<Object> cells = new ArrayList<>();
            cells.add(d.get("title"));
            cells.add(d.get("author"));
            cells.add(String.valueOf(d.get("kind")).toUpperCase(Locale.ROOT));
            cells.add(d.get("pages"));
            cells.add(d.get("words"));
            cells.add(d.get("size_bytes"));
            cells.add(SHELF_LABELS.getOrDefault(String.valueOf(d.get("shelf")), ""));
            cells.add(Boolean.TRUE.equals(d.get("favorite")) ? "sim" : "não");
            cells.add(d.get("progress_pct"));
            cells.add(String.join(" | ", (List<String>) d.get("tags")));
            cells.add(String.join(" | ", (List<String>) d.get("collections")));
            cells.add(d.get("language"));
            cells.add(date(d.get("added_at")));
            cells.add(date(d.get("finished_at")));
            cells.add(d.get("original_name"));
            cells.add(SPACES.matcher(String.valueOf(d.get("description"))).replaceAll(" "));
            out.append(String.join(";", cells.stream().map(ToolsService::csvCell).toList())).append("\r\n");
        }
        return out.toString();
    }

    static String toJson(List<Map<String, Object>> items) {
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> d : items) {
            Map<String, Object> copy = new LinkedHashMap<>(d);
            copy.remove("metadata");
            clean.add(copy);
        }
        return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(clean);
    }

    private static String bibValue(Object value) {
        String text = value == null ? "" : SPACES.matcher(String.valueOf(value)).replaceAll(" ").trim();
        return text.replace("\\", "\\textbackslash{}").replace("{", "\\{").replace("}", "\\}").replace("&", "\\&")
                .replace("%", "\\%").replace("#", "\\#").replace("_", "\\_").replace("$", "\\$");
    }

    @SuppressWarnings("unchecked")
    static String toBibtex(List<Map<String, Object>> items) {
        StringBuilder out = new StringBuilder("% Catálogo exportado do Lume em " + LocalDate.now().format(BRAZIL_DATE) + "\n\n");
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> d : items) {
            String author = String.valueOf(d.get("author"));
            String year = year(d);
            String[] authorWords = Texts.slugify(author).split("-");
            String authorKey = author.isBlank() ? "lume" : authorWords[authorWords.length - 1];
            String titleKey = Texts.slugify(d.get("title")).split("-")[0];
            String base = (authorKey + year + titleKey).replaceAll("[^a-z0-9]", "");
            String key = base.isEmpty() ? "doc" : base;
            for (int i = 2; !keys.add(key); i++) {
                key = base + (char) ('a' + Math.min(25, i - 2));
                if (i > 27) {
                    key = base + i;
                }
            }
            out.append("@misc{").append(key).append(",\n");
            out.append("  title = {").append(bibValue(d.get("title"))).append("},\n");
            if (!author.isBlank()) {
                out.append("  author = {").append(bibValue(author)).append("},\n");
            }
            out.append("  year = {").append(year).append("},\n");
            List<String> tags = (List<String>) d.get("tags");
            if (!tags.isEmpty()) {
                out.append("  keywords = {").append(bibValue(String.join(", ", tags))).append("},\n");
            }
            if (!String.valueOf(d.get("language")).isEmpty()) {
                out.append("  language = {").append(bibValue(d.get("language"))).append("},\n");
            }
            if (!String.valueOf(d.get("summary")).isEmpty()) {
                out.append("  note = {").append(bibValue(d.get("summary"))).append("},\n");
            }
            out.append("  howpublished = {").append(String.valueOf(d.get("kind")).toUpperCase(Locale.ROOT)).append("},\n");
            out.append("  file = {").append(bibValue(d.get("original_name"))).append("}\n");
            out.append("}\n\n");
        }
        return out.toString();
    }

    /** Ano dos metadados do arquivo (data de criação), ou o ano em que entrou na biblioteca. */
    @SuppressWarnings("unchecked")
    private static String year(Map<String, Object> d) {
        if (d.get("metadata") instanceof Map<?, ?> meta) {
            for (String key : List.of("created", "creation_date", "createdAt", "date")) {
                Object value = ((Map<String, Object>) meta).get(key);
                if (value != null && String.valueOf(value).matches("^\\d{4}.*")) {
                    return String.valueOf(value).substring(0, 4);
                }
            }
        }
        String added = String.valueOf(d.get("added_at"));
        return added.length() >= 4 ? added.substring(0, 4) : String.valueOf(LocalDate.now().getYear());
    }

    @SuppressWarnings("unchecked")
    static String toMarkdown(List<Map<String, Object>> items) {
        StringBuilder out = new StringBuilder("# Catálogo do Lume\n\n");
        out.append(items.size()).append(items.size() == 1 ? " documento" : " documentos")
                .append(" · exportado em ").append(LocalDate.now().format(BRAZIL_DATE)).append("\n");
        Map<String, String> groups = new LinkedHashMap<>();
        groups.put("lendo", "Lendo");
        groups.put("para_ler", "Quero ler");
        groups.put("lido", "Lidos");
        groups.put("", "Sem estante");
        for (Map.Entry<String, String> group : groups.entrySet()) {
            List<Map<String, Object>> inGroup = items.stream().filter(d -> group.getKey().equals(String.valueOf(d.get("shelf")))).toList();
            if (inGroup.isEmpty()) {
                continue;
            }
            out.append("\n## ").append(group.getValue()).append(" (").append(inGroup.size()).append(")\n\n");
            for (Map<String, Object> d : inGroup) {
                out.append("- **").append(d.get("title")).append("**");
                if (!String.valueOf(d.get("author")).isEmpty()) {
                    out.append(" — ").append(d.get("author"));
                }
                List<String> facts = new ArrayList<>();
                facts.add(String.valueOf(d.get("kind")).toUpperCase(Locale.ROOT));
                if (d.get("pages") != null) {
                    facts.add(d.get("pages") + " págs.");
                }
                if (Boolean.TRUE.equals(d.get("favorite"))) {
                    facts.add("★ favorito");
                }
                out.append(" · ").append(String.join(" · ", facts)).append("\n");
                List<String> tags = (List<String>) d.get("tags");
                if (!tags.isEmpty()) {
                    out.append("  - Etiquetas: ").append(String.join(", ", tags)).append("\n");
                }
                String description = SPACES.matcher(String.valueOf(d.get("description"))).replaceAll(" ").trim();
                if (!description.isEmpty()) {
                    out.append("  - ").append(description).append("\n");
                }
            }
        }
        if (items.isEmpty()) {
            out.append("\n_Nenhum documento._\n");
        }
        return out.toString();
    }

    /** Conteúdo do catálogo no formato pedido: {filename, content, mime, count}. */
    public Map<String, Object> exportCatalog(String format, List<String> ids) {
        String fmt = format == null || format.isBlank() ? "csv" : format.toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(fmt)) {
            throw new ApiException("Formato inválido. Use csv, json, bibtex ou markdown.");
        }
        List<String> valid = ids == null ? List.of() : ids.stream().filter(Ids::isUuid).limit(5000).toList();
        List<Map<String, Object>> items = catalog(valid);
        String content;
        String extension;
        String mime;
        switch (fmt) {
            case "json" -> {
                content = toJson(items);
                extension = "json";
                mime = "application/json";
            }
            case "bibtex" -> {
                content = toBibtex(items);
                extension = "bib";
                mime = "application/x-bibtex";
            }
            case "markdown" -> {
                content = toMarkdown(items);
                extension = "md";
                mime = "text/markdown";
            }
            default -> {
                content = toCsv(items);
                extension = "csv";
                mime = "text/csv";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", "lume-catalogo-" + today() + "." + extension);
        out.put("content", content);
        out.put("mime", mime);
        out.put("count", items.size());
        return out;
    }

    /* ---------- Notas em Markdown (Obsidian, Logseq, Notion) ---------- */

    private static String yaml(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    static String safeFilename(String title) {
        String base = Texts.trim(SPACES.matcher(BAD_FILENAME.matcher(title == null ? "" : title).replaceAll(" ")).replaceAll(" "));
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        if (base.length() > 100) {
            base = base.substring(0, 100).trim();
        }
        return base.isEmpty() ? "Documento" : base;
    }

    /** Um arquivo .md por documento, com front matter YAML, a descrição e as anotações. Devolve quantos arquivos foram gravados. */
    @SuppressWarnings("unchecked")
    public int exportMarkdownFolder(Path folder) throws IOException {
        Files.createDirectories(folder);
        List<Map<String, Object>> items = catalog(List.of());
        Map<String, List<Row>> annotations = new HashMap<>();
        for (Row a : db.query("SELECT * FROM annotations ORDER BY coalesce(page, 0), created_at")) {
            annotations.computeIfAbsent(a.str("document_id"), k -> new ArrayList<>()).add(a);
        }
        Set<String> used = new HashSet<>();
        int written = 0;
        for (Map<String, Object> d : items) {
            String base = safeFilename(String.valueOf(d.get("title")));
            String name = base;
            for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
                name = base + " (" + i + ")";
            }
            List<String> lines = new ArrayList<>();
            lines.add("---");
            lines.add("title: " + yaml(d.get("title")));
            if (!String.valueOf(d.get("author")).isEmpty()) {
                lines.add("author: " + yaml(d.get("author")));
            }
            lines.add("kind: " + d.get("kind"));
            if (d.get("pages") != null) {
                lines.add("pages: " + d.get("pages"));
            }
            List<String> tags = (List<String>) d.get("tags");
            lines.add("tags: [" + String.join(", ", tags.stream().map(Texts::slugify).filter(s -> !s.isEmpty()).toList()) + "]");
            lines.add("shelf: " + yaml(SHELF_LABELS.getOrDefault(String.valueOf(d.get("shelf")), "")));
            lines.add("favorite: " + Boolean.TRUE.equals(d.get("favorite")));
            lines.add("progress: " + d.get("progress_pct"));
            List<String> collections = (List<String>) d.get("collections");
            lines.add("collections: [" + String.join(", ", collections.stream().map(ToolsService::yaml).toList()) + "]");
            lines.add("added: " + date(d.get("added_at")));
            if (d.get("finished_at") != null) {
                lines.add("finished: " + date(d.get("finished_at")));
            }
            lines.add("source: " + yaml(d.get("original_name")));
            lines.add("lume_id: " + d.get("id"));
            lines.add("---");
            lines.add("");
            lines.add("# " + d.get("title"));
            lines.add("");
            if (!String.valueOf(d.get("author")).isEmpty()) {
                lines.add("*" + d.get("author") + "*");
                lines.add("");
            }
            lines.add("## Descrição");
            lines.add("");
            String description = String.valueOf(d.get("description")).trim();
            lines.add(description.isEmpty() ? "_Sem descrição._" : description);
            lines.add("");
            lines.add("## Destaques e notas");
            lines.add("");
            List<Row> notes = annotations.getOrDefault(String.valueOf(d.get("id")), List.of());
            if (notes.isEmpty()) {
                lines.add("_Nenhuma anotação._");
                lines.add("");
            }
            Integer lastPage = -1;
            for (Row a : notes) {
                Integer page = a.integer("page");
                if (!java.util.Objects.equals(page, lastPage)) {
                    lines.add("### " + (page == null ? "Sem página" : "Página " + page));
                    lines.add("");
                    lastPage = page;
                }
                String quote = a.text("quote");
                String note = a.text("note");
                String kind = a.str("kind");
                if ("bookmark".equals(kind) && quote.isEmpty() && note.isEmpty()) {
                    lines.add("- 🔖 Marcador");
                    lines.add("");
                    continue;
                }
                if (!quote.isEmpty()) {
                    lines.add("> " + quote.replace("\n", "\n> "));
                    lines.add("");
                }
                if (!note.isEmpty()) {
                    lines.add(note);
                    lines.add("");
                }
                lines.add("<small>" + ("highlight".equals(kind) ? "Destaque" : "note".equals(kind) ? "Nota" : "Marcador")
                        + " · " + COLOR_LABELS.getOrDefault(a.str("color"), a.str("color")) + " · " + date(a.str("created_at")) + "</small>");
                lines.add("");
            }
            lines.add("---");
            lines.add("Exportado do Lume em " + LocalDate.now().format(BRAZIL_DATE) + ".");
            Path file;
            try {
                file = folder.resolve(name + ".md");
            } catch (java.nio.file.InvalidPathException e) {
                // Sistema sem suporte a acentos em nomes de arquivo: usa o nome sem acentos.
                String ascii = safeFilename(java.text.Normalizer.normalize(String.valueOf(d.get("title")), java.text.Normalizer.Form.NFD)
                        .replaceAll("[^\\x20-\\x7e]", ""));
                file = folder.resolve(ascii + " " + String.valueOf(d.get("id")).substring(0, 8) + ".md");
            }
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            written++;
        }
        return written;
    }

    /* ---------- Backup ---------- */

    /** Grava um .zip com o lume.db (cópia consistente com VACUUM INTO), a pasta documentos/ e um LEIAME. */
    public Map<String, Object> backupTo(Path target) throws IOException {
        Files.createDirectories(store.tmpDir());
        Path snapshot = store.tmpDir().resolve("backup-" + Ids.uuid() + ".db");
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            db.update("VACUUM INTO ?", snapshot.toAbsolutePath().toString());
            long documents = 0;
            try (OutputStream file = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
                zip.setLevel(Deflater.BEST_SPEED);
                zip.setComment("Backup do Lume " + appInfo.version() + " · " + Times.now());
                zip.putNextEntry(new ZipEntry("lume.db"));
                Files.copy(snapshot, zip);
                zip.closeEntry();
                Path documentsDir = store.documentsDir();
                if (Files.isDirectory(documentsDir)) {
                    Set<String> ids = new HashSet<>(db.query("SELECT id FROM documents").stream().map(Row::id).toList());
                    try (DirectoryStream<Path> folders = Files.newDirectoryStream(documentsDir)) {
                        for (Path folder : folders) {
                            if (!Files.isDirectory(folder) || !ids.contains(folder.getFileName().toString())) {
                                continue;
                            }
                            documents++;
                            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    String name = "documentos/" + documentsDir.relativize(file).toString().replace('\\', '/');
                                    zip.putNextEntry(new ZipEntry(name));
                                    Files.copy(file, zip);
                                    zip.closeEntry();
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        }
                    }
                }
                zip.putNextEntry(new ZipEntry("LEIAME.txt"));
                zip.write(readme(documents).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", target.toString());
            out.put("bytes", Files.size(target));
            out.put("documents", documents);
            return out;
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(partial);
        }
    }

    private String readme(long documents) {
        return String.join("\r\n",
                "Backup do Lume",
                "==============",
                "",
                "Criado em " + LocalDate.now().format(BRAZIL_DATE) + " pelo Lume " + appInfo.version() + ", com " + documents + " documentos.",
                "",
                "Conteúdo:",
                "  lume.db       banco da biblioteca (documentos, estantes, coleções, anotações e preferências)",
                "  documentos/   uma pasta por documento, com o arquivo original, a capa e a versão de leitura",
                "",
                "Como restaurar:",
                "  1. Feche o Lume (ícone da bandeja ou a janela).",
                "  2. Abra a pasta de dados: %APPDATA%\\Lume\\Biblioteca (ou Lume-dados\\Biblioteca no modo portátil).",
                "  3. Guarde uma cópia do que estiver lá, se quiser, e apague lume.db, lume.db-wal, lume.db-shm e a pasta documentos.",
                "  4. Extraia este arquivo .zip dentro da pasta de dados e abra o Lume de novo.",
                "",
                "A chave de API da IA é cifrada para esta conta do Windows; em outro computador, informe a chave de novo em Preferências.",
                "");
    }

    /* ---------- Manutenção ---------- */

    private static long sizeOf(Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (var walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private long databaseBytes() {
        Path file = db.file();
        return sizeOf(file) + sizeOf(file.resolveSibling(file.getFileName() + "-wal"));
    }

    private List<Path> orphanFolders() {
        List<Path> out = new ArrayList<>();
        Path dir = store.documentsDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        Set<String> ids = new HashSet<>(db.query("SELECT id FROM documents").stream().map(Row::id).toList());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!ids.contains(entry.getFileName().toString())) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Não foi possível ler {}: {}", dir, e.getMessage());
        }
        return out;
    }

    private List<Path> tempFiles(boolean onlyOld) {
        List<Path> out = new ArrayList<>();
        Path dir = store.tmpDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        long limit = System.currentTimeMillis() - TEMP_GRACE_MS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                try {
                    if (!onlyOld || Files.getLastModifiedTime(entry).toMillis() < limit) {
                        out.add(entry);
                    }
                } catch (IOException e) {
                    // arquivo sumiu no meio da varredura
                }
            }
        } catch (IOException e) {
            log.warn("Não foi possível ler {}: {}", dir, e.getMessage());
        }
        return out;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public Map<String, Object> maintenance(String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        switch (action == null ? "" : action) {
            case "check" -> {
                List<String> problems = db.query("PRAGMA integrity_check").stream()
                        .map(r -> String.valueOf(r.values().iterator().next())).toList();
                out.put("integrity", problems.size() == 1 && "ok".equals(problems.get(0)) ? "ok" : String.join("; ", problems));
                List<Map<String, Object>> missing = new ArrayList<>();
                List<Row> docs = db.query("SELECT id, title, kind FROM documents ORDER BY title_sort");
                for (Row d : docs) {
                    if (!Files.isRegularFile(store.files(d).file())) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", d.id());
                        item.put("title", d.str("title"));
                        missing.add(item);
                    }
                }
                List<Path> orphans = orphanFolders();
                out.put("documents", docs.size());
                out.put("missing_files", missing);
                out.put("orphan_folders", orphans.size());
                out.put("orphan_bytes", orphans.stream().mapToLong(ToolsService::sizeOf).sum());
                out.put("temp_files", tempFiles(false).size());
                out.put("database_bytes", databaseBytes());
            }
            case "reindex" -> {
                List<String> ids = db.query("SELECT id FROM documents").stream().map(Row::id).toList();
                ids.forEach(search::refreshSearch);
                db.execute("INSERT INTO documents_fts (documents_fts) VALUES ('optimize')");
                out.put("reindexed", ids.size());
            }
            case "optimize" -> {
                long before = databaseBytes();
                db.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                db.execute("VACUUM");
                db.execute("PRAGMA optimize");
                db.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                out.put("before_bytes", before);
                out.put("after_bytes", databaseBytes());
            }
            case "redescribe" -> {
                PipelineService queue = pipeline.getObject();
                List<String> ids = db.query("SELECT id FROM documents WHERE status = 'ready' ORDER BY created_at").stream().map(Row::id).toList();
                ids.forEach(id -> queue.requeue(id, "describe"));
                out.put("requeued", ids.size());
            }
            case "cleanup" -> {
                int removed = 0;
                long freed = 0;
                List<Path> targets = new ArrayList<>(orphanFolders());
                targets.addAll(tempFiles(true));
                for (Path path : targets) {
                    long size = sizeOf(path);
                    try {
                        deleteTree(path);
                        removed++;
                        freed += size;
                    } catch (IOException e) {
                        log.warn("Não foi possível apagar {}: {}", path, e.getMessage());
                    }
                }
                out.put("removed", removed);
                out.put("freed_bytes", freed);
            }
            default -> throw new ApiException("Ação de manutenção inválida. Use check, reindex, optimize, redescribe ou cleanup.");
        }
        return out;
    }

    /** Nome sugerido para o backup. */
    public static String backupName() {
        return "Lume-backup-" + today() + ".zip";
    }

    /** Quantos documentos guardados (usado no LEIAME e nos testes). */
    public long documentCount() {
        return db.scalar("SELECT count(*) FROM documents");
    }
}
