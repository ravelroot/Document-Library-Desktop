package io.github.ravelroot.lume.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limpeza, comparação e contagem de texto (as mesmas regras da versão web do Lume). */
public final class Texts {

    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0\\u2007\\u202f\\ufeff]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LINE_CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]");
    /** Mantém \n, \t e \f (o \f separa as páginas do PDF). */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000b\\u000e-\\u001f\\u007f-\\u009f\\ufffe\\uffff]");
    private static final Pattern LONE_SURROGATE =
            Pattern.compile("[\\ud800-\\udbff](?![\\udc00-\\udfff])|(?<![\\ud800-\\udbff])[\\udc00-\\udfff]");
    private static final Pattern FILENAME_SEPARATORS = Pattern.compile("_+|(?<=[A-Za-z0-9_])-(?=[A-Za-z0-9_])|\\.+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*");

    private Texts() {
    }

    /** Minúsculas e sem acentos: usado para ordenar e comparar títulos e etiquetas. */
    public static String fold(Object text) {
        String value = text == null ? "" : String.valueOf(text);
        return COMBINING.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    public static String slugify(Object text) {
        String slug = EDGE_DASHES.matcher(NOT_SLUG.matcher(fold(text)).replaceAll("-")).replaceAll("");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    /** Uma linha só: sem caracteres de controle e com os espaços colapsados. Nunca devolve null. */
    public static String singleLine(Object value) {
        return singleLine(value, 0);
    }

    public static String singleLine(Object value, int limit) {
        String text = value == null ? "" : String.valueOf(value);
        text = trim(WHITESPACE.matcher(LINE_CONTROL.matcher(text).replaceAll("")).replaceAll(" "));
        return limit > 0 && text.length() > limit ? text.substring(0, limit) : text;
    }

    /** Como {@link #singleLine(Object)}, mas devolve null quando não sobra texto (metadados de PDF e DOCX). */
    public static String singleLineOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = trim(WHITESPACE.matcher(clean(String.valueOf(raw))).replaceAll(" "));
        return value.isEmpty() ? null : value;
    }

    /** "relatorio_final-2026.pdf" → "Relatorio final 2026". */
    public static String titleFromFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "documento" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        stem = trim(WHITESPACE.matcher(FILENAME_SEPARATORS.matcher(stem).replaceAll(" ")).replaceAll(" "));
        if (stem.isEmpty()) {
            return "Documento sem título";
        }
        if (stem.equals(stem.toLowerCase(Locale.ROOT))) {
            int first = stem.offsetByCodePoints(0, 1);
            return stem.substring(0, first).toUpperCase(Locale.ROOT) + stem.substring(first);
        }
        return stem;
    }

    public static String initials(String name) {
        String[] parts = trim(name == null ? "" : name).split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "L";
        }
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1
                ? parts[parts.length - 1].substring(0, 1)
                : parts[0].length() > 1 ? parts[0].substring(1, 2) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }

    /** Remove caracteres de controle e surrogates soltos e padroniza as quebras de linha. */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = CONTROL.matcher(text).replaceAll("");
        text = LONE_SURROGATE.matcher(text).replaceAll("");
        return trim(text);
    }

    /** Corta sem partir um par substituto (um emoji, por exemplo). */
    public static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end -= 1;
        }
        return text.substring(0, end);
    }

    /** Palavras = sequências de letras ou números, aceitando hífen e apóstrofo internos ("guarda-chuva", "d'água"). */
    public static int countWords(String text) {
        Matcher matcher = WORD.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** Aplica o limite de caracteres e devolve o texto com as contagens. */
    public static FinishedText finish(String raw, int maxChars) {
        String text = clean(raw);
        boolean truncated = false;
        if (text.length() > maxChars) {
            text = truncate(text, maxChars);
            truncated = true;
        }
        return new FinishedText(text, truncated, text.length(), countWords(text));
    }

    /** trim() do JavaScript: também tira espaços não separáveis e o BOM das pontas. */
    public static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isJsSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isJsSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    public static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Texto pronto para guardar: o conteúdo e as contagens. */
    public record FinishedText(String text, boolean truncated, int charCount, int wordCount) {
    }
}
