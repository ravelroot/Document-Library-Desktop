package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.desktop.DesktopIntegration;
import io.github.ravelroot.lume.library.InsightsService;
import io.github.ravelroot.lume.tools.ToolsService;

/** Tela Ferramentas (backup, exportações, manutenção) e tela Estatísticas. */
@RestController
@RequestMapping("/api/v1")
public class ToolsController {

    private final ToolsService tools;
    private final InsightsService insights;
    private final DesktopIntegration desktop;

    public ToolsController(ToolsService tools, InsightsService insights, DesktopIntegration desktop) {
        this.tools = tools;
        this.insights = insights;
        this.desktop = desktop;
    }

    @GetMapping("/insights")
    public Map<String, Object> insights() {
        return insights.insights();
    }

    /** Backup completo: pergunta onde salvar e grava o .zip. */
    @PostMapping("/tools/backup")
    public Map<String, Object> backup() throws IOException {
        Path target = desktop.saveFile("Salvar backup do Lume", desktop.documentsFolder().resolve(ToolsService.backupName()), "Arquivo ZIP", "zip");
        if (target == null) {
            return Map.of("ok", false);
        }
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            target = target.resolveSibling(target.getFileName() + ".zip");
        }
        return tools.backupTo(target);
    }

    @GetMapping("/tools/export/catalog")
    public Map<String, Object> catalog(@RequestParam(required = false) String format, @RequestParam(required = false) String ids) {
        List<String> only = ids == null || ids.isBlank() ? List.of() : Arrays.stream(ids.split(",")).map(String::trim).toList();
        return tools.exportCatalog(format, only);
    }

    /** Uma nota em Markdown por documento numa pasta escolhida (Obsidian, Logseq, Notion). */
    @PostMapping("/tools/export/markdown-folder")
    public Map<String, Object> markdownFolder() throws IOException {
        Path folder = desktop.pickFolder();
        if (folder == null) {
            return Map.of("ok", false);
        }
        int files = tools.exportMarkdownFolder(folder);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("folder", folder.toString());
        out.put("files", files);
        return out;
    }

    @PostMapping("/tools/maintenance")
    public Map<String, Object> maintenance(@RequestBody(required = false) String raw) {
        return tools.maintenance(Values.text(Params.body(raw).get("action")));
    }
}
