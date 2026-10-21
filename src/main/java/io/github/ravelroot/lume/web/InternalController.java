package io.github.ravelroot.lume.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.desktop.AppWindow;
import io.github.ravelroot.lume.desktop.Presence;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.library.LibraryService;

/**
 * Recebe o pedido de um segundo Lume.exe (dois cliques de novo, ou "Abrir com" num PDF): importa os arquivos e
 * reabre a janela se ela estiver fechada. Só responde com a chave da instância (conferida no {@link SessionFilter}).
 */
@RestController
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final LibraryService library;
    private final AppWindow window;
    private final Presence presence;
    private final EventBus events;

    public InternalController(LibraryService library, AppWindow window, Presence presence, EventBus events) {
        this.library = library;
        this.window = window;
        this.presence = presence;
        this.events = events;
    }

    @PostMapping("/interno/segunda-instancia")
    public Map<String, Object> secondInstance(@RequestBody(required = false) String raw) {
        Object files = Params.body(raw).get("files");
        List<String> paths = files instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        if (!paths.isEmpty()) {
            Thread worker = new Thread(() -> importAll(paths), "lume-abrir-com");
            worker.setDaemon(true);
            worker.start();
        }
        int windows = presence.windows();
        if (windows == 0) {
            window.open("/");
        }
        return Map.of("windows", windows);
    }

    private void importAll(List<String> paths) {
        for (String file : paths) {
            try {
                library.importFile(Path.of(file), null);
            } catch (ApiException | java.nio.file.InvalidPathException e) {
                String name = Path.of(file).getFileName() == null ? file : Path.of(file).getFileName().toString();
                events.emit("import.failed", Map.of("file", name, "message", e.getMessage()));
                log.info("Importação recusada {}: {}", name, e.getMessage());
            }
        }
    }
}
