package io.github.ravelroot.lume.processing;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Higienização do HTML de leitura gerado a partir de DOCX. O conversor já produz HTML restrito, mas o que vai para a
 * tela passa por uma lista de permissões aqui também (defesa em profundidade), com o jsoup.
 */
public final class HtmlSanitizer {

    private static final String[] TAGS = {"h1", "h2", "h3", "h4", "h5", "h6", "p", "br", "hr", "strong", "b", "em", "i", "u",
        "s", "sup", "sub", "ul", "ol", "li", "blockquote", "table", "thead", "tbody", "tr", "td", "th", "a", "img", "figure",
        "figcaption", "span", "code", "pre"};
    private static final Pattern MEDIA_SRC = Pattern.compile("^media/([A-Za-z0-9_.-]{1,120})$");

    private static final Safelist SAFELIST = buildSafelist();

    private HtmlSanitizer() {
    }

    private static Safelist buildSafelist() {
        Safelist safelist = new Safelist().addTags(TAGS).addAttributes(":all", "data-b");
        for (String heading : new String[] {"h1", "h2", "h3", "h4", "h5", "h6"}) {
            safelist.addAttributes(heading, "id", "data-b");
        }
        return safelist
                .addAttributes("a", "href", "title", "rel")
                .addProtocols("a", "href", "http", "https", "mailto")
                .addAttributes("img", "src", "alt", "width", "height", "loading")
                .addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan")
                .addAttributes("ol", "start", "type", "data-b")
                .addAttributes("p", "class", "data-b");
    }

    /**
     * Deixa só as tags e atributos da lista; imagens só podem vir da pasta media do próprio documento (viram
     * /api/v1/documents/{id}/media/...) e links ganham rel="noopener noreferrer".
     */
    public static String readerHtml(String raw, String documentId) {
        Document dirty = Jsoup.parseBodyFragment(raw == null ? "" : raw);
        for (Element img : dirty.select("img")) {
            Matcher match = MEDIA_SRC.matcher(img.attr("src"));
            if (!match.matches()) {
                img.replaceWith(new Element("span"));
                continue;
            }
            img.attr("src", "/api/v1/documents/" + documentId + "/media/" + match.group(1));
            img.attr("loading", "lazy");
        }
        for (Element link : dirty.select("a")) {
            link.attr("rel", "noopener noreferrer");
        }
        for (Element paragraph : dirty.select("p[class]")) {
            boolean subtitle = Arrays.asList(paragraph.className().split("\\s+")).contains("subtitulo");
            if (subtitle) {
                paragraph.attr("class", "subtitulo");
            } else {
                paragraph.removeAttr("class");
            }
        }
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base).charset("UTF-8");
        return clean.body().html();
    }
}
