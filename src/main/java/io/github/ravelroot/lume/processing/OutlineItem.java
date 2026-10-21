package io.github.ravelroot.lume.processing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Item do sumário.
 *
 * @param title  texto do item
 * @param page   página (PDF) ou null (DOCX)
 * @param level  nível, começando em 1
 * @param anchor id do título no HTML de leitura (DOCX) ou null (PDF)
 */
public record OutlineItem(String title, Integer page, int level, String anchor) {

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("page", page);
        out.put("level", level);
        out.put("anchor", anchor);
        return out;
    }
}
