package io.github.ravelroot.lume.docx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.core.ProcessingRejected;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.processing.ExtractionResult;

/** Lê um DOCX sem programas externos: metadados, texto, sumário, HTML de leitura e imagens. */
@Component
public class DocxExtractor {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
    private static final int WORDS_PER_PAGE = 350;

    private static String mainDocumentPart(DocxPackage pkg) {
        Relationships root = Relationships.forPart(pkg, "");
        Relationships.Rel main = root.firstOfType(Relationships.OFFICE_DOCUMENT);
        String part = main != null ? DocxPackage.resolve("", main.target()) : "word/document.xml";
        if (part == null || part.isEmpty() || !pkg.has(part)) {
            throw new ProcessingRejected("O arquivo não tem o corpo de um documento do Word (word/document.xml).");
        }
        return part;
    }

    public ExtractionResult extract(Path file, Path outputDir) {
        return extract(file, outputDir, 2_000_000);
    }

    /**
     * @param file      o .docx
     * @param outputDir pasta do documento (recebe media/)
     * @param maxChars  limite do texto guardado para a busca
     */
    public ExtractionResult extract(Path file, Path outputDir, int maxChars) {
        long started = System.currentTimeMillis();
        ExtractionResult result = new ExtractionResult("docx");
        try (DocxPackage pkg = DocxPackage.open(file)) {
            String mainPart = mainDocumentPart(pkg);
            Relationships relationships = Relationships.forPart(pkg, mainPart);
            Styles styles = Styles.read(pkg, relationships.partOf(mainPart, Relationships.STYLES, "word/styles.xml"));
            Numbering numbering = Numbering.read(pkg, relationships.partOf(mainPart, Relationships.NUMBERING, "word/numbering.xml"));
            DocProperties props = DocProperties.read(pkg);
            DocumentParser parser = new DocumentParser(styles, relationships).parse(pkg.xml(mainPart));

            Path mediaDir = outputDir.resolve("media");
            deleteTree(mediaDir);
            MediaWriter media = new MediaWriter(pkg, mainPart, relationships, mediaDir, result);
            HtmlWriter writer = new HtmlWriter(styles, numbering, media::write).write(parser.body());

            Texts.FinishedText text = Texts.finish(writer.text(), maxChars);
            int pageCount = props.pages > 0 ? props.pages : Math.max(1, (int) Math.ceil(text.wordCount() / (double) WORDS_PER_PAGE));
            if (Texts.trim(text.text()).isEmpty()) {
                result.warn("O documento não tem texto.");
            }
            result.pageCount(pageCount)
                    .text(text.text())
                    .textTruncated(text.truncated())
                    .charCount(text.charCount())
                    .wordCount(text.wordCount())
                    .title(first(props.title, writer.titleText(), writer.firstHeading()))
                    .author(props.creator)
                    .subject(props.subject)
                    .keywords(props.keywords)
                    .createdAt(props.created)
                    .modifiedAt(props.modified)
                    .creator(props.application)
                    .producer(null)
                    .language(first(props.language, styles.language()))
                    .outline(writer.outline())
                    .pageSize(parser.pageWidth(), parser.pageHeight())
                    .encrypted(false)
                    .html(writer.html())
                    .media(media.written);
        }
        return result.elapsedMs(System.currentTimeMillis() - started);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !Texts.trim(value).isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Copia as imagens referenciadas para media/ com nomes seguros e sem repetir a mesma parte. */
    private static final class MediaWriter {
        private final DocxPackage pkg;
        private final String mainPart;
        private final Relationships relationships;
        private final Path mediaDir;
        private final ExtractionResult result;
        private final Map<String, String> byPart = new HashMap<>();
        private final Set<String> usedNames = new HashSet<>();
        private final List<String> written = new ArrayList<>();

        MediaWriter(DocxPackage pkg, String mainPart, Relationships relationships, Path mediaDir, ExtractionResult result) {
            this.pkg = pkg;
            this.mainPart = mainPart;
            this.relationships = relationships;
            this.mediaDir = mediaDir;
            this.result = result;
        }

        String write(String relationshipId) {
            Relationships.Rel rel = relationships.get(relationshipId);
            if (rel == null) {
                return null;
            }
            if (rel.external()) {
                result.warn("Imagens vinculadas a endereços externos não são carregadas.");
                return null;
            }
            String part = DocxPackage.resolve(mainPart, rel.target());
            if (part == null || part.isEmpty() || !pkg.has(part)) {
                return null;
            }
            if (byPart.containsKey(part)) {
                return byPart.get(part);
            }
            String base = part.substring(part.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            String extension = dot >= 0 ? base.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            if (!IMAGE_EXTENSIONS.contains(extension)) {
                result.warn("Imagens em formato " + (extension.isEmpty() ? "desconhecido" : extension.toUpperCase(Locale.ROOT))
                        + " não podem ser exibidas e foram omitidas.");
                return null;
            }
            String stem = (dot >= 0 ? base.substring(0, dot) : base).replaceAll("[^A-Za-z0-9_-]", "_");
            if (stem.isEmpty()) {
                stem = "imagem";
            }
            String name = stem + "." + extension;
            for (int i = 2; usedNames.contains(name); i++) {
                name = stem + "-" + i + "." + extension;
            }
            usedNames.add(name);
            try {
                Files.createDirectories(mediaDir);
                Files.write(mediaDir.resolve(name), pkg.media(part));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String src = "media/" + name;
            byPart.put(part, src);
            written.add(src);
            return src;
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
