package io.github.ravelroot.lume.desktop;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.Ids;

/**
 * A sessão da janela. O servidor só atende 127.0.0.1, mas qualquer site aberto no navegador também poderia mandar
 * requisições para essa porta; por isso a API exige um cookie de sessão que só a janela do Lume recebe:
 *
 * <ol>
 *   <li>o Lume.exe abre a janela em {@code /entrar?chave=<launchKey>};</li>
 *   <li>a chave confere, o servidor grava o cookie {@code lume_sessao} (HttpOnly, SameSite=Strict) e redireciona;</li>
 *   <li>daí em diante toda chamada a /api leva o cookie. Sites de fora não têm a chave nem recebem o cookie.</li>
 * </ol>
 */
@Component
public class DesktopSession {

    public static final String COOKIE = "lume_sessao";

    private final String launchKey;
    private final String token = Ids.secret(32);
    private volatile int port;

    public DesktopSession(LumeProperties properties) {
        this.launchKey = properties.launchKey() == null || properties.launchKey().isBlank() ? Ids.secret(24) : properties.launchKey();
    }

    @EventListener
    public void onServerStarted(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    public String launchKey() {
        return launchKey;
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    public String origin() {
        return "http://127.0.0.1:" + port;
    }

    /** Endereço que a janela abre: entra com a chave e vai para {@code path}. */
    public String launchUrl(String path) {
        String target = path == null || path.isEmpty() ? "/" : path;
        return origin() + "/entrar?chave=" + launchKey + (target.equals("/") ? "" : "&para=" + java.net.URLEncoder.encode(target,
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Hosts aceitos (proteção contra DNS rebinding). */
    public boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        return host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port);
    }
}
