package io.github.ravelroot.lume.processing;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

import io.github.ravelroot.lume.core.Texts;

/**
 * Paleta do Lume (a mesma da versão web). As capas geradas (DOCX e PDFs sem capa) usam somente estes tons, e o tom
 * de cada documento é estável: depende só do título (CRC32 do título em minúsculas).
 */
public final class Palette {

    public record Tone(String name, String hex, String ink) {

        public Color color() {
            return Palette.color(hex);
        }

        public Color inkColor() {
            return Palette.color(ink);
        }
    }

    private record Base(String name, String hex) {
    }

    private static final List<Base> BASES = List.of(
            new Base("Creme", "#F4F1DE"),
            new Base("Camurça", "#B0A08C"),
            new Base("Sálvia", "#B8C9A9"),
            new Base("Ardósia", "#8395A7"),
            new Base("Rosé", "#D8A7B1"),
            // Derivado da Ardósia: a paleta não tem um tom escuro para fundos fortes.
            new Base("Ardósia escura", "#4A5A6B"));

    /** Tons de texto das capas: Tinta (derivada da Ardósia) e Creme. */
    public static final String INK_DARK = "#2B3540";
    public static final String INK_LIGHT = "#F4F1DE";

    public static final List<Tone> TONES = BASES.stream().map(b -> new Tone(b.name(), b.hex(), inkFor(b.hex()))).toList();

    private Palette() {
    }

    public static Color color(String hex) {
        String value = hex.replace("#", "");
        return new Color(Integer.parseInt(value.substring(0, 2), 16), Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16));
    }

    /** Luminância relativa (WCAG). */
    public static double luminance(String hex) {
        Color c = color(hex);
        double r = channel(c.getRed());
        double g = channel(c.getGreen());
        double b = channel(c.getBlue());
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    public static String inkFor(String background) {
        return contrast(background, INK_LIGHT) >= contrast(background, INK_DARK) ? INK_LIGHT : INK_DARK;
    }

    public static Tone toneFor(String key) {
        CRC32 crc = new CRC32();
        crc.update(Texts.trim(key == null ? "" : key).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return TONES.get((int) (crc.getValue() % TONES.size()));
    }
}
