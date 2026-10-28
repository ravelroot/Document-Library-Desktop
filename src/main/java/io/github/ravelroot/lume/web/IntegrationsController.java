package io.github.ravelroot.lume.web;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.desktop.DesktopIntegration;
import io.github.ravelroot.lume.integrations.WatchFolderService;
import io.github.ravelroot.lume.integrations.WebhookService;

/** Tela Integrações: pastas monitoradas (importação automática) e webhooks. */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationsController {

    private final WatchFolderService folders;
    private final WebhookService webhooks;
    private final DesktopIntegration desktop;

    public IntegrationsController(WatchFolderService folders, WebhookService webhooks, DesktopIntegration desktop) {
        this.folders = folders;
        this.webhooks = webhooks;
        this.desktop = desktop;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watch_folders", folders.list());
        out.put("webhooks", webhooks.list());
        out.put("scan_interval_s", folders.intervalS());
        out.put("webhook_events", WebhookService.eventList());
        return out;
    }

    /* ---------- Pastas monitoradas ---------- */

    /** Sem caminho no corpo, abre o seletor de pasta; se a pessoa cancelar, devolve {ok: false}. */
    @PostMapping("/watch-folders")
    public Map<String, Object> addFolder(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        String path = Values.text(body.get("path"));
        if (path.isBlank()) {
            Path picked = desktop.pickFolder();
            if (picked == null) {
                return Map.of("ok", false);
            }
            path = picked.toString();
        }
        folders.add(path, body.get("collection"));
        return overview();
    }

    @RequestMapping(path = "/watch-folders/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> updateFolder(@PathVariable String id, @RequestBody(required = false) String raw) {
        folders.update(Params.uuid(id), Params.body(raw));
        return overview();
    }

    @DeleteMapping("/watch-folders/{id}")
    public Map<String, Object> removeFolder(@PathVariable String id) {
        folders.remove(Params.uuid(id));
        return overview();
    }

    @PostMapping("/watch-folders/scan")
    public Map<String, Object> scan() {
        return folders.scan(true).toMap();
    }

    /* ---------- Webhooks ---------- */

    @PostMapping("/webhooks")
    public Map<String, Object> addWebhook(@RequestBody(required = false) String raw) {
        webhooks.add(Params.body(raw));
        return overview();
    }

    @RequestMapping(path = "/webhooks/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> updateWebhook(@PathVariable String id, @RequestBody(required = false) String raw) {
        webhooks.update(Params.uuid(id), Params.body(raw));
        return overview();
    }

    @DeleteMapping("/webhooks/{id}")
    public Map<String, Object> removeWebhook(@PathVariable String id) {
        webhooks.remove(Params.uuid(id));
        return overview();
    }

    @PostMapping("/webhooks/{id}/test")
    public Map<String, Object> testWebhook(@PathVariable String id) {
        return webhooks.test(Params.uuid(id));
    }
}
