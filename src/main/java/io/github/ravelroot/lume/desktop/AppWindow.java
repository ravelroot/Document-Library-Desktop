package io.github.ravelroot.lume.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.SplashScreen;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.Values;
import io.github.ravelroot.lume.settings.SettingsService;

/**
 * A janela do Lume: o Microsoft Edge em modo aplicativo ({@code --app}), com perfil próprio, sem barra de endereço e
 * apontando para o servidor local. Quando a última janela fecha, o Lume encerra sozinho.
 */
@Component
public class AppWindow {

    private static final Logger log = LoggerFactory.getLogger(AppWindow.class);
    private static final long CLOSE_GRACE_MS = 10_000;
    private static final long NEVER_OPENED_MS = 180_000;

    private final LumeProperties properties;
    private final DesktopSession session;
    private final Presence presence;
    private final SettingsService settings;
    private final DesktopIntegration desktop;
    private final ApplicationContext context;
    private final long startedAt = System.currentTimeMillis();
    private volatile long browserPid = -1;
    private volatile boolean closing;

    public AppWindow(LumeProperties properties, DesktopSession session, Presence presence, SettingsService settings,
                     DesktopIntegration desktop, ApplicationContext context) {
        this.properties = properties;
        this.session = session;
        this.presence = presence;
        this.settings = settings;
        this.desktop = desktop;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.window()) {
            return;
        }
        open("/");
        watch();
        closeSplash();
    }

    public long browserPid() {
        return browserPid;
    }

    /** Abre uma janela (ou traz a atual para a frente). */
    public synchronized void open(String path) {
        if (presence.windows() > 0 && WindowFocus.focus(browserPid)) {
            return;
        }
        String url = session.launchUrl(path);
        Path browser = BrowserLocator.find(System.getenv());
        if (browser == null) {
            log.warn("Edge/Chrome não encontrados: abrindo no navegador padrão");
            try {
                desktop.openExternal(url);
            } catch (IOException | RuntimeException e) {
                log.error("Não foi possível abrir a janela", e);
            }
            return;
        }
        try {
            Path profile = properties.appDir().resolve("navegador");
            Files.createDirectories(profile);
            Process process = new ProcessBuilder(arguments(browser, url, profile)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            browserPid = process.pid();
            long openedAt = System.currentTimeMillis();
            process.onExit().thenRun(() -> {
                // Processo que sai logo de cara só repassou a janela para um Edge já aberto com o mesmo perfil.
                if (System.currentTimeMillis() - openedAt > 5_000) {
                    sleep(2_000);
                    if (presence.windows() == 0) {
                        shutdown("a janela foi fechada");
                    }
                }
            });
            log.info("Janela aberta com {}", browser.getFileName());
        } catch (IOException e) {
            log.error("Não foi possível abrir {}", browser, e);
            try {
                desktop.openExternal(url);
            } catch (IOException | RuntimeException ignored) {
                // sem janela e sem navegador: o vigia encerra o Lume
            }
        }
    }

    private List<String> arguments(Path browser, String url, Path profile) {
        Map<String, Object> saved = settings.window();
        int[] screen = screenSize();
        int width = (int) Math.min(screen[0], Values.number(saved.getOrDefault("width", 1360)));
        int height = (int) Math.min(screen[1], Values.number(saved.getOrDefault("height", 860)));
        List<String> args = new ArrayList<>(List.of(
                browser.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
                "--disable-sync",
                "--edge-skip-compat-layer-relaunch",
                "--hide-crash-restore-bubble",
                "--disable-features=Translate,msEdgeTranslate,msUndersideButton,msEdgeSidebarV2,msHubApps",
                "--lang=pt-BR",
                "--window-size=" + Math.max(900, width) + "," + Math.max(600, height)));
        if (saved.get("x") instanceof Number x && saved.get("y") instanceof Number y && x.intValue() > -2000 && y.intValue() > -2000) {
            args.add("--window-position=" + x.intValue() + "," + y.intValue());
        }
        if (Values.truthy(saved.get("maximized"))) {
            args.add("--start-maximized");
        }
        return args;
    }

    private static int[] screenSize() {
        if (GraphicsEnvironment.isHeadless()) {
            return new int[] {1920, 1080};
        }
        java.awt.Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
        return new int[] {size.width, size.height};
    }

    /** Encerra quando a última janela fecha (ou se nenhuma janela chegou a abrir). */
    private void watch() {
        Thread watcher = new Thread(() -> {
            while (!closing) {
                sleep(1_000);
                if (presence.everConnected() && presence.windows() == 0 && presence.emptyForMillis() > CLOSE_GRACE_MS) {
                    shutdown("nenhuma janela aberta");
                } else if (!presence.everConnected() && System.currentTimeMillis() - startedAt > NEVER_OPENED_MS) {
                    shutdown("a janela não abriu");
                }
            }
        }, "lume-vigia-da-janela");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void shutdown(String reason) {
        if (closing) {
            return;
        }
        closing = true;
        log.info("Encerrando o Lume: {}", reason);
        Thread exit = new Thread(() -> System.exit(SpringApplication.exit(context)), "lume-encerrar");
        exit.start();
    }

    private static void closeSplash() {
        try {
            SplashScreen splash = SplashScreen.getSplashScreen();
            if (splash != null) {
                splash.close();
            }
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
            // sem tela de abertura
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
