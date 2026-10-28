package io.github.ravelroot.lume.db;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uma linha do banco, com leitura tipada das colunas (o SQLite devolve INTEGER como Integer ou Long). */
public class Row extends LinkedHashMap<String, Object> {

    public Row() {
        super();
    }

    public Row(Map<String, ?> values) {
        super(values);
    }

    public String str(String column) {
        Object value = get(column);
        return value == null ? null : String.valueOf(value);
    }

    /** Texto da coluna, ou "" quando é NULL. */
    public String text(String column) {
        Object value = get(column);
        return value == null ? "" : String.valueOf(value);
    }

    public Integer integer(String column) {
        Object value = get(column);
        return value instanceof Number n ? n.intValue() : null;
    }

    public int integer(String column, int fallback) {
        Integer value = integer(column);
        return value == null ? fallback : value;
    }

    public Long lng(String column) {
        Object value = get(column);
        return value instanceof Number n ? n.longValue() : null;
    }

    public long lng(String column, long fallback) {
        Long value = lng(column);
        return value == null ? fallback : value;
    }

    public Double dbl(String column) {
        Object value = get(column);
        return value instanceof Number n ? n.doubleValue() : null;
    }

    /** Colunas 0/1 do SQLite como booleano. */
    public boolean bool(String column) {
        Object value = get(column);
        return value instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(value);
    }

    public String id() {
        return str("id");
    }
}
