package io.github.ravelroot.lume.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.ravelroot.lume.core.Texts;

/**
 * Resumo extrativo local: escolhe as frases que melhor representam o texto, sem chamar serviço externo.
 *
 * <p>É o provedor padrão, então o Lume funciona sem chave de API e sem internet. A pontuação combina frequência dos
 * termos (com peso extra para palavras do título e dos títulos de seção), posição no texto e corte de redundância.
 * Os cabeçalhos e rodapés repetidos, a capa e os títulos de seção são descartados antes.</p>
 */
public final class LocalSummarizer {

    static final Set<String> STOPWORDS_PT = wordSet("""
            a à às ao aos as o os um uma uns umas de da das do dos dum duma em na nas no nos num numa por pela pelas pelo pelos
            para pra com sem sob sobre entre até após ante contra desde e ou mas nem que se como quando onde porque porquê pois
            já também só ainda muito muita muitos muitas pouco pouca mais menos tão tanto tanta todo toda todos todas cada outro
            outra outros outras mesmo mesma mesmos mesmas este esta estes estas esse essa esses essas aquele aquela aqueles
            aquelas isto isso aquilo eu tu ele ela nós vós eles elas me te lhe nos vos lhes meu minha meus minhas seu sua seus
            suas nosso nossa nossos nossas ser é são era eram foi foram será serão seja sejam sendo sido estar está estão estava
            estavam esteve estiveram ter tem têm tinha tinham teve tiveram terá haver há havia houve fazer faz fazem feito pode
            podem poderá deve devem deverá vai vão ir sim não qual quais quem cujo cuja cujos cujas lá aqui ali aí então assim
            bem cada vez vezes ano anos dia dias forma parte caso tipo além apenas sempre nunca agora antes depois durante
            você vocês pode podem poder fazer isso aqui tudo nada algo alguns algumas qualquer coisa coisas meses mês semana
            hora horas primeiro primeira segundo segunda exemplo exemplos sobre outro null true false item itens página páginas
            """);

    static final Set<String> STOPWORDS_EN = wordSet("""
            the a an and or but of to in on at by for with from as is are was were be been being it its this that these those
            which who whom what when where why how not no yes can could should would may might must will shall do does did
            done have has had having into over under than then there their they them he she his her we our you your i me my
            also only just more most such other some any each all both few many much very own same so too out up down about
            """);

    static final Set<String> STOPWORDS_ES =
            wordSet("el la los las un una unos unas del al y o pero que en por para con sin es son fue ser está");

    static final Set<String> STOP_ALL;

    static {
        Set<String> all = new LinkedHashSet<>(STOPWORDS_PT);
        all.addAll(STOPWORDS_EN);
        all.addAll(STOPWORDS_ES);
        STOP_ALL = Set.copyOf(all);
    }

    private static final Set<String> ABBREVIATIONS = Set.of("sr", "sra", "dr", "dra", "prof", "profa", "p", "pág", "págs",
            "ex", "etc", "art", "fig", "cap", "vol", "nº", "n", "obs", "ltda", "s.a", "av", "e.g", "i.e", "mr", "mrs", "no");

