package io.github.ravelroot.lume.docx;

import java.util.HashMap;
import java.util.Map;

/** numbering.xml: diz se o item (numId, nível) é marcador (ul) ou numerado (ol). */
public final class Numbering {

    private final Map<Integer, Map<Integer, String>> formatsByAbstract = new HashMap<>();
    private final Map<Integer, Integer> abstractByNum = new HashMap<>();
    private final Map<Integer, Map<Integer, String>> overrides = new HashMap<>();

    public static Numbering read(DocxPackage pkg, String part) {
        Numbering numbering = new Numbering();
        if (part == null || !pkg.has(part)) {
            return numbering;
        }
        final Integer[] abstractId = {null};
        final Integer[] numId = {null};
        final Integer[] level = {null};
        XmlNode.walk(pkg.xml(part), node -> {
            switch (node.name()) {
                case "abstractNum" -> {
                    abstractId[0] = node.intAttr("abstractNumId", -1);
                    numId[0] = null;
                }
                case "num" -> {
                    numId[0] = node.intAttr("numId", -1);
                    abstractId[0] = null;
                }
                case "abstractNumId" -> {
                    if (numId[0] != null) {
                        numbering.abstractByNum.put(numId[0], node.intAttr("val", -1));
                    }
                }
                case "lvl", "lvlOverride" -> level[0] = node.intAttr("ilvl", 0);
                case "numFmt" -> {
                    String format = node.attr("val");
                    if (format != null && !format.isEmpty() && level[0] != null) {
                        if (abstractId[0] != null) {
                            numbering.formatsByAbstract.computeIfAbsent(abstractId[0], k -> new HashMap<>()).put(level[0], format);
                        } else if (numId[0] != null) {
                            numbering.overrides.computeIfAbsent(numId[0], k -> new HashMap<>()).put(level[0], format);
                        }
                    }
                }
                default -> {
                    // outros elementos não interessam
                }
            }
        }, node -> {
            switch (node.name()) {
                case "abstractNum" -> abstractId[0] = null;
                case "num" -> numId[0] = null;
                case "lvl", "lvlOverride" -> level[0] = null;
                default -> {
                    // nada
                }
            }
        });
        return numbering;
    }

    String format(int numId, int level) {
        Map<Integer, String> override = overrides.get(numId);
        String format = override == null ? null : override.get(level);
        if (format == null) {
            Integer abstractId = abstractByNum.get(numId);
            if (abstractId != null) {
                Map<Integer, String> formats = formatsByAbstract.get(abstractId);
                format = formats == null ? null : formats.get(level);
            }
        }
        return format;
    }

    /** true para listas numeradas; marcadores e numeração desconhecida viram lista com marcador. */
    public boolean isOrdered(int numId, int level) {
        String format = null;
        for (int lvl = level; lvl >= 0 && format == null; lvl--) {
            format = format(numId, lvl);
        }
        return format != null && !format.equals("bullet") && !format.equals("none");
    }
}
