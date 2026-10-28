package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.AppInfo;
import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.settings.SettingsService;

/**
 * A página única da interface (index.html) com os dados de partida embutidos: perfil, configuração da IA e versão.
 * Todas as rotas da interface (/, /documento/..., /ler/...) chegam aqui pelo {@link SpaFilter}.
 */
@RestController
public class IndexController {

    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'wasm-unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'none'");

    private final SettingsService settings;
    private final AppInfo appInfo;
    private final LumeProperties properties;
    private volatile String cachedIndex;

    public IndexController(SettingsService settings, AppInfo appInfo, LumeProperties properties) {
        this.settings = settings;
        this.appInfo = appInfo;
        this.properties = properties;
    }

    private String template() throws IOException {
        String index = cachedIndex;
        if (index == null || properties.dev()) {
            try (InputStream in = getClass().getResourceAsStream("/static/index.html")) {
                if (in == null) {
                    throw new IOException("index.html não encontrado");
                }
                index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            cachedIndex = index;
        }
        return index;
    }

    Map<String, Object> boot() {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("version", appInfo.version());
        app.put("java", System.getProperty("java.version"));
        app.put("portable", properties.portable());
        Map<String, Object> boot = new LinkedHashMap<>();
        boot.put("user", settings.profile());
        boot.put("brand", Map.of("name", "Lume", "tagline", "Biblioteca de documentos"));
        boot.put("maxUploadMb", 500);
        boot.put("ai", settings.aiPublic());
        boot.put("staticRoot", "/");
        boot.put("app", app);
        boot.put("desktop", true);
        return boot;
    }

    @GetMapping(SpaFilter.INDEX)
    public ResponseEntity<String> index() throws IOException {
        String data = Json.write(boot()).replace("<", "\\u003c");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CSP)
                .header("Cross-Origin-Opener-Policy", "same-origin")
                .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .body(template().replace("{{BOOT}}", data));
    }
}
