package io.github.ravelroot.lume.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ravelroot.lume.core.Values;

/**
 * Configuração da descrição por IA (Preferências → Descrição por IA).
 *
 * @param provider      local, anthropic ou openai
 * @param model         modelo do provedor (vazio no resumo local)
 * @param baseUrl       endereço da API; vazio usa o padrão do provedor
 * @param apiKey        chave aberta, só em memória (no banco ela fica cifrada)
 * @param timeoutS      tempo máximo de espera pela resposta, em segundos
 * @param maxInputChars quanto do texto vai no pedido
 */
public record AiConfig(String provider, String model, String baseUrl, String apiKey, int timeoutS, int maxInputChars) {

    public static final Map<String, String> LABELS = Map.of(
            "local", "Resumo automático",
            "anthropic", "Claude (Anthropic)",
            "openai", "API compatível com OpenAI");
    public static final Map<String, String> DEFAULT_MODELS = Map.of("anthropic", "claude-haiku-5-5", "openai", "");
    public static final Map<String, String> DEFAULT_URLS =
            Map.of("anthropic", "https://api.anthropic.com", "openai", "https://api.openai.com/v1");

    public static AiConfig defaults() {
        return normalize(Map.of());
    }

    /** Junta o que está guardado com os padrões e põe os números dentro dos limites. */
    public static AiConfig normalize(Map<String, ?> raw) {
        String provider = Values.text(raw.get("provider"));
        if (!LABELS.containsKey(provider)) {
            provider = "local";
        }
        String model = Values.text(raw.get("model"));
        if (model.isEmpty()) {
            model = DEFAULT_MODELS.getOrDefault(provider, "");
        }
        double timeout = Values.number(raw.containsKey("timeout_s") ? raw.get("timeout_s") : 45);
        double maxInput = Values.number(raw.containsKey("max_input_chars") ? raw.get("max_input_chars") : 12000);
        int timeoutS = (int) Math.min(180, Math.max(5, Double.isNaN(timeout) || timeout == 0 ? 45 : timeout));
        int maxInputChars = (int) Math.min(60000, Math.max(2000, Double.isNaN(maxInput) || maxInput == 0 ? 12000 : maxInput));
        return new AiConfig(provider, model, Values.text(raw.get("base_url")), Values.text(raw.get("api_key")), timeoutS, maxInputChars);
    }

    public boolean configured() {
        return switch (provider) {
            case "local" -> true;
            case "anthropic" -> !apiKey.isEmpty();
            case "openai" -> !model.isEmpty();
            default -> false;
        };
    }

    public String label() {
        return LABELS.get(provider);
    }

    /** O que a interface pode ver: tudo menos a chave (só se ela existe). */
    public Map<String, Object> toPublic() {
        boolean local = "local".equals(provider);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("label", label());
        out.put("model", local ? "" : model);
        out.put("base_url", local ? "" : baseUrl);
        out.put("has_key", !apiKey.isEmpty());
        out.put("timeout_s", timeoutS);
        out.put("max_input_chars", maxInputChars);
        out.put("configured", configured());
        return out;
    }

    /** Mapa com as mesmas chaves da tabela settings (para combinar com o que chega da interface). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("model", model);
        out.put("base_url", baseUrl);
        out.put("api_key", apiKey);
        out.put("timeout_s", timeoutS);
        out.put("max_input_chars", maxInputChars);
        return out;
    }
}
