package io.github.ravelroot.lume.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.core.Ids;
import io.github.ravelroot.lume.core.Json;

/** Validação dos parâmetros de rota e leitura dos corpos JSON. */
final class Params {

    private Params() {
    }

    /** Id da rota: precisa ser um UUID; qualquer outra coisa é 404. */
    static String uuid(String value) {
        if (!Ids.isUuid(value)) {
            throw ApiException.notFound("Não encontrado.");
        }
        return value;
    }

    static Map<String, Object> body(String raw) {
        return Json.body(raw);
    }

    /** Só os itens da lista que são UUIDs. */
    static List<String> uuids(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (Ids.isUuid(item)) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
