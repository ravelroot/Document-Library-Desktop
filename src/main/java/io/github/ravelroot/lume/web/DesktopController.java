package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.AppInfo;
import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.desktop.DesktopIntegration;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.settings.SettingsService;

/**
 * Recursos do sistema para a janela (o que era o preload/IPC no Electron): importar pelo caminho, diálogos, abrir no
 * programa padrão, mostrar no Explorador, salvar cópias, abrir links e guardar o tamanho da janela.
 */
@RestController
@RequestMapping("/api/v1/desktop")
public class DesktopController {

    private static final Pattern WEB_LINK = Pattern.compile("^(https?:|mailto:)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAD_FILENAME = Pattern.compile("[\\\\/:*?\"<>|]+");

    private final LibraryService library;
    private final DocumentStore store;
    private final DesktopIntegration desktop;
    private final SettingsService settings;
    private final AppInfo appInfo;
    private final LumeProperties properties;

    public DesktopController(LibraryService library, DocumentStore store, DesktopIntegration desktop, SettingsService settings,
                             AppInfo appInfo, LumeProperties properties) {
        this.library = library;
        this.store = store;
        this.desktop = desktop;
        this.settings = settings;
        this.appInfo = appInfo;
        this.properties = properties;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Importa arquivos pelo caminho no disco (sem enviar os bytes pela rede). */
    @PostMapping("/import")
    public List<Map<String, Object>> importFiles(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        Object collection = body.get("collection");
        String collectionId = Ids.isUuid(collection) ? String.valueOf(collection) : null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (String file : strings(body.get("paths")).stream().limit(2000).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file);
            try {
                Path source = path(file);
                if (source == null) {
                    throw new ApiException("Caminho inválido.");
                }
                LibraryService.ImportResult result = library.importFile(source, collectionId);
                item.put("ok", true);
                item.putAll(result.toMap());
            } catch (ApiException e) {
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            items.add(item);
        }
        return items;
    }

    /** Recebe o arquivo em si (arrastar e soltar na janela, quando o caminho não é conhecido). */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam String name, @RequestParam(required = false) String collection,
                                      HttpServletRequest request) throws IOException {
        String collectionId = Ids.isUuid(collection) ? collection : null;
        try (InputStream in = request.getInputStream()) {
            return library.importStream(in, name, collectionId).toMap();
        }
    }

    /** Pastas soltas na janela viram a lista dos PDFs e DOCX que estão dentro delas. */
    @PostMapping("/expand")
    public List<String> expand(@RequestBody(required = false) String raw) {
        List<String> files = new ArrayList<>();
        for (String item : strings(Params.body(raw).get("paths"))) {
            Path source = path(item);
            if (source != null && Files.isDirectory(source)) {
                library.scanFolder(source).forEach(p -> files.add(p.toString()));
            } else {
                files.add(item);
            }
        }
        return files.size() > 2000 ? files.subList(0, 2000) : files;
    }

    @PostMapping("/pick-files")
    public List<String> pickFiles() {
        return desktop.pickFiles().stream().map(Path::toString).toList();
    }

    @PostMapping("/pick-folder")
    public Map<String, Object> pickFolder() {
        Path folder = desktop.pickFolder();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folder", folder == null ? null : folder.toString());
        out.put("files", folder == null ? List.of() : library.scanFolder(folder).stream().map(Path::toString).toList());
        return out;
    }

    private Row document(Map<String, Object> body) {
        return store.requireRow(Params.uuid(Values.text(body.get("id"))));
    }

    @PostMapping("/open-file")
    public Map<String, Object> openFile(@RequestBody(required = false) String raw) {
        Row d = document(Params.body(raw));
        try {
            desktop.openPath(store.files(d).file());
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "Nenhum programa abriu o arquivo: " + e.getMessage());
        }
    }

    @PostMapping("/show-in-folder")
    public Map<String, Object> showInFolder(@RequestBody(required = false) String raw) throws IOException {
        Row d = document(Params.body(raw));
        desktop.showInFolder(store.files(d).file());
        return Map.of("ok", true);
    }

    @PostMapping("/save-copy")
    public Map<String, Object> saveCopy(@RequestBody(required = false) String raw) throws IOException {
        Row d = document(Params.body(raw));
        String kind = d.str("kind");
        Path target = desktop.saveFile("Salvar uma cópia", desktop.downloadsFolder().resolve(d.str("original_name")), kind.toUpperCase(), kind);
        if (target == null) {
            return Map.of("ok", false);
        }
        Files.copy(store.files(d).file(), target, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("ok", true, "path", target.toString());
    }

    @PostMapping("/save-text")
    public Map<String, Object> saveText(@RequestBody(required = false) String raw) throws IOException {
        Map<String, Object> body = Params.body(raw);
        String filename = BAD_FILENAME.matcher(Values.truthy(body.get("filename")) ? Values.text(body.get("filename")) : "anotacoes.md")
                .replaceAll(" ");
        Path target = desktop.saveFile("Salvar", desktop.documentsFolder().resolve(filename), "Markdown", "md", "txt");
        if (target == null) {
            return Map.of("ok", false);
        }
        Files.writeString(target, Values.text(body.get("content")), StandardCharsets.UTF_8);
        return Map.of("ok", true, "path", target.toString());
    }

    @PostMapping("/open-data-folder")
    public Map<String, Object> openDataFolder() throws IOException {
        desktop.openPath(properties.dataDir());
        return Map.of("ok", true);
    }

    @PostMapping("/open-external")
    public Map<String, Object> openExternal(@RequestBody(required = false) String raw) throws IOException {
        String url = Values.text(Params.body(raw).get("url"));
        if (!WEB_LINK.matcher(url).find()) {
            return Map.of("ok", false);
        }
        desktop.openExternal(URI.create(url).toString());
        return Map.of("ok", true);
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>(appInfo.toMap());
        out.put("window", desktop.available() ? "Microsoft Edge (modo aplicativo)" : "sem janela");
        return out;
    }

    /** A janela informa posição e tamanho para abrir do mesmo jeito da próxima vez. */
    @PutMapping("/window")
    public Map<String, Object> window(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        Map<String, Object> bounds = new LinkedHashMap<>();
        for (String key : List.of("x", "y", "width", "height")) {
            double value = Values.number(body.get(key));
            if (!Double.isNaN(value)) {
                bounds.put(key, (int) Math.round(value));
            }
        }
        bounds.put("maximized", Values.truthy(body.get("maximized")));
        settings.saveWindow(bounds);
        return bounds;
    }
}
