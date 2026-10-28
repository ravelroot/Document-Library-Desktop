package io.github.ravelroot.lume.settings;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.Crypt32Util;

/**
 * Cofre padrão. Prefixos guardados no banco:
 * <ul>
 *   <li>{@code dpapi:} cifrado pelo Windows (CryptProtectData, escopo do usuário);</li>
 *   <li>{@code b64:} sem cofre disponível;</li>
 *   <li>{@code enc:} formato da versão em Electron (safeStorage), que o Java não consegue abrir: a pessoa informa a
 *   chave de novo nas Preferências.</li>
 * </ul>
 */
public class SystemVault implements SecretVault {

    private static final Logger log = LoggerFactory.getLogger(SystemVault.class);
    private static final byte[] ENTROPY = "Lume · chave de API".getBytes(StandardCharsets.UTF_8);

    private final boolean windows;

    public SystemVault() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    SystemVault(boolean windows) {
        this.windows = windows;
    }

    @Override
    public String seal(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        byte[] data = secret.getBytes(StandardCharsets.UTF_8);
        if (windows) {
            try {
                byte[] sealed = Crypt32Util.cryptProtectData(data, ENTROPY, 0, "Lume", null);
                return "dpapi:" + Base64.getEncoder().encodeToString(sealed);
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                log.warn("Cofre do Windows indisponível: {}", e.getMessage());
            }
        }
        return "b64:" + Base64.getEncoder().encodeToString(data);
    }

    @Override
    public String open(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            if (stored.startsWith("dpapi:")) {
                if (!windows) {
                    return "";
                }
                byte[] sealed = Base64.getDecoder().decode(stored.substring(6));
                return new String(Crypt32Util.cryptUnprotectData(sealed, ENTROPY, 0, null), StandardCharsets.UTF_8);
            }
            if (stored.startsWith("b64:")) {
                return new String(Base64.getDecoder().decode(stored.substring(4)), StandardCharsets.UTF_8);
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("Não foi possível abrir a chave guardada: {}", e.getMessage());
        }
        return "";
    }
}
