package io.github.ravelroot.lume.docx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.docx.DocxModel.Block;
import io.github.ravelroot.lume.docx.DocxModel.Format;
import io.github.ravelroot.lume.docx.DocxModel.Image;
import io.github.ravelroot.lume.docx.DocxModel.Inline;
import io.github.ravelroot.lume.docx.DocxModel.LineBreak;
import io.github.ravelroot.lume.docx.DocxModel.Paragraph;
import io.github.ravelroot.lume.docx.DocxModel.Table;
import io.github.ravelroot.lume.docx.DocxModel.TableCell;
import io.github.ravelroot.lume.docx.DocxModel.TableRow;
import io.github.ravelroot.lume.docx.DocxModel.Text;
import io.github.ravelroot.lume.processing.OutlineItem;

/**
 * Converte o modelo do DOCX num fragmento HTML semântico para a tela de leitura, junto com o texto puro (busca e
 * descrição) e o sumário a partir dos títulos.
 */
public final class HtmlWriter {

    private final Styles styles;
    private final Numbering numbering;
    private final Function<String, String> media;
    private StringBuilder html = new StringBuilder();
    private StringBuilder text = new StringBuilder();
    private final List<OutlineItem> outline = new ArrayList<>();
    private String titleText;
    private String firstHeading;
    private int blockIndex;
    private int headingCounter;

    /** {@code media(relationshipId)} copia a imagem e devolve o src relativo, ou null se ela não for exibível. */
    public HtmlWriter(Styles styles, Numbering numbering, Function<String, String> media) {
        this.styles = styles;
        this.numbering = numbering;
        this.media = media;
    }

    public HtmlWriter write(List<Block> blocks) {
        writeBlocks(blocks, true);
        return this;
    }

    public String html() {
        return html.toString();
    }

    public String text() {
        return text.toString();
    }

    public List<OutlineItem> outline() {
        return outline;
    }

    public String titleText() {
        return titleText;
    }

    public String firstHeading() {
        return firstHeading;
    }

    private record Kind(String kind, int level, boolean ordered) {
    }

    private void writeBlocks(List<Block> blocks, boolean topLevel) {
        ListState lists = new ListState();
        boolean quoteOpen = false;
        boolean lastWasRule = true;
        for (Block block : blocks) {
            if (block instanceof Table table) {
                lists.closeAll();
                quoteOpen = closeQuote(quoteOpen);
                writeTable(table, topLevel);
                lastWasRule = false;
                continue;
            }
            Paragraph paragraph = (Paragraph) block;
            if (paragraph.pageBreakBefore && topLevel && !lastWasRule) {
                lists.closeAll();
                quoteOpen = closeQuote(quoteOpen);
                html.append("<hr").append(blockAttr(true)).append(">\n");
                lastWasRule = true;
            }
            if (paragraph.hasVisibleContent()) {
                Kind kind = classify(paragraph);
                String inline = inlineHtml(paragraph);
                String plain = Texts.trim(paragraph.plainText());
                text.append(plain).append('\n');
                switch (kind.kind()) {
                    case "list" -> {
                        quoteOpen = closeQuote(quoteOpen);
                        lists.item(kind.ordered(), kind.level(), inline, topLevel);
                    }
                    case "quote" -> {
                        lists.closeAll();
                        if (!quoteOpen) {
                            html.append("<blockquote").append(blockAttr(topLevel)).append('>');
                            quoteOpen = true;
                        }
                        html.append("<p>").append(inline).append("</p>");
                    }
                    default -> {
                        lists.closeAll();
                        quoteOpen = closeQuote(quoteOpen);
                        writeParagraph(kind, inline, plain, topLevel);
                    }
                }
                lastWasRule = false;
            }
            if (paragraph.hasPageBreak() && topLevel && !lastWasRule) {
                lists.closeAll();
                quoteOpen = closeQuote(quoteOpen);
                html.append("<hr").append(blockAttr(true)).append(">\n");
                lastWasRule = true;
            }
        }
        lists.closeAll();
        closeQuote(quoteOpen);
        if (topLevel) {
            trimTrailingRule();
        }
    }

    private boolean closeQuote(boolean open) {
        if (open) {
            html.append("</blockquote>\n");
        }
        return false;
    }

