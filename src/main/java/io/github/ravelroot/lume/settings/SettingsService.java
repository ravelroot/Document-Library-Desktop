package io.github.ravelroot.lume.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.github.ravelroot.lume.ai.AiConfig;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;

/**
 * Preferências guardadas na tabela settings (JSON por chave): perfil, tema, preferências do leitor, tamanho da janela
 * e a configuração da IA. A chave de API passa pelo {@link SecretVault} antes de ir para o banco.
 */
@Service
public class SettingsService {

    private static final List<String> THEMES = java.util.Arrays.asList("system", "light", "dark");
    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_SEPARATORS = Pattern.compile("[._-]+");
    private static final Pattern WORD_START = Pattern.compile("\\b\\p{L}", Pattern.UNICODE_CHARACTER_CLASS);

    private final Database db;
    private final SecretVault vault;

    public SettingsService(Database db, SecretVault vault) {
        this.db = db;
        this.vault = vault;
    }

    public Object get(String key, Object fallback) {
        Row row = db.one("SELECT value FROM settings WHERE key = ?", key);
        return row == null ? fallback : Json.parse(row.str("value"), fallback);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getObject(String key) {
        Object value = get(key, null);
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    public void set(String key, Object value) {
        db.update("INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                key, Json.write(value), Times.now());
    }

    /* ---------- Perfil ---------- */

    static String defaultName() {
        String user = System.getProperty("user.name", "");
        String cleaned = Texts.trim(NAME_SEPARATORS.matcher(user).replaceAll(" "));
        if (cleaned.isEmpty()) {
            return "Leitor";
        }
        return WORD_START.matcher(cleaned).replaceAll(m -> m.group().toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> storedProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "");
        profile.put("theme", "system");
        profile.put("reader_prefs", new LinkedHashMap<>());
        profile.putAll(getObject("profile"));
        return profile;
    }

    public Map<String, Object> profile() {
        Map<String, Object> stored = storedProfile();
        String name = Values.truthy(stored.get("name")) ? Values.text(stored.get("name")) : defaultName();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("initials", Texts.initials(name));
        out.put("theme", stored.get("theme"));
        out.put("reader_prefs", stored.get("reader_prefs") == null ? Map.of() : stored.get("reader_prefs"));
        return out;
    }

    public Map<String, Object> updateProfile(Map<String, Object> changes) {
        Map<String, Object> next = storedProfile();
        if (changes.containsKey("name")) {
            String name = Texts.trim(Values.text(changes.get("name")));
            next.put("name", name.length() > 120 ? name.substring(0, 120) : name);
        }
        if (changes.containsKey("theme") && THEMES.contains(changes.get("theme"))) {
            next.put("theme", changes.get("theme"));
        }
        if (changes.get("reader_prefs") instanceof Map<?, ?> prefs && Json.size(prefs) < 2000) {
            next.put("reader_prefs", prefs);
        }
        set("profile", next);
        return profile();
    }

    /* ---------- Descrição por IA ---------- */

    /** Configuração completa da IA, com a chave aberta (uso interno do pipeline). */
    public AiConfig aiConfig() {
        Map<String, Object> stored = getObject("ai");
        stored.put("api_key", vault.open(Values.text(stored.get("api_key"))));
        return AiConfig.normalize(stored);
    }

    public Map<String, Object> aiPublic() {
        return aiConfig().toPublic();
    }

    /** api_key ausente mantém a atual, "" apaga e texto novo substitui. */
    public Map<String, Object> updateAi(Map<String, Object> changes) {
        Map<String, Object> next = getObject("ai");
        for (String key : List.of("provider", "model", "base_url")) {
            if (changes.containsKey(key)) {
                String value = Texts.trim(Values.text(changes.get(key)));
                next.put(key, value.length() > 300 ? value.substring(0, 300) : value);
            }
        }
        for (String key : List.of("timeout_s", "max_input_chars")) {
            Object value = changes.get(key);
            if (changes.containsKey(key) && !"".equals(value)) {
                double number = Values.number(value);
                next.put(key, Double.isNaN(number) ? null : number);
            }
        }
        String baseUrl = Values.text(next.get("base_url"));
        if (!baseUrl.isEmpty() && !HTTP_URL.matcher(baseUrl).matches()) {
            throw new ApiException("O endereço da API precisa começar com http:// ou https://.");
        }
        if (changes.containsKey("api_key")) {
            next.put("api_key", vault.seal(Texts.trim(Values.text(changes.get("api_key")))));
        }
        set("ai", next);
        return aiPublic();
    }

    /* ---------- Janela ---------- */

    public Map<String, Object> window() {
        return getObject("window");
    }

    public void saveWindow(Map<String, Object> bounds) {
        set("window", bounds);
    }
}
