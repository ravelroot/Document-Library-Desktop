package io.github.ravelroot.lume.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Ids {

    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static boolean isUuid(Object value) {
        return value != null && UUID_RE.matcher(String.valueOf(value)).matches();
    }

    /** Segredo aleatório em hexadecimal (chaves de sessão da janela). */
    public static String secret(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return HexFormat.of().formatHex(data);
    }
}
