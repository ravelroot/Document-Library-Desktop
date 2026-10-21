package io.github.ravelroot.lume.docx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.ravelroot.lume.docx.DocxModel.Block;
import io.github.ravelroot.lume.docx.DocxModel.Format;
import io.github.ravelroot.lume.docx.DocxModel.Image;
import io.github.ravelroot.lume.docx.DocxModel.LineBreak;
import io.github.ravelroot.lume.docx.DocxModel.PageBreak;
import io.github.ravelroot.lume.docx.DocxModel.Paragraph;
import io.github.ravelroot.lume.docx.DocxModel.Table;
import io.github.ravelroot.lume.docx.DocxModel.TableCell;
import io.github.ravelroot.lume.docx.DocxModel.TableRow;
import io.github.ravelroot.lume.docx.DocxModel.Text;

/** Lê word/document.xml e monta o modelo intermediário (parágrafos, tabelas e trechos formatados). */
public final class DocumentParser {

    private static final Set<String> BLOCK_CONTAINERS =
            Set.of("sdt", "sdtContent", "customXml", "smartTag", "ins", "AlternateContent", "Choice");
    private static final Set<String> INLINE_CONTAINERS = Set.of("ins", "smartTag", "customXml", "fldSimple", "sdt",
            "sdtContent", "AlternateContent", "Choice", "dir", "bdo", "moveTo");
    private static final Pattern WEB_LINK = Pattern.compile("^(https?://|mailto:)", Pattern.CASE_INSENSITIVE);

    private final Styles styles;
    private final Relationships relationships;
    private final List<Block> body = new ArrayList<>();
    private Double pageWidth;
    private Double pageHeight;

    public DocumentParser(Styles styles, Relationships relationships) {
        this.styles = styles;
        this.relationships = relationships;
    }

    public List<Block> body() {
        return body;
    }

    public Double pageWidth() {
        return pageWidth;
    }

    public Double pageHeight() {
        return pageHeight;
    }

    public DocumentParser parse(XmlNode tree) {
        XmlNode bodyNode = tree.find("body");
        if (bodyNode != null) {
            blocks(bodyNode, body, null);
        }
        return this;
    }

    private void blocks(XmlNode node, List<Block> out, TableCell cell) {
        for (XmlNode child : node.elements()) {
            String name = child.name();
            if (name.equals("p")) {
                out.add(paragraph(child));
            } else if (name.equals("tbl")) {
                out.add(table(child));
            } else if (BLOCK_CONTAINERS.contains(name)) {
                blocks(child, out, cell);
            } else if (name.equals("tcPr") && cell != null) {
                cellProperties(child, cell);
            } else if (name.equals("sectPr")) {
                section(child);
            }
        }
    }

    private Paragraph paragraph(XmlNode node) {
        Paragraph p = new Paragraph();
        paragraphContent(node, p, null);
        return p;
    }

    private void paragraphContent(XmlNode node, Paragraph p, String href) {
        for (XmlNode child : node.elements()) {
            String name = child.name();
            if (name.equals("pPr")) {
                paragraphProperties(child, p);
            } else if (name.equals("r")) {
                run(child, p, href, new Format[] {Format.PLAIN});
            } else if (name.equals("hyperlink")) {
                paragraphContent(child, p, hyperlink(child.attr("id")));
            } else if (INLINE_CONTAINERS.contains(name)) {
                paragraphContent(child, p, href);
            }
        }
    }

    private void paragraphProperties(XmlNode node, Paragraph p) {
        for (XmlNode child : node.elements()) {
            switch (child.name()) {
                case "pStyle" -> p.styleId = child.attr("val");
                case "numPr" -> {
                    for (XmlNode item : child.elements()) {
                        if (item.name().equals("ilvl")) {
                            p.ilvl = Math.max(0, Math.min(8, item.intAttr("val", 0)));
                        } else if (item.name().equals("numId")) {
                            p.numId = item.intAttr("val", 0);
                        }
                    }
                }
                case "outlineLvl" -> p.outlineLevel = child.intAttr("val", 9);
                case "pageBreakBefore" -> p.pageBreakBefore = child.toggle();
                default -> {
                    // demais propriedades de parágrafo não afetam a leitura
                }
            }
        }
    }

