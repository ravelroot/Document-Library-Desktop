package io.github.ravelroot.lume.core;

import java.util.List;
import java.util.Map;

/**
 * Erro com status HTTP para a API local. O {@code ApiExceptionHandler} transforma em {@code {error, message}} (e
 * {@code fields}, quando o erro é de um campo do formulário).
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, List<String>> fields;

    public ApiException(String message) {
        this(message, 400, "invalid", null);
    }

    public ApiException(String message, int status, String code) {
        this(message, status, code, null);
    }

    public ApiException(String message, int status, String code, Map<String, List<String>> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    /** Erro de validação ligado a um campo (o formulário mostra a mensagem embaixo dele). */
    public static ApiException field(String field, String message) {
        return new ApiException(message, 400, "invalid", Map.of(field, List.of(message)));
    }

    public static ApiException notFound(String message) {
        return new ApiException(message, 404, "not_found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(message, 409, "conflict");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, List<String>> fields() {
        return fields;
    }
}
