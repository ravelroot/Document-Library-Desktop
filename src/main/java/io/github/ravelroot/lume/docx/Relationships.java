package io.github.ravelroot.lume.docx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Relações de uma parte (arquivo .rels): estilos, numeração, imagens e links. */
public final class Relationships {

    public static final String OFFICE_DOCUMENT = "/officeDocument";
    public static final String STYLES = "/styles";
    public static final String NUMBERING = "/numbering";

    /** Uma relação: id, tipo (URI), alvo e se aponta para fora do pacote (links). */
    public record Rel(String id, String type, String target, boolean external) {
    }

    private final Map<String, Rel> byId = new LinkedHashMap<>();

    public static Relationships forPart(DocxPackage pkg, String part) {
        int slash = part.lastIndexOf('/');
        String relsName = (slash >= 0 ? part.substring(0, slash + 1) : "") + "_rels/"
                + (slash >= 0 ? part.substring(slash + 1) : part) + ".rels";
        Relationships rels = new Relationships();
        if (!pkg.has(relsName)) {
            return rels;
        }
        XmlNode.walk(pkg.xml(relsName), node -> {
            String id = node.attr("Id");
            if (!"Relationship".equals(node.name()) || id == null || id.isEmpty()) {
                return;
            }
            String mode = node.attr("TargetMode") == null ? "" : node.attr("TargetMode");
            rels.byId.put(id, new Rel(id, node.attr("Type") == null ? "" : node.attr("Type"), node.attr("Target"),
                    mode.toLowerCase(Locale.ROOT).equals("external")));
        });
        return rels;
    }

    public Rel get(String id) {
        return id == null ? null : byId.get(id);
    }

    public Rel firstOfType(String suffix) {
        for (Rel rel : byId.values()) {
            if (rel.type().endsWith(suffix)) {
                return rel;
            }
        }
        return null;
    }

    public String partOf(String mainPart, String type, String fallback) {
        Rel rel = firstOfType(type);
        if (rel == null || rel.external()) {
            return fallback;
        }
        String resolved = DocxPackage.resolve(mainPart, rel.target());
        return resolved == null || resolved.isEmpty() ? fallback : resolved;
    }
}
