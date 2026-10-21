package io.github.ravelroot.lume.processing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import io.github.ravelroot.lume.core.ProcessingRejected;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.docx.XmlNode;
import io.github.ravelroot.lume.docx.XmlParser;

/**
 * Leitura de PDF com o Apache PDFBox: metadados, sumário (marcadores), texto completo com \f entre as páginas e a capa
 * da primeira página que não esteja em branco (entre as três primeiras).
 */
public final class PdfExtractor {

    private static final int MAX_OUTLINE_ITEMS = 2000;
    private static final int MAX_OUTLINE_DEPTH = 8;
    private static final int CANDIDATE_PAGES = 3;
    private static final long MEMORY_LIMIT = 64L * 1024 * 1024;

    private PdfExtractor() {
    }

    public static ExtractionResult extract(Path file, int maxChars, int coverWidth) throws IOException {
        long started = System.nanoTime();
        ExtractionResult result = new ExtractionResult("pdf");
        checkHeader(file);
        try (PDDocument pdf = open(file)) {
            int pages = pdf.getNumberOfPages();
            if (pages == 0) {
                throw new ProcessingRejected("O PDF não tem nenhuma página.");
            }
            result.encrypted(pdf.isEncrypted());
            if (pdf.isEncrypted()) {
                pdf.setAllSecurityToBeRemoved(true);
            }
            readMetadata(pdf, result);
            PDPage first = pdf.getPage(0);
            double[] size = pageSize(first);
            result.pageCount(pages).pageSize(round1(size[0]), round1(size[1]));
            result.outline(readOutline(pdf, result));
            readText(pdf, maxChars, result);
            if (Texts.trim(result.text().replace('\f', ' ')).isEmpty()) {
                result.warn("O PDF não tem texto selecionável (provavelmente é digitalizado).");
            }
            try {
                result.cover(renderCover(pdf, coverWidth));
            } catch (IOException | RuntimeException e) {
                result.warn("Não foi possível desenhar a capa; o Lume criou uma.");
            }
        }
        return result.elapsedMs((System.nanoTime() - started) / 1_000_000);
    }