    private void writeParagraph(Kind kind, String inline, String plain, boolean topLevel) {
        String heading = Texts.singleLineOrNull(plain);
        switch (kind.kind()) {
            case "title" -> {
                if (titleText == null) {
                    titleText = heading;
                }
                String id = "s-" + (++headingCounter);
                html.append("<h1 id=\"").append(id).append('"').append(blockAttr(topLevel)).append('>').append(inline).append("</h1>\n");
            }
            case "heading" -> {
                if (!topLevel) {
                    html.append("<p><strong>").append(inline).append("</strong></p>");
                    return;
                }
                if (firstHeading == null) {
                    firstHeading = heading;
                }
                int level = Math.min(kind.level(), 4);
                int tag = Math.min(level + 1, 4);
                String id = "s-" + (++headingCounter);
                html.append("<h").append(tag).append(" id=\"").append(id).append('"').append(blockAttr(true)).append('>')
                        .append(inline).append("</h").append(tag).append(">\n");
                if (heading != null) {
                    outline.add(new OutlineItem(heading, null, level, id));
                }
            }
            case "subtitle" -> html.append("<p class=\"subtitulo\"").append(blockAttr(topLevel)).append('>').append(inline).append("</p>\n");
            default -> html.append("<p").append(blockAttr(topLevel)).append('>').append(inline).append("</p>\n");
        }
    }

    private void trimTrailingRule() {
        String trimmed = html.toString().stripTrailing();
        int start = trimmed.lastIndexOf("<hr");
        if (start >= 0 && trimmed.indexOf('>', start) == trimmed.length() - 1) {
            html = new StringBuilder(trimmed.substring(0, start));
        }
    }

    private String blockAttr(boolean topLevel) {
        return topLevel ? " data-b=\"" + (blockIndex++) + "\"" : "";
    }

    private Kind classify(Paragraph p) {
        String role = styles.role(p.styleId);
        if (role.equals("title")) {
            return new Kind("title", 0, false);
        }
        if (role.equals("subtitle")) {
            return new Kind("subtitle", 0, false);
        }
        int heading = p.outlineLevel != null
                ? (p.outlineLevel >= 0 && p.outlineLevel < 9 ? p.outlineLevel + 1 : 0)
                : styles.headingLevel(p.styleId);
        if (heading > 0) {
            return new Kind("heading", heading, false);
        }
        int[] list = p.numId != null ? (p.numId > 0 ? new int[] {p.numId, p.ilvl} : null) : styles.numbering(p.styleId);
        if (list != null && list[0] > 0) {
            int ilvl = Math.max(0, Math.min(8, list[1]));
            int level = Math.max(ilvl, styles.listLevelFromName(p.styleId));
            return new Kind("list", level, numbering.isOrdered(list[0], ilvl));
        }
        if (role.equals("quote")) {
            return new Kind("quote", 0, false);
        }
        return new Kind("normal", 0, false);
    }

    private String inlineHtml(Paragraph p) {
        StringBuilder out = new StringBuilder();
        Pending pending = new Pending();
        for (Inline inline : p.inlines) {
            if (inline instanceof Text t) {
                if (pending.format != null && DocxModel.sameFormat(t.format(), pending.format) && Objects.equals(t.href(), pending.href)) {
                    pending.text.append(t.value());
                    continue;
                }
                pending.flush(out);
                pending.text.append(t.value());
                pending.format = t.format();
                pending.href = t.href();
                continue;
            }
            pending.flush(out);
            pending.format = null;
            pending.href = null;
            if (inline instanceof LineBreak) {
                out.append("<br>");
            } else if (inline instanceof Image image) {
                String src = media.apply(image.relationshipId());
                if (src != null) {
                    out.append("<img src=\"").append(Texts.escapeHtml(src)).append("\" alt=\"").append(Texts.escapeHtml(image.alt()))
                            .append("\" loading=\"lazy\">");
                }
            }
        }
        pending.flush(out);
        String result = out.toString();
        while (result.endsWith("<br>")) {
            result = result.substring(0, result.length() - 4);
        }
        return Texts.trim(result);
    }

    /** Trecho de texto acumulado com a mesma formatação e o mesmo link. */
    private static final class Pending {
        final StringBuilder text = new StringBuilder();
        Format format;
        String href;

        void flush(StringBuilder out) {
            if (text.isEmpty()) {
                return;
            }
            String content = Texts.escapeHtml(text.toString());
            text.setLength(0);
            if (format == null || Texts.trim(content).isEmpty()) {
                out.append(content);
                return;
            }
            List<String> tags = new ArrayList<>();
            if (format.bold()) {
                tags.add("strong");
            }
            if (format.italic()) {
                tags.add("em");
            }
            if (format.underline() && href == null) {
                tags.add("u");
            }
            if (format.strike()) {
                tags.add("s");
            }
            if (format.superscript()) {
                tags.add("sup");
            } else if (format.subscript()) {
                tags.add("sub");
            }
            if (href != null) {
                out.append("<a href=\"").append(Texts.escapeHtml(href)).append("\" rel=\"noopener noreferrer\">");
            }
            for (String tag : tags) {
                out.append('<').append(tag).append('>');
            }
            out.append(content);
            for (int i = tags.size() - 1; i >= 0; i--) {
                out.append("</").append(tags.get(i)).append('>');
            }
            if (href != null) {
                out.append("</a>");
            }
        }
    }

