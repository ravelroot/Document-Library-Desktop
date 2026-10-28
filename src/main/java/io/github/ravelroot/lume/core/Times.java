package io.github.ravelroot.lume.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;

/** Datas sempre em UTC e no mesmo formato do JavaScript (toISOString), para ordenar como texto no banco. */
public final class Times {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    /** Agora, como "2026-10-21T13:05:00.123Z". */
    public static String now() {
        return ISO_MILLIS.format(Instant.now());
    }

    public static String iso(Instant instant) {
        return ISO_MILLIS.format(instant);
    }

    /** Data de metadados (PDF/DOCX) em ISO 8601 UTC, sem frações de segundo. */
    public static String isoSeconds(Instant instant) {
        return instant == null ? null : ISO_SECONDS.format(instant);
    }

    public static String isoSeconds(Calendar calendar) {
        return calendar == null ? null : isoSeconds(calendar.toInstant());
    }

    public static String ago(long millis) {
        return iso(Instant.now().minusMillis(millis));
    }
}
