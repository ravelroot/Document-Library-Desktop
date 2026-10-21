package io.github.ravelroot.lume.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (Jackson 3) para as colunas TEXT do banco e para os corpos da API. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private Json() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Lê qualquer JSON; devolve {@code fallback} se o texto estiver vazio ou inválido. */
    public static Object parse(String raw, Object fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (JacksonException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String raw) {
        Object value = parse(raw, null);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseList(String raw) {
        Object value = parse(raw, null);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    /**
     * Corpo de uma requisição da API: precisa ser um objeto JSON. Corpo vazio vale como {}.
     *
     * @throws ApiException 400 parse_error quando o texto não é um objeto JSON
     */
    public static Map<String, Object> body(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(raw, MAP);
        } catch (JacksonException e) {
            throw new ApiException("Corpo da requisição inválido.", 400, "parse_error");
        }
    }

    /** Tamanho do objeto serializado (as regras limitam âncoras, posições e preferências por tamanho). */
    public static int size(Object value) {
        return write(value).length();
    }
}
