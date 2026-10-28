package io.github.ravelroot.lume.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.ravelroot.lume.core.Texts;

/**
 * Busca local de trechos para "Perguntar ao documento": divide o texto em trechos de ~400 a 700 caracteres (com a
 * página, quando o PDF separa as páginas com \f), e pontua cada trecho pelos termos da pergunta, sem acentos e sem
 * palavras vazias, num BM25 simples. Funciona sem internet e serve de base para a resposta dos provedores externos.
 */
public final class PassageFinder {

    static final int MIN_CHUNK = 400;
    static final int MAX_CHUNK = 700;
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final double K1 = 1.4;
    private static final double B = 0.72;

    private PassageFinder() {
    }

    /** Um trecho do documento: o texto, a página (null no DOCX) e a pontuação para a pergunta. */
    public record Passage(String text, Integer page, double score) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("text", text);
            out.put("page", page);
            out.put("score", Math.round(score * 1000) / 1000.0);
            return out;
        }
    }

    /** Termo normalizado: minúsculo, sem acento e com um radical simples (os 6 primeiros caracteres). */
    static String stem(String word) {
        String folded = LocalSummarizer.stripAccents(word.toLowerCase(Locale.ROOT));
        return folded.length() > 6 ? folded.substring(0, 6) : folded;
    }

    /** Termos da pergunta (ou de um trecho), sem palavras vazias e sem repetições. */
    public static List<String> terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String word : LocalSummarizer.words(text)) {
            String folded = LocalSummarizer.stripAccents(word);
            if (folded.length() < 2 || LocalSummarizer.STOP_ALL.contains(word) || LocalSummarizer.STOP_ALL.contains(folded)) {
                continue;
            }
            out.add(stem(word));
        }
        return new ArrayList<>(out);
    }

    /** Divide o texto em trechos, preservando a página do PDF. */
    public static List<Passage> chunks(String text, boolean byPage) {
        List<Passage> out = new ArrayList<>();
        String value = text == null ? "" : text;
        String[] pages = byPage && value.contains("\f") ? value.split("\f", -1) : new String[] {value};
        if (pages.length >= 3) {
            pages = withoutRunningHeads(pages);
        }
        for (int p = 0; p < pages.length; p++) {
            Integer page = byPage ? Integer.valueOf(p + 1) : null;
            List<String> paragraphs = new ArrayList<>();
            for (String block : BLANK_LINES.split(pages[p])) {
                if (!byPage) {
                    // No DOCX cada linha já é um parágrafo.
                    for (String line : block.split("\\n")) {
                        String clean = Texts.trim(SPACES.matcher(line).replaceAll(" "));
                        if (!clean.isEmpty()) {
                            paragraphs.add(clean);
                        }
                    }
                } else {
                    String joined = Texts.trim(SPACES.matcher(LINE_BREAKS.matcher(block).replaceAll(" ")).replaceAll(" "));
                    if (!joined.isEmpty()) {
                        paragraphs.add(joined);
                    }
                }
            }
            StringBuilder current = new StringBuilder();
            for (String paragraph : paragraphs) {
                List<String> pieces = paragraph.length() > MAX_CHUNK ? splitLong(paragraph) : List.of(paragraph);
                for (String piece : pieces) {
                    if (current.length() > 0 && current.length() + piece.length() + 1 > MAX_CHUNK) {
                        out.add(new Passage(current.toString(), page, 0));
                        current.setLength(0);
                    }
                    if (current.length() > 0) {
                        current.append(' ');
                    }
                    current.append(piece);
                    if (current.length() >= MIN_CHUNK) {
                        out.add(new Passage(current.toString(), page, 0));
                        current.setLength(0);
                    }
                }
            }
            if (Texts.trim(current.toString()).length() >= 30) {
                out.add(new Passage(Texts.trim(current.toString()), page, 0));
            } else if (current.length() > 0 && !out.isEmpty() && java.util.Objects.equals(out.get(out.size() - 1).page(), page)) {
                Passage last = out.remove(out.size() - 1);
                out.add(new Passage(last.text() + " " + Texts.trim(current.toString()), page, 0));
            }
        }
        return out;
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static String headKey(String line) {
        return DIGITS.matcher(Texts.trim(SPACES.matcher(line).replaceAll(" ")).toLowerCase(java.util.Locale.ROOT)).replaceAll("#");
    }

    /**
     * Tira cabeçalhos e rodapés que se repetem (título do livro, autor, número da página): as duas primeiras e as duas
     * últimas linhas de cada página que aparecem, iguais a menos dos números, em pelo menos metade das páginas.
     */
    static String[] withoutRunningHeads(String[] pages) {
        Map<String, Integer> seen = new java.util.HashMap<>();
        List<String[]> lines = new ArrayList<>();
        for (String page : pages) {
            String[] split = page.split("\\n");
            lines.add(split);
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (int i = 0; i < split.length; i++) {
                if (i < 2 || i >= split.length - 2) {
                    String key = headKey(split[i]);
                    if (!key.isEmpty() && key.length() <= 120) {
                        keys.add(key);
                    }
                }
            }
            keys.forEach(k -> seen.merge(k, 1, Integer::sum));
        }
        int threshold = Math.max(2, (pages.length + 1) / 2);
        String[] out = new String[pages.length];
        for (int p = 0; p < pages.length; p++) {
            String[] split = lines.get(p);
            StringBuilder page = new StringBuilder();
            for (int i = 0; i < split.length; i++) {
                boolean edge = i < 2 || i >= split.length - 2;
                if (edge && seen.getOrDefault(headKey(split[i]), 0) >= threshold) {
                    continue;
                }
                page.append(split[i]).append('\n');
            }
            out[p] = page.toString();
        }
        return out;
    }

    /** Parágrafo longo demais: corta por frases (ou à força, se não houver pontuação). */
    private static List<String> splitLong(String paragraph) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : LocalSummarizer.splitSentences(paragraph)) {
            String rest = sentence;
            while (rest.length() > MAX_CHUNK) {
                int cut = rest.lastIndexOf(' ', MAX_CHUNK);
                cut = cut < MIN_CHUNK / 2 ? MAX_CHUNK : cut;
                out.add(rest.substring(0, cut));
                rest = Texts.trim(rest.substring(cut));
            }
            if (current.length() > 0 && current.length() + rest.length() + 1 > MAX_CHUNK) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(rest);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** Os melhores trechos para a pergunta (só os que têm algum termo em comum), do mais ao menos relevante. */
    public static List<Passage> rank(List<Passage> chunks, String question, int limit) {
        List<String> query = terms(question);
        if (query.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> frequencies = new ArrayList<>(chunks.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (Passage chunk : chunks) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> words = LocalSummarizer.words(chunk.text());
            for (String word : words) {
                String term = stem(word);
                tf.merge(term, 1, Integer::sum);
            }
            for (String term : query) {
                if (tf.containsKey(term)) {
                    documentFrequency.merge(term, 1, Integer::sum);
                }
            }
            frequencies.add(tf);
            totalLength += Math.max(1, words.size());
        }
        double average = totalLength / chunks.size();
        int n = chunks.size();
        List<Passage> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = frequencies.get(i);
            int length = tf.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            int matched = 0;
            for (String term : query) {
                Integer f = tf.get(term);
                if (f == null) {
                    continue;
                }
                matched++;
                double df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * Math.max(1, length) / average));
            }
            if (matched == 0) {
                continue;
            }
            // Trechos que cobrem mais termos diferentes da pergunta valem mais do que a repetição de um só.
            score *= 1 + 0.5 * matched / query.size();
            scored.add(new Passage(chunks.get(i).text(), chunks.get(i).page(), score));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : scored;
    }

    /** Resposta extrativa: as duas frases do melhor trecho que mais combinam com a pergunta, na ordem do texto. */
    public static String bestSentences(Passage passage, String question) {
        List<String> query = terms(question);
        List<String> sentences = LocalSummarizer.splitSentences(passage.text());
        if (sentences.size() <= 2) {
            return passage.text();
        }
        List<int[]> scores = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            Set<String> words = new java.util.HashSet<>();
            LocalSummarizer.words(sentences.get(i)).forEach(w -> words.add(stem(w)));
            int hits = (int) query.stream().filter(words::contains).count();
            scores.add(new int[] {i, hits});
        }
        scores.sort((a, b) -> b[1] != a[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
        int first = Math.min(scores.get(0)[0], scores.get(1)[0]);
        int second = Math.max(scores.get(0)[0], scores.get(1)[0]);
        if (scores.get(1)[1] == 0) {
            return sentences.get(scores.get(0)[0]);
        }
        return sentences.get(first) + " " + sentences.get(second);
    }
}
