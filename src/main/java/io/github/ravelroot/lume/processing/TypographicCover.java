package io.github.ravelroot.lume.processing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Capa tipográfica nas cores da paleta, para DOCX e PDFs sem uma página aproveitável. Rótulo em versalete, título em
 * Literata SemiBold reduzido até caber (no máximo cinco linhas) e o autor em itálico no pé, com Java2D.
 */
public final class TypographicCover {

    private static final int BASE_WIDTH = 480;
    private static final int MAX_TITLE_LINES = 5;
    private static final Font SEMIBOLD = load("/capas/Literata-SemiBold.ttf");
    private static final Font ITALIC = load("/capas/Literata-Italic.ttf");

    private TypographicCover() {
    }

    private static Font load(String resource) {
        try (InputStream in = TypographicCover.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Fonte ausente: " + resource);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (FontFormatException e) {
            throw new IllegalStateException("Fonte inválida: " + resource, e);
        }
    }

    /** Literata quando ela tem todos os caracteres; senão a serifada do sistema (evita quadradinhos). */
    private static Font font(Font base, String text, float size) {
        Font chosen = base.canDisplayUpTo(text) == -1 ? base : new Font(Font.SERIF, base.getStyle(), 1);
        return chosen.deriveFont(size);
    }

    private static Color alpha(Color color, double a) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) Math.round(a * 255));
    }

    public static Cover render(CoverRequest job) {
        int width = job.width() > 0 ? job.width() : BASE_WIDTH;
        int height = Math.round(width * 4f / 3f);
        float s = width / (float) BASE_WIDTH;
        String title = job.title() == null || job.title().isBlank() ? "Documento sem título" : job.title().strip();
        Color ink = Palette.color(job.ink());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g.setColor(Palette.color(job.background()));
            g.fillRect(0, 0, width, height);

            float margin = 44 * s;
            float inset = 16 * s;
            g.setColor(alpha(ink, 0.22));
            g.setStroke(new BasicStroke(Math.max(1, 0.8f * s)));
            g.draw(new Rectangle2D.Float(inset, inset, width - 2 * inset, height - 2 * inset));

            // Rótulo (PDF, DOCX) espaçado, com um fio embaixo.
            String label = job.label() == null ? "" : job.label().toUpperCase(Locale.ROOT);
            Font labelFont = font(SEMIBOLD, label, 11 * s).deriveFont(Map.of(TextAttribute.TRACKING, 0.28f));
            g.setFont(labelFont);
            g.setColor(alpha(ink, 0.78));
            float labelBaseline = margin + 12 * s;
            g.drawString(label, margin, labelBaseline);

            g.setColor(alpha(ink, 0.5));
            g.setStroke(new BasicStroke(Math.max(1, s)));
            float ruleY = labelBaseline + 14 * s;
            g.draw(new Line2D.Float(margin, ruleY, width - margin, ruleY));

            // Título em Literata, com o corpo reduzido até caber.
            float maxWidth = width - 2 * margin;
            float maxHeight = height * 0.49f;
            float size = 50 * s;
            float minSize = 22 * s;
            List<String> lines;
            float lineHeight;
            Font titleFont;
            while (true) {
                titleFont = font(SEMIBOLD, title, size);
                g.setFont(titleFont);
                lines = wrap(g, title, maxWidth);
                lineHeight = size * 1.14f;
                if ((lines.size() <= MAX_TITLE_LINES && lines.size() * lineHeight <= maxHeight) || size <= minSize) {
                    break;
                }
                size = Math.max(minSize, size - 1.5f * s);
            }
            if (lines.size() > MAX_TITLE_LINES) {
                lines = new ArrayList<>(lines.subList(0, MAX_TITLE_LINES));
                lines.set(MAX_TITLE_LINES - 1, ellipsize(g, lines.get(MAX_TITLE_LINES - 1) + "…", maxWidth));
            }
            g.setColor(ink);
            float baseline = height * 0.29f + size * 0.92f;
            for (String line : lines) {
                g.drawString(line, margin, baseline);
                baseline += lineHeight;
            }

            String author = job.author() == null ? "" : job.author().strip();
            if (!author.isEmpty()) {
                float authorBaseline = height - margin - 4 * s;
                g.setColor(ink);
                g.setStroke(new BasicStroke(1.4f * s));
                float shortRuleY = authorBaseline - 30 * s;
                g.draw(new Line2D.Float(margin, shortRuleY, margin + 36 * s, shortRuleY));
                g.setFont(font(ITALIC, author, 17 * s));
                g.setColor(alpha(ink, 0.92));
                g.drawString(ellipsize(g, author, maxWidth), margin, authorBaseline);
            }
        } finally {
            g.dispose();
        }
        return new Cover(Images.jpeg(image, 0.92f), width, height, job.background(), true);
    }

    private static double measure(Graphics2D g, String text) {
        return g.getFont().getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    /** Quebra em linhas; palavras maiores que a linha são partidas com hífen. */
    static List<String> wrap(Graphics2D g, String text, double maxWidth) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (measure(g, candidate) <= maxWidth) {
                line = candidate;
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            line = "";
            String rest = word;
            while (measure(g, rest) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && measure(g, rest.substring(0, cut) + "-") > maxWidth) {
                    cut--;
                }
                lines.add(rest.substring(0, cut) + "-");
                rest = rest.substring(cut);
            }
            line = rest;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    static String ellipsize(Graphics2D g, String text, double maxWidth) {
        if (measure(g, text) <= maxWidth) {
            return text;
        }
        String base = text.endsWith("…") ? text.substring(0, text.length() - 1) : text;
        while (!base.isEmpty() && measure(g, base.strip() + "…") > maxWidth) {
            base = base.substring(0, base.length() - 1);
        }
        return base.strip() + "…";
    }
}
