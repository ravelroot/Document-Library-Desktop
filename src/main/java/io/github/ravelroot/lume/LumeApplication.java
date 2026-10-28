package io.github.ravelroot.lume;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.desktop.AppWindow;
import io.github.ravelroot.lume.desktop.DataLocation;
import io.github.ravelroot.lume.desktop.DesktopSession;
import io.github.ravelroot.lume.desktop.SingleInstance;
import io.github.ravelroot.lume.desktop.WindowFocus;
import io.github.ravelroot.lume.events.EventBus;
import io.github.ravelroot.lume.library.LibraryService;

/**
 * Lume para Windows: biblioteca de PDFs e DOCX com Spring Boot.
 *
 * <p>Ao abrir: escolhe a pasta de dados, garante um Lume só por biblioteca, sobe a API local (Tomcat em
 * 127.0.0.1) e abre a janela. Argumentos:</p>
 * <ul>
 *   <li>{@code arquivo.pdf arquivo.docx}: importa ao abrir ("Abrir com" no Explorador);</li>
 *   <li>{@code --sem-janela}: só o servidor (testes de interface e desenvolvimento);</li>
 *   <li>{@code --porta=47820} e {@code --chave=...}: porta e chave fixas (testes).</li>
 * </ul>
 */
@SpringBootApplication
public class LumeApplication {

    private static final Logger log = LoggerFactory.getLogger(LumeApplication.class);
    private static final int FIRST_PORT = 47820;

    public static void main(String[] args) {
        Options options = Options.parse(args);
        DataLocation location = DataLocation.resolve(System.getenv());
        SingleInstance instance;
        try {
            instance = SingleInstance.tryAcquire(location.dataDir());
        } catch (IOException e) {
            fatal("Não foi possível abrir a biblioteca em " + location.dataDir() + ".\n\n" + e.getMessage(), options.window());
            return;
        }
        if (instance == null) {
            handOver(location, options);
            return;
        }

        int port = options.port() != null ? options.port() : freePort();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("server.port", port);
        props.put("lume.data-dir", location.dataDir().toString());
        props.put("lume.app-dir", location.appDir().toString());
        props.put("lume.portable", location.portable());
        props.put("lume.window", options.window());
        props.put("logging.file.name", location.dataDir().resolve("lume.log").toString());
        if (options.key() != null) {
            props.put("lume.launch-key", options.key());
        }
        if (options.dev()) {
            props.put("lume.dev", true);
        }

        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(LumeApplication.class)
                    .headless(!options.window())
                    .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        } catch (RuntimeException e) {
            instance.close();
            fatal("Não foi possível abrir a biblioteca em " + location.dataDir() + ".\n\n" + rootMessage(e), options.window());
            return;
        }
        DesktopSession session = context.getBean(DesktopSession.class);
        try {
            instance.publish(session.port(), session.launchKey(), context.getBean(AppWindow.class).browserPid());
        } catch (IOException e) {
            log.warn("Não foi possível registrar a instância: {}", e.getMessage());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(instance::close, "lume-liberar"));
        if (!options.window()) {
            System.out.println("Lume pronto: " + session.launchUrl("/"));
        }
        importInBackground(context, options.files());
    }

    /** Já existe um Lume aberto com esta biblioteca: entrega os arquivos e traz a janela para a frente. */
    private static void handOver(DataLocation location, Options options) {
        SingleInstance.Running running = SingleInstance.read(location.dataDir());
        if (running != null) {
            int windows = SingleInstance.forward(running, options.files());
            if (windows > 0) {
                WindowFocus.focus(running.browserPid());
            }
            if (windows >= 0) {
                return;
            }
        }
        fatal("O Lume já está aberto, mas não respondeu. Feche-o pelo Gerenciador de Tarefas e abra de novo.", options.window());
    }

    private static void importInBackground(ConfigurableApplicationContext context, List<String> files) {
        if (files.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> {
            LibraryService library = context.getBean(LibraryService.class);
            EventBus events = context.getBean(EventBus.class);
            for (String file : files) {
                try {
                    library.importFile(Path.of(file), null);
                } catch (ApiException e) {
                    events.emit("import.failed", Map.of("file", Path.of(file).getFileName().toString(), "message", e.getMessage()));
                }
            }
        }, "lume-abrir-com");
        worker.setDaemon(true);
        worker.start();
    }

    /** A primeira porta livre a partir de 47820 (porta fixa = a janela guarda tema e preferências locais). */
    static int freePort() {
        for (int port = FIRST_PORT; port < FIRST_PORT + 20; port++) {
            try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return port;
            } catch (IOException ignored) {
                // ocupada: tenta a próxima
            }
        }
        return 0;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static void fatal(String message, boolean window) {
        log.error(message);
        if (window && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Lume", JOptionPane.ERROR_MESSAGE);
        } else {
            System.err.println(message);
        }
        System.exit(1);
    }

    /** Argumentos da linha de comando. */
    record Options(boolean window, boolean dev, Integer port, String key, List<String> files) {

        static Options parse(String[] args) {
            boolean window = true;
            boolean dev = false;
            Integer port = null;
            String key = null;
            List<String> files = new ArrayList<>();
            for (String arg : args) {
                if (arg.equals("--sem-janela")) {
                    window = false;
                } else if (arg.equals("--dev")) {
                    dev = true;
                } else if (arg.startsWith("--porta=")) {
                    port = Integer.parseInt(arg.substring("--porta=".length()));
                } else if (arg.startsWith("--chave=")) {
                    key = arg.substring("--chave=".length());
                } else if (arg.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx)$") && Files.exists(Path.of(arg))) {
                    files.add(Path.of(arg).toAbsolutePath().toString());
                }
            }
            return new Options(window, dev, port, key, files);
        }
    }
}