    private void writeTable(Table table, boolean topLevel) {
        List<TableRow> rows = table.rows();
        List<int[]> columns = new ArrayList<>();
        for (TableRow row : rows) {
            int[] starts = new int[row.cells.size()];
            int column = 0;
            for (int i = 0; i < starts.length; i++) {
                starts[i] = column;
                column += row.cells.get(i).gridSpan;
            }
            columns.add(starts);
        }
        List<int[]> rowspan = new ArrayList<>();
        Set<TableCell> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int r = 0; r < rows.size(); r++) {
            TableRow row = rows.get(r);
            int[] spans = new int[row.cells.size()];
            java.util.Arrays.fill(spans, 1);
            rowspan.add(spans);
            for (int c = 0; c < row.cells.size(); c++) {
                if (!"restart".equals(row.cells.get(c).verticalMerge)) {
                    continue;
                }
                for (int below = r + 1; below < rows.size(); below++) {
                    TableCell next = cellAt(rows.get(below), columns.get(below), columns.get(r)[c]);
                    if (next == null || !"continue".equals(next.verticalMerge)) {
                        break;
                    }
                    covered.add(next);
                    spans[c] += 1;
                }
            }
        }
        html.append("<table").append(blockAttr(topLevel)).append("><tbody>");
        for (int r = 0; r < rows.size(); r++) {
            TableRow row = rows.get(r);
            html.append("<tr>");
            List<String> cellTexts = new ArrayList<>();
            for (int c = 0; c < row.cells.size(); c++) {
                TableCell cell = row.cells.get(c);
                if (covered.contains(cell)) {
                    continue;
                }
                String tag = row.header ? "th" : "td";
                html.append('<').append(tag);
                if (cell.gridSpan > 1) {
                    html.append(" colspan=\"").append(cell.gridSpan).append('"');
                }
                if (rowspan.get(r)[c] > 1) {
                    html.append(" rowspan=\"").append(rowspan.get(r)[c]).append('"');
                }
                html.append('>');
                int mark = text.length();
                writeCell(cell);
                cellTexts.add(Texts.trim(text.substring(mark)).replace('\n', ' '));
                text.setLength(mark);
                html.append("</").append(tag).append('>');
            }
            html.append("</tr>");
            text.append(String.join("\t", cellTexts)).append('\n');
        }
        html.append("</tbody></table>\n");
    }

    private void writeCell(TableCell cell) {
        Paragraph only = cell.blocks.size() == 1 && cell.blocks.get(0) instanceof Paragraph p ? p : null;
        if (only != null && classify(only).kind().equals("normal")) {
            text.append(Texts.trim(only.plainText())).append('\n');
            html.append(inlineHtml(only));
            return;
        }
        writeBlocks(cell.blocks, false);
    }

    private static TableCell cellAt(TableRow row, int[] starts, int column) {
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] == column) {
                return row.cells.get(i);
            }
            if (starts[i] > column) {
                return null;
            }
        }
        return null;
    }

    /** Listas aninhadas: cada nível aberto mantém um {@code <li>} aberto até chegar o próximo item. */
    private final class ListState {
        private final Deque<Boolean> open = new ArrayDeque<>(); // true = ol, false = ul

        void item(boolean ordered, int level, String content, boolean topLevel) {
            int target = level + 1;
            while (open.size() > target) {
                close();
            }
            if (open.size() == target) {
                html.append("</li>");
                if (open.peekLast() != ordered) {
                    html.append(open.removeLast() ? "</ol>" : "</ul>");
                }
            }
            while (open.size() < target) {
                boolean outermost = open.isEmpty();
                html.append(ordered ? "<ol" : "<ul").append(outermost ? blockAttr(topLevel) : "").append('>');
                open.addLast(ordered);
                if (open.size() < target) {
                    html.append("<li>");
                }
            }
            html.append("<li>").append(content);
        }

        void closeAll() {
            while (!open.isEmpty()) {
                close();
            }
        }

        void close() {
            html.append("</li>").append(open.removeLast() ? "</ol>" : "</ul>");
            if (open.isEmpty()) {
                html.append('\n');
            }
        }
    }
}