    private static void checkHeader(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(1024);
        }
        if (!new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
            throw new ProcessingRejected("O arquivo não é um PDF (cabeçalho %PDF ausente).");
        }
    }

    private static PDDocument open(Path file) {
        try {
            return PDDocument.load(file.toFile(), MemoryUsageSetting.setupMixed(MEMORY_LIMIT));
        } catch (InvalidPasswordException e) {
            throw new ProcessingRejected("O PDF está protegido por senha e não pode ser aberto.", "encrypted");
        } catch (IOException | RuntimeException e) {
            throw new ProcessingRejected("Não foi possível ler o PDF: o arquivo está corrompido ou não é um PDF válido.");
        }
    }

    /* ---------- Metadados ---------- */

    private static String cleanTitle(String raw) {
        String title = Texts.singleLineOrNull(raw);
        if (title == null) {
            return null;
        }
        title = title.replaceFirst("^Microsoft (Word|PowerPoint|Excel) - ", "");
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.equals("untitled") || lower.equals("sem título") || lower.equals("documento1")) {
            return null;
        }
        return title.isEmpty() ? null : title;
    }

    private static void readMetadata(PDDocument pdf, ExtractionResult result) {
        PDDocumentInformation info = pdf.getDocumentInformation();
        PDDocumentCatalog catalog = pdf.getDocumentCatalog();
        String[] xmp = readXmp(catalog);
        String title = cleanTitle(info.getTitle());
        String author = Texts.singleLineOrNull(info.getAuthor());
        result.title(title != null ? title : cleanTitle(xmp[0]))
                .author(author != null ? author : Texts.singleLineOrNull(xmp[1]))
                .subject(Texts.singleLineOrNull(info.getSubject()))
                .keywords(Texts.singleLineOrNull(info.getKeywords()))
                .creator(Texts.singleLineOrNull(info.getCreator()))
                .producer(Texts.singleLineOrNull(info.getProducer()));
        try {
            result.createdAt(Times.isoSeconds(info.getCreationDate()));
        } catch (RuntimeException ignored) {
            // data malformada: fica sem
        }
        try {
            result.modifiedAt(Times.isoSeconds(info.getModificationDate()));
        } catch (RuntimeException ignored) {
            // data malformada: fica sem
        }
        String language = Texts.singleLineOrNull(catalog.getLanguage());
        result.language(language == null ? "" : language);
    }

    /** dc:title e dc:creator do XMP (quando o dicionário Info não tem). */
    private static String[] readXmp(PDDocumentCatalog catalog) {
        String[] out = {null, null};
        try {
            PDMetadata metadata = catalog.getMetadata();
            if (metadata == null) {
                return out;
            }
            XmlNode root = XmlParser.parse(metadata.toByteArray());
            XmlNode title = root.find("title");
            XmlNode creator = root.find("creator");
            out[0] = title == null ? null : firstItem(title);
            out[1] = creator == null ? null : firstItem(creator);
        } catch (IOException | RuntimeException ignored) {
            // XMP ausente ou inválido não impede a leitura
        }
        return out;
    }

    private static String firstItem(XmlNode node) {
        XmlNode li = node.find("li");
        return li != null ? li.text() : node.text();
    }

    private static double[] pageSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        return turned ? new double[] {box.getHeight(), box.getWidth()} : new double[] {box.getWidth(), box.getHeight()};
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /* ---------- Sumário ---------- */

    private static List<OutlineItem> readOutline(PDDocument pdf, ExtractionResult result) {
        List<OutlineItem> outline = new ArrayList<>();
        try {
            PDDocumentOutline root = pdf.getDocumentCatalog().getDocumentOutline();
            if (root != null) {
                walkOutline(pdf, root, 1, outline, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        } catch (IOException | RuntimeException e) {
            result.warn("O sumário do PDF está malformado e foi ignorado.");
        }
        return outline;
    }

    private static void walkOutline(PDDocument pdf, PDOutlineNode node, int level, List<OutlineItem> out, Set<Object> seen)
            throws IOException {
        for (PDOutlineItem item : node.children()) {
            if (out.size() >= MAX_OUTLINE_ITEMS || !seen.add(item.getCOSObject())) {
                return;
            }
            String title = Texts.singleLineOrNull(item.getTitle());
            if (title != null) {
                out.add(new OutlineItem(title, pageOf(pdf, item), level, null));
            }
            if (level < MAX_OUTLINE_DEPTH && item.hasChildren()) {
                walkOutline(pdf, item, level + 1, out, seen);
            }
        }
    }

    private static Integer pageOf(PDDocument pdf, PDOutlineItem item) {
        try {
            PDPage page = item.findDestinationPage(pdf);
            if (page == null) {
                return null;
            }
            int index = pdf.getPages().indexOf(page);
            return index >= 0 ? index + 1 : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /* ---------- Texto ---------- */

    private static void readText(PDDocument pdf, int maxChars, ExtractionResult result) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setLineSeparator("\n");
        stripper.setPageStart("");
        stripper.setPageEnd("");
        stripper.setParagraphStart("");
        stripper.setParagraphEnd("");
        stripper.setSortByPosition(false);
        List<String> pages = new ArrayList<>();
        long total = 0;
        boolean truncated = false;
        int count = pdf.getNumberOfPages();
        for (int n = 1; n <= count; n++) {
            String text = "";
            try {
                stripper.setStartPage(n);
                stripper.setEndPage(n);
                text = stripper.getText(pdf);
            } catch (IOException | RuntimeException e) {
                result.warn("Parte do texto não pôde ser extraída.");
            }
            text = Texts.clean(text).replace('\f', ' ');
            pages.add(text);
            total += text.length() + 1;
            if (total > maxChars) {
                truncated = true;
                break;
            }
        }
        String joined = String.join("\f", pages);
        Texts.FinishedText finished = Texts.finish(joined, maxChars);
        result.text(finished.text())
                .textTruncated(truncated || finished.truncated())
                .charCount(finished.charCount())
                .wordCount(finished.wordCount());
    }

    /* ---------- Capa ---------- */

    /** Capa: a primeira página que não esteja em branco, entre as três primeiras. */
    private static Cover renderCover(PDDocument pdf, int width) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdf);
        BufferedImage first = null;
        int limit = Math.min(CANDIDATE_PAGES, pdf.getNumberOfPages());
        for (int index = 0; index < limit; index++) {
            try {
                double[] size = pageSize(pdf.getPage(index));
                if (size[0] <= 0) {
                    continue;
                }
                float scale = (float) Math.min(6, width / size[0]);
                BufferedImage page = renderer.renderImage(index, scale, ImageType.RGB);
                int maxHeight = Math.round(page.getWidth() * 3f);
                if (page.getHeight() > maxHeight) {
                    page = page.getSubimage(0, 0, page.getWidth(), maxHeight);
                }
                if (first == null) {
                    first = page;
                }
                if (!Images.isNearlyUniform(page)) {
                    first = page;
                    break;
                }
            } catch (IOException | RuntimeException e) {
                // página que não renderiza: tenta a próxima
            }
        }
        if (first == null) {
            return null;
        }
        return new Cover(Images.jpeg(first, 0.88f), first.getWidth(), first.getHeight(), Images.averageColor(first), false);
    }
}
