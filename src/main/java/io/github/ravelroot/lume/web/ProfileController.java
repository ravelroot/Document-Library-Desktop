package io.github.ravelroot.lume.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.settings.SettingsService;

/** Perfil (nome e tema), números do painel e etiquetas. */
@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final SettingsService settings;
    private final LibraryService library;

    public ProfileController(SettingsService settings, LibraryService library) {
        this.settings = settings;
        this.library = library;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return settings.profile();
    }

    @RequestMapping(path = "/me", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String, Object> updateMe(@RequestBody(required = false) String raw) {
        Map<String, Object> body = Params.body(raw);
        if (body.containsKey("name") && Texts.trim(Values.text(body.get("name"))).isEmpty()) {
            throw ApiException.field("name", "Informe um nome.");
        }
        return settings.updateProfile(body);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return library.stats();
    }

    @GetMapping("/tags")
    public List<Map<String, Object>> tags() {
        return library.listTags();
    }
}