    /** Conteúdo de um w:r; também serve para mc:AlternateContent/mc:Choice dentro do trecho. */
    private void run(XmlNode node, Paragraph p, String href, Format[] state) {
        for (XmlNode child : node.elements()) {
            switch (child.name()) {
                case "rPr" -> state[0] = runProperties(child);
                case "t" -> p.inlines.add(new Text(child.text(), state[0], href));
                case "tab", "ptab" -> p.inlines.add(new Text(" ", state[0], href));
                case "noBreakHyphen" -> p.inlines.add(new Text("-", state[0], href));
                case "br" -> p.inlines.add("page".equals(child.attr("type")) ? new PageBreak() : new LineBreak());
                case "cr" -> p.inlines.add(new LineBreak());
                case "drawing" -> drawing(child, p);
                case "pict", "object" -> vml(child, p);
                case "AlternateContent", "Choice" -> run(child, p, href, state);
                default -> {
                    // marcas de revisão, campos e afins
                }
            }
        }
    }

    private Format runProperties(XmlNode node) {
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        boolean superscript = false;
        boolean subscript = false;
        for (XmlNode child : node.elements()) {
            switch (child.name()) {
                case "b" -> bold = child.toggle();
                case "i" -> italic = child.toggle();
                case "u" -> underline = child.toggle();
                case "strike", "dstrike" -> strike = strike || child.toggle();
                case "vertAlign" -> {
                    superscript = "superscript".equals(child.attr("val"));
                    subscript = "subscript".equals(child.attr("val"));
                }
                case "rStyle" -> {
                    bold = bold || styles.runBold(child.attr("val"));
                    italic = italic || styles.runItalic(child.attr("val"));
                }
                default -> {
                    // fonte, cor e tamanho não entram no HTML de leitura
                }
            }
        }
        return new Format(bold, italic, underline, strike, superscript, subscript);
    }

    /** Imagem DrawingML: o r:embed do a:blip e a descrição (alt) do wp:docPr. */
    private void drawing(XmlNode node, Paragraph p) {
        String[] embed = {null};
        String[] alt = {null};
        XmlNode.walk(node, child -> {
            if (child.name().equals("docPr")) {
                String description = child.attr("descr");
                alt[0] = description != null && !description.isBlank() ? description : child.attr("title");
            } else if (child.name().equals("blip") && embed[0] == null) {
                embed[0] = child.attr("embed");
            }
        });
        if (embed[0] != null && !embed[0].isEmpty()) {
            p.inlines.add(new Image(embed[0], alt[0] == null ? "" : alt[0].strip()));
        }
    }

    /** Imagem VML antiga (w:pict): v:imagedata com r:id. */
    private void vml(XmlNode node, Paragraph p) {
        String[] id = {null};
        String[] alt = {null};
        XmlNode.walk(node, child -> {
            if (child.name().equals("imagedata") && id[0] == null) {
                id[0] = child.attr("id");
                alt[0] = child.attr("title");
            } else if (child.name().equals("shape") && alt[0] == null) {
                alt[0] = child.attr("alt");
            }
        });
        if (id[0] != null && !id[0].isEmpty()) {
            p.inlines.add(new Image(id[0], alt[0] == null ? "" : alt[0].strip()));
        }
    }

    private Table table(XmlNode node) {
        List<TableRow> rows = new ArrayList<>();
        for (XmlNode child : node.elements()) {
            if (!child.name().equals("tr")) {
                continue;
            }
            TableRow row = new TableRow();
            for (XmlNode item : child.elements()) {
                if (item.name().equals("trPr")) {
                    for (XmlNode prop : item.elements()) {
                        if (prop.name().equals("tblHeader")) {
                            row.header = prop.toggle();
                        }
                    }
                } else if (item.name().equals("tc")) {
                    TableCell cell = new TableCell();
                    blocks(item, cell.blocks, cell);
                    row.cells.add(cell);
                }
            }
            rows.add(row);
        }
        return new Table(rows);
    }

    private void cellProperties(XmlNode node, TableCell cell) {
        for (XmlNode child : node.elements()) {
            if (child.name().equals("gridSpan")) {
                cell.gridSpan = Math.max(1, Math.min(64, child.intAttr("val", 1)));
            } else if (child.name().equals("vMerge")) {
                String value = child.attr("val");
                cell.verticalMerge = value == null ? "continue" : value.toLowerCase(Locale.ROOT);
            }
        }
    }

    private void section(XmlNode node) {
        for (XmlNode child : node.elements()) {
            if (child.name().equals("pgSz") && pageWidth == null) {
                int width = child.intAttr("w", 0);
                int height = child.intAttr("h", 0);
                if (width > 0 && height > 0) {
                    pageWidth = width / 20.0;
                    pageHeight = height / 20.0;
                }
            }
        }
    }

    /** Só links externos http(s) e mailto viram {@code <a>}; âncoras internas ficam como texto. */
    private String hyperlink(String relationshipId) {
        Relationships.Rel rel = relationships.get(relationshipId);
        if (rel == null || !rel.external() || rel.target() == null) {
            return null;
        }
        String target = rel.target().strip();
        return WEB_LINK.matcher(target).find() ? target : null;
    }
}
