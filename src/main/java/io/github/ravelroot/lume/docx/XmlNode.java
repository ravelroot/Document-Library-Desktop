package io.github.ravelroot.lume.docx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Árvore XML simples: nome local, atributos pelo nome local e filhos (outros nós ou textos). Os prefixos de namespace
 * (w:, r:, a:...) são descartados, como no leitor de DOCX da versão em JavaScript.
 */
public final class XmlNode {

    private final String name;
    private final Map<String, String> attrs = new LinkedHashMap<>();
    private final List<Object> children = new ArrayList<>();

    public XmlNode(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Map<String, String> attrs() {
        return attrs;
    }

    public String attr(String key) {
        return attrs.get(key);
    }

    public List<Object> children() {
        return children;
    }

    void addText(String text) {
        children.add(text);
    }

    void addChild(XmlNode node) {
        children.add(node);
    }

    /** Só os elementos filhos (sem os textos). */
    public List<XmlNode> elements() {
        List<XmlNode> out = new ArrayList<>();
        for (Object child : children) {
            if (child instanceof XmlNode node) {
                out.add(node);
            }
        }
        return out;
    }

    /** Todo o texto dentro do nó, em ordem. */
    public String text() {
        StringBuilder out = new StringBuilder();
        appendText(out);
        return out.toString();
    }

    private void appendText(StringBuilder out) {
        for (Object child : children) {
            if (child instanceof String s) {
                out.append(s);
            } else {
                ((XmlNode) child).appendText(out);
            }
        }
    }

    /** Liga/desliga do OOXML: ausente ou qualquer valor diferente de 0/false/off/none conta como ligado. */
    public boolean toggle() {
        String value = attrs.get("val");
        if (value == null) {
            return true;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return !(lower.equals("0") || lower.equals("false") || lower.equals("off") || lower.equals("none"));
    }

    /** Número inteiro do atributo (como parseInt do JavaScript: lê o começo do texto). */
    public int intAttr(String key, int fallback) {
        String value = attrs.get(key);
        if (value == null) {
            return fallback;
        }
        Integer parsed = parseLeadingInt(value);
        return parsed == null ? fallback : parsed;
    }

    static Integer parseLeadingInt(String raw) {
        String value = raw.strip();
        int i = 0;
        if (i < value.length() && (value.charAt(i) == '-' || value.charAt(i) == '+')) {
            i++;
        }
        int start = i;
        while (i < value.length() && Character.isDigit(value.charAt(i)) && value.charAt(i) < 128) {
            i++;
        }
        if (i == start) {
            return null;
        }
        try {
            return Integer.parseInt(value.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Primeiro descendente com o nome dado (busca em profundidade). */
    public XmlNode find(String target) {
        for (XmlNode child : elements()) {
            if (child.name.equals(target)) {
                return child;
            }
            XmlNode found = child.find(target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Percorre a árvore em ordem, chamando enter antes e exit depois dos filhos de cada elemento. */
    public static void walk(XmlNode node, Consumer<XmlNode> enter, Consumer<XmlNode> exit) {
        for (XmlNode child : node.elements()) {
            if (enter != null) {
                enter.accept(child);
            }
            walk(child, enter, exit);
            if (exit != null) {
                exit.accept(child);
            }
        }
    }

    public static void walk(XmlNode node, Consumer<XmlNode> enter) {
        walk(node, enter, null);
    }
}
