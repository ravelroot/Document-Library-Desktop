package io.github.ravelroot.lume.docx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.ravelroot.lume.core.Texts;

/** Modelo intermediário do DOCX: blocos (parágrafos e tabelas) com trechos formatados, imagens e quebras. */
public final class DocxModel {

    private DocxModel() {
    }

    /** Parágrafo ou tabela. */
    public sealed interface Block permits Paragraph, Table {
    }

    /** Formatação de um trecho de texto. */
    public record Format(boolean bold, boolean italic, boolean underline, boolean strike, boolean superscript, boolean subscript) {

        public static final Format PLAIN = new Format(false, false, false, false, false, false);
    }

    /** Conteúdo de um parágrafo. */
    public sealed interface Inline permits Text, LineBreak, PageBreak, Image {
    }

    public record Text(String value, Format format, String href) implements Inline {
    }

    public record LineBreak() implements Inline {
    }

    public record PageBreak() implements Inline {
    }

    public record Image(String relationshipId, String alt) implements Inline {
    }

    public static final class Paragraph implements Block {
        String styleId;
        Integer outlineLevel;
        Integer numId;
        int ilvl;
        boolean pageBreakBefore;
        final List<Inline> inlines = new ArrayList<>();

        /** Tem texto visível ou imagem (parágrafos vazios não viram HTML). */
        boolean hasVisibleContent() {
            for (Inline inline : inlines) {
                if (inline instanceof Image || inline instanceof Text t && !Texts.trim(t.value()).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        String plainText() {
            StringBuilder out = new StringBuilder();
            for (Inline inline : inlines) {
                if (inline instanceof Text t) {
                    out.append(t.value());
                } else if (inline instanceof LineBreak) {
                    out.append('\n');
                }
            }
            return out.toString();
        }

        boolean hasPageBreak() {
            return inlines.stream().anyMatch(PageBreak.class::isInstance);
        }
    }

    public record Table(List<TableRow> rows) implements Block {
    }

    public static final class TableRow {
        boolean header;
        final List<TableCell> cells = new ArrayList<>();
    }

    /** Célula; a igualdade é por identidade (células mescladas são marcadas como "cobertas"). */
    public static final class TableCell {
        int gridSpan = 1;
        String verticalMerge;
        final List<Block> blocks = new ArrayList<>();
    }

    static boolean sameFormat(Format a, Format b) {
        return a != null && b != null && Objects.equals(a, b);
    }
}
