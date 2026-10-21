package io.github.ravelroot.lume.docx;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estilos do documento (styles.xml).
 *
 * <p>O Word grava o nome interno dos estilos nativos em inglês ("heading 1") mesmo quando o id está traduzido
 * ("Ttulo1"), por isso a classificação usa o nome.</p>
 */
public final class Styles {

    private static final Pattern NUMBERED_LIST_STYLE = Pattern.compile("^list (?:bullet|number) ([2-9])$");

    private static final class Style {
        String name = "";
        String basedOn;
        Integer outlineLevel;
        Integer numId;
        Integer ilvl;
        Boolean bold;
        Boolean italic;
    }

    private final Map<String, Style> byId = new HashMap<>();
    private String defaultParagraphStyle;
    private String language;

    public String language() {
        return language;
    }

    private static boolean isOn(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("1") || lower.equals("true") || lower.equals("on");
    }

    public static Styles read(DocxPackage pkg, String part) {
        Styles styles = new Styles();
        if (part == null || !pkg.has(part)) {
            return styles;
        }
        final Style[] current = {null};
        final boolean[] inDefaults = {false};
        final boolean[] inRunProps = {false};
        XmlNode.walk(pkg.xml(part), node -> {
            Style style = current[0];
            switch (node.name()) {
                case "docDefaults" -> inDefaults[0] = true;
                case "style" -> {
                    Style created = new Style();
                    current[0] = created;
                    String id = node.attr("styleId");
                    if (id != null && !id.isEmpty()) {
                        styles.byId.put(id, created);
                        String isDefault = node.attr("default");
                        if ("paragraph".equals(node.attr("type")) && isDefault != null && isOn(isDefault)) {
                            styles.defaultParagraphStyle = id;
                        }
                    }
                }
                case "name" -> {
                    if (style != null) {
                        String val = node.attr("val");
                        style.name = (val == null ? "" : val).strip().toLowerCase(Locale.ROOT);
                    }
                }
                case "basedOn" -> {
                    if (style != null) {
                        String val = node.attr("val");
                        style.basedOn = val == null || val.isEmpty() ? null : val;
                    }
                }
                case "outlineLvl" -> {
                    if (style != null) {
                        style.outlineLevel = node.intAttr("val", 9);
                    }
                }
                case "numId" -> {
                    if (style != null) {
                        style.numId = node.intAttr("val", 0);
                    }
                }
                case "ilvl" -> {
                    if (style != null) {
                        style.ilvl = node.intAttr("val", 0);
                    }
                }
                case "rPr" -> inRunProps[0] = true;
                case "b" -> {
                    if (style != null && inRunProps[0]) {
                        style.bold = node.toggle();
                    }
                }
                case "i" -> {
                    if (style != null && inRunProps[0]) {
                        style.italic = node.toggle();
                    }
                }
                case "lang" -> {
                    if (inDefaults[0] && styles.language == null) {
                        String val = node.attr("val");
                        styles.language = val == null || val.isEmpty() ? null : val;
                    }
                }
                default -> {
                    // outros elementos não interessam
                }
            }
        }, node -> {
            switch (node.name()) {
                case "docDefaults" -> inDefaults[0] = false;
                case "style" -> current[0] = null;
                case "rPr" -> inRunProps[0] = false;
                default -> {
                    // nada
                }
            }
        });
        return styles;
    }

    private Style style(String styleId) {
        String id = styleId != null ? styleId : defaultParagraphStyle != null ? defaultParagraphStyle : "";
        return byId.get(id);
    }

    public String name(String styleId) {
        Style style = style(styleId);
        return style == null ? "" : style.name;
    }

    /** title, subtitle, quote, heading ou normal. */
    public String role(String styleId) {
        String name = name(styleId);
        if (name.equals("title")) {
            return "title";
        }
        if (name.equals("subtitle")) {
            return "subtitle";
        }
        if (name.equals("quote") || name.equals("intense quote")) {
            return "quote";
        }
        if (headingLevel(styleId) > 0) {
            return "heading";
        }
        return "normal";
    }

    /** Nível de título 1–9 pelo nome ("heading 2") ou pelo nível de estrutura do estilo; 0 se não for título. */
    public int headingLevel(String styleId) {
        Set<String> seen = new HashSet<>();
        String id = styleId != null ? styleId : defaultParagraphStyle;
        while (id != null && seen.add(id)) {
            Style style = byId.get(id);
            if (style == null) {
                break;
            }
            if (style.name.startsWith("heading ")) {
                Integer level = XmlNode.parseLeadingInt(style.name.substring(8));
                if (level != null && level >= 1 && level <= 9) {
                    return level;
                }
            }
            if (style.outlineLevel != null) {
                return style.outlineLevel >= 0 && style.outlineLevel < 9 ? style.outlineLevel + 1 : 0;
            }
            id = style.basedOn;
        }
        return 0;
    }

    /** Numeração herdada do estilo (como "List Bullet"): {numId, ilvl} ou null. */
    public int[] numbering(String styleId) {
        Set<String> seen = new HashSet<>();
        String id = styleId;
        Integer numId = null;
        Integer ilvl = null;
        while (id != null && seen.add(id)) {
            Style style = byId.get(id);
            if (style == null) {
                break;
            }
            if (numId == null && style.numId != null) {
                numId = style.numId;
            }
            if (ilvl == null && style.ilvl != null) {
                ilvl = style.ilvl;
            }
            id = style.basedOn;
        }
        return numId == null ? null : new int[] {numId, ilvl == null ? 0 : ilvl};
    }

    /** "List Bullet 2", "List Number 3": o nível vem do nome do estilo. */
    public int listLevelFromName(String styleId) {
        Matcher match = NUMBERED_LIST_STYLE.matcher(name(styleId));
        return match.matches() ? Integer.parseInt(match.group(1)) - 1 : 0;
    }

    /** Estilos de caractere "Strong" e "Emphasis" valem como negrito e itálico. */
    public boolean runBold(String styleId) {
        Style style = styleId == null ? null : byId.get(styleId);
        return style != null && (Boolean.TRUE.equals(style.bold) || style.name.equals("strong"));
    }

    public boolean runItalic(String styleId) {
        Style style = styleId == null ? null : byId.get(styleId);
        return style != null && (Boolean.TRUE.equals(style.italic) || style.name.equals("emphasis"));
    }
}
