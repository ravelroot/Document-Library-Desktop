package io.github.ravelroot.lume.core;

import java.util.Collection;
import java.util.Map;

/**
 * Conversões com as mesmas regras do JavaScript para os campos que chegam da interface em JSON
 * (número que veio como texto, "true" como booleano, campo ausente versus null).
 */
public final class Values {

    private Values() {
    }

    /** String(valor), com null e ausente virando texto vazio. */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    /** Number(valor): null e "" valem 0; texto que não é número vale NaN. */
    public static double number(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String text = Texts.trim(String.valueOf(value));
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Valor "verdadeiro" do JavaScript (o que um if aceitaria). */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (value instanceof CharSequence s) {
            return !s.isEmpty();
        }
        return true;
    }

    /** Objeto JSON (não nulo e não lista). */
    public static boolean isObject(Object value) {
        return value instanceof Map<?, ?>;
    }

    public static boolean isList(Object value) {
        return value instanceof Collection<?>;
    }

    public static int clampInt(double value, int min, int max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return (int) Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