    private static final Pattern WORD_RE = Pattern.compile("[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ0-9'-]*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])[\"”’)\\]]*\\s+(?=[\"“‘(\\[]?[A-ZÀ-Ý0-9])");
    private static final Pattern CODE_RE = Pattern.compile("[=_{}<>\\\\]|\\(\\)|\\b[A-Z]{3,}\\b.*\\b[A-Z]{3,}\\b");
    private static final Pattern DIGITS_AND_SPACES = Pattern.compile("[\\d\\s]+");
    private static final Pattern ONLY_SYMBOLS = Pattern.compile("^[\\d\\s.\\-–—|/·•]+$");
    private static final Pattern SENTENCE_FINAL = Pattern.compile("[.!?…][\"”’)]*$");
    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.+$");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[,;:—-]+$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private LocalSummarizer() {
    }

    private static Set<String> wordSet(String block) {
        return Arrays.stream(block.trim().split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Palavras do texto, em minúsculas. */
    public static List<String> words(Object text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD_RE.matcher(text == null ? "" : String.valueOf(text));
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    static String stripAccents(String text) {
        return COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    }

    /** "pt", "en", "es" ou "" quando não dá para saber. */
    public static String guessLanguage(String text) {
        String value = text == null ? "" : text;
        List<String> sample = words(value.length() > 20000 ? value.substring(0, 20000) : value);
        if (sample.isEmpty()) {
            return "";
        }
        Map<String, Long> scores = new LinkedHashMap<>();
        scores.put("pt", sample.stream().filter(STOPWORDS_PT::contains).count());
        scores.put("en", sample.stream().filter(STOPWORDS_EN::contains).count());
        scores.put("es", sample.stream().filter(STOPWORDS_ES::contains).count());
        String lang = "pt";
        for (String key : List.of("pt", "en", "es")) {
            if (scores.get(key) > scores.get(lang)) {
                lang = key;
            }
        }
        return scores.get(lang) >= Math.max(5, sample.size() * 0.08) ? lang : "";
    }

    private static String norm(String line) {
        return DIGITS_AND_SPACES.matcher(line).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    private static boolean isUpper(char c) {
        return isLetter(c) && c == Character.toUpperCase(c) && c != Character.toLowerCase(c);
    }

    private static boolean isLower(char c) {
        return isLetter(c) && c == Character.toLowerCase(c) && c != Character.toUpperCase(c);
    }

    private static char last(String text) {
        return text.charAt(text.length() - 1);
    }

    public static List<String> cleanText(String text) {
        return cleanText(text, false);
    }

    /**
     * Parágrafos de texto corrido: junta linhas quebradas, desfaz hifenização e descarta capa, cabeçalhos e rodapés
     * repetidos (mesmo com o número da página mudando) e títulos de seção.
     */
    public static List<String> cleanText(String text, boolean onePerLine) {
        String value = text == null ? "" : text;
        String[] pages = value.contains("\f") ? value.split("\f", -1) : new String[] {value};
        Counter seen = new Counter();
        for (String page : pages) {
            Set<String> keys = new LinkedHashSet<>();
            for (String line : page.split("\n", -1)) {
                if (!Texts.trim(line).isEmpty()) {
                    keys.add(norm(line));
                }
            }
            for (String key : keys) {
                seen.add(key, 1);
            }
        }
        Set<String> repeated = new HashSet<>();
        for (Map.Entry<String, Integer> entry : seen.entrySet()) {
            if (!entry.getKey().isEmpty() && pages.length >= 3 && entry.getValue() >= Math.max(2, pages.length * 0.3)) {
                repeated.add(entry.getKey());
            }
        }

        List<String> paragraphs = new ArrayList<>();
        for (int index = 0; index < pages.length; index++) {
            String page = pages[index];
            if (index == 0 && pages.length > 2 && words(page).size() < 80) {
                continue; // capa
            }
            List<String> lines = Arrays.stream(page.split("\n", -1)).map(Texts::trim).toList();
            List<Integer> lengths = lines.stream().filter(l -> l.length() > 20).map(String::length).sorted().toList();
            int median = lengths.isEmpty() ? 80 : lengths.get(lengths.size() / 2);
            List<String> current = new ArrayList<>();
            for (String line : lines) {
                if (line.isEmpty() || repeated.contains(norm(line)) || ONLY_SYMBOLS.matcher(line).matches()) {
                    flush(current, paragraphs);
                    continue;
                }
                boolean endsSentence = ".!?…:;\"”)".indexOf(last(line)) >= 0;
                boolean heading = !endsSentence && !line.endsWith("-")
                        && (line.length() < 0.65 * median || (onePerLine && line.length() < 90));
                if (heading) {
                    flush(current, paragraphs);
                    continue;
                }
                if (!current.isEmpty() && current.get(current.size() - 1).endsWith("-") && isLower(line.charAt(0))) {
                    String previous = current.get(current.size() - 1);
                    current.set(current.size() - 1, previous.substring(0, previous.length() - 1) + line);
                } else {
                    current.add(line);
                }
                if (onePerLine) {
                    flush(current, paragraphs);
                }
            }
            flush(current, paragraphs);
        }
        List<String> out = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String clean = Texts.trim(SPACES.matcher(paragraph).replaceAll(" "));
            if (!clean.isEmpty()) {
                out.add(clean);
            }
        }
        return out;
    }

    private static void flush(List<String> current, List<String> paragraphs) {
        if (!current.isEmpty()) {
            paragraphs.add(String.join(" ", current));
            current.clear();
        }
    }

    public static List<String> splitSentences(String paragraph) {
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_END.split(paragraph, -1)) {
            if (!sentences.isEmpty()) {
                String previous = sentences.get(sentences.size() - 1);
                String[] tokens = TRAILING_DOTS.matcher(previous).replaceAll("").split(" ", -1);
                String lastWord = tokens[tokens.length - 1].toLowerCase(Locale.ROOT);
                if (ABBREVIATIONS.contains(lastWord) || (lastWord.length() == 1 && isLetter(lastWord.charAt(0)))) {
                    sentences.set(sentences.size() - 1, previous + " " + part);
                    continue;
                }
            }
            sentences.add(Texts.trim(part));
        }
        return sentences.stream().filter(s -> !s.isEmpty()).toList();
    }

    static boolean isCandidate(String sentence) {
        if (sentence.length() < 40 || sentence.length() > 320) {
            return false;
        }
        long pipes = sentence.chars().filter(c -> c == '|').count();
        if (!SENTENCE_FINAL.matcher(sentence).find() || pipes > 1 || CODE_RE.matcher(sentence).find()) {
            return false;
        }
        long letters = sentence.codePoints().filter(Character::isLetter).count();
        if ((double) letters / sentence.length() < 0.7) {
            return false;
        }
        char first = sentence.charAt(0);
        return isUpper(first) || "“\"(".indexOf(first) >= 0 || Character.isDigit(first) && first < 128;
    }

    public static List<String> keywords(String text, String title, Collection<String> headings, String author, boolean byLine) {
        return keywords(text, title, headings, 4, author, byLine);
    }

    public static List<String> keywords(String text, String title, Collection<String> headings, int limit, String author, boolean byLine) {
        Set<String> stop = new HashSet<>(STOP_ALL);
        stop.addAll(words(author));
        String value = text == null ? "" : text;
        String sliced = value.length() > 300_000 ? value.substring(0, 300_000) : value;
        String body = String.join("\n", cleanText(sliced, byLine));
        if (body.isEmpty()) {
            body = sliced;
        }
        List<String> sequence = words(body);
        boolean[] useful = new boolean[sequence.size()];
        Counter counts = new Counter();
        for (int i = 0; i < sequence.size(); i++) {
            String w = sequence.get(i);
            useful[i] = w.length() > 3 && !stop.contains(w) && !HAS_DIGIT.matcher(w).find();
            if (useful[i]) {
                counts.add(w, 1);
            }
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        for (String w : words(title)) {
            if (counts.containsKey(w)) {
                counts.add(w, 3);
            }
        }
        for (String heading : headings == null ? List.<String>of() : headings) {
            for (String w : words(heading)) {
                if (counts.containsKey(w)) {
                    counts.add(w, 1);
                }
            }
        }
        Counter bigrams = new Counter();
        for (int i = 0; i < sequence.size() - 1; i++) {
            if (useful[i] && useful[i + 1]) {
                bigrams.add(sequence.get(i) + " " + sequence.get(i + 1), 1);
            }
        }
        List<String> picked = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : bigrams.mostCommon(20)) {
            String[] pair = entry.getKey().split(" ");
            if (entry.getValue() >= 3 && !pair[0].equals(pair[1]) && picked.size() < 2) {
                picked.add(entry.getKey());
            }
        }
        Set<String> used = new LinkedHashSet<>();
        for (String pair : picked) {
            for (String part : pair.split(" ")) {
                used.add(stripAccents(part));
            }
        }
        for (Map.Entry<String, Integer> entry : counts.mostCommon(60)) {
            if (picked.size() >= limit) {
                break;
            }
            String w = entry.getKey();
            String key = stripAccents(w);
            boolean similar = used.stream().anyMatch(u -> prefix(key).equals(prefix(u)));
            if (!used.contains(key) && !similar) {
                picked.add(w);
                used.add(key);
            }
        }
        return picked.size() > limit ? picked.subList(0, limit) : picked;
    }

    private static String prefix(String text) {
        return text.length() > 5 ? text.substring(0, 5) : text;
    }

    public static String summarize(String text, String title, Collection<String> headings, boolean byLine) {
        return summarize(text, title, headings, 320, byLine);
    }

    public static String summarize(String text, String title, Collection<String> headings, int maxChars, boolean byLine) {
        List<String> paragraphs = cleanText(text, byLine);
        List<String> sentences = new ArrayList<>();
        for (String paragraph : paragraphs) {
            for (String sentence : splitSentences(paragraph)) {
                if (isCandidate(sentence)) {
                    sentences.add(sentence);
                }
            }
        }
        if (sentences.isEmpty()) {
            String flat = String.join(" ", paragraphs);
            flat = flat.length() > maxChars ? flat.substring(0, maxChars) : flat;
            return flat.isEmpty() ? "" : shorten(flat, maxChars);
        }

        Counter freq = new Counter();
        for (String sentence : sentences) {
            for (String w : words(sentence)) {
                if (!STOP_ALL.contains(w) && w.length() > 2) {
                    freq.add(w, 1);
                }
            }
        }
        double top = freq.isEmpty() ? 1 : freq.mostCommon(1).get(0).getValue();
        Set<String> boost = new HashSet<>();
        for (String w : words(title)) {
            if (!STOP_ALL.contains(w)) {
                boost.add(w);
            }
        }
        for (String heading : headings == null ? List.<String>of() : headings) {
            for (String w : words(heading)) {
                if (!STOP_ALL.contains(w)) {
                    boost.add(w);
                }
            }
        }

        int total = sentences.size();
        List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            String sentence = sentences.get(index);
            Set<String> terms = new LinkedHashSet<>();
            for (String w : words(sentence)) {
                if (!STOP_ALL.contains(w) && w.length() > 2) {
                    terms.add(w);
                }
            }
            if (terms.isEmpty()) {
                continue;
            }
            double weight = 0;
            for (String w : terms) {
                weight += freq.getOrDefault(w, 0) / top + (boost.contains(w) ? 0.6 : 0);
            }
            weight /= Math.sqrt(terms.size());
            double position = index == 0 ? 2.0 : 1.0 + 0.6 * Math.max(0, 1 - index / Math.max(total * 0.25, 1));
            scored.add(new Scored(weight * position, index, sentence, terms));
        }
        scored.sort((a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : Integer.compare(b.index, a.index));

        List<Scored> chosen = new ArrayList<>();
        int length = 0;
        for (Scored candidate : scored) {
            boolean redundant = chosen.stream().anyMatch(other -> {
                long inter = candidate.terms.stream().filter(other.terms::contains).count();
                Set<String> union = new HashSet<>(candidate.terms);
                union.addAll(other.terms);
                return (double) inter / Math.max(union.size(), 1) > 0.45;
            });
            if (redundant) {
                continue;
            }
            if (length + candidate.sentence.length() + 1 > maxChars && !chosen.isEmpty()) {
                continue;
            }
            chosen.add(candidate);
            length += candidate.sentence.length() + 1;
            if (length > maxChars * 0.7 || chosen.size() >= 3) {
                break;
            }
        }
        chosen.sort((a, b) -> Integer.compare(a.index, b.index));
        return shorten(chosen.stream().map(s -> s.sentence).collect(Collectors.joining(" ")), maxChars + 40);
    }

    /** Corta no último espaço antes do limite e põe reticências. */
    public static String shorten(String text, int limit) {
        String value = Texts.trim(text == null ? "" : text);
        if (value.length() <= limit) {
            return value;
        }
        String cut = value.substring(0, limit);
        int space = cut.lastIndexOf(' ');
        String base = space > 0 ? cut.substring(0, space) : cut;
        return TRAILING_PUNCT.matcher(base).replaceAll("") + "…";
    }

    private record Scored(double score, int index, String sentence, Set<String> terms) {
    }

    /** Contador que preserva a ordem de inserção nos empates, como o Counter do Python. */
    private static final class Counter extends LinkedHashMap<String, Integer> {

        void add(String key, int n) {
            merge(key, n, Integer::sum);
        }

        List<Map.Entry<String, Integer>> mostCommon(int limit) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            return entries.size() > limit ? entries.subList(0, limit) : entries;
        }
    }
}
