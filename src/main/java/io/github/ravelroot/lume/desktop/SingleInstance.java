package io.github.ravelroot.lume.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.core.Values;

/**
 * Um Lume por biblioteca. O primeiro a abrir trava {@code lume.lock} e anota porta e chave em
 * {@code instancia.json}; quem abrir depois (dois cliques de novo, "Abrir com" num PDF) entrega os arquivos para o
 * primeiro, traz a janela para a frente e sai.
 */
public final class SingleInstance implements AutoCloseable {

    private static final String LOCK = "lume.lock";
    private static final String INFO = "instancia.json";

    private final Path dataDir;
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(Path dataDir, FileChannel channel, FileLock lock) {
        this.dataDir = dataDir;
        this.channel = channel;
        this.lock = lock;
    }

    /** Trava a biblioteca; devolve null se outro Lume já está com ela aberta. */
    public static SingleInstance tryAcquire(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        FileChannel channel = FileChannel.open(dataDir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new SingleInstance(dataDir, channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        }
    }

    public void publish(int port, String key, long browserPid) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("port", port);
        info.put("key", key);
        info.put("pid", ProcessHandle.current().pid());
        info.put("browser_pid", browserPid);
        Files.writeString(dataDir.resolve(INFO), Json.write(info), StandardCharsets.UTF_8);
    }

    /** Dados do Lume que já está aberto (porta, chave, processo da janela). */
    public record Running(int port, String key, long browserPid) {
    }

    public static Running read(Path dataDir) {
        try {
            Map<String, Object> info = Json.parseObject(Files.readString(dataDir.resolve(INFO), StandardCharsets.UTF_8));
            return new Running((int) Values.number(info.get("port")), Values.text(info.get("key")), (long) Values.number(info.get("browser_pid")));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Entrega os arquivos para o Lume aberto. Devolve quantas janelas ele tem abertas (-1 se não respondeu).
     */
    public static int forward(Running running, List<String> files) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(HttpClient.Builder.NO_PROXY).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + "/interno/segunda-instancia"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Lume-Chave", running.key())
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("files", files))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return -1;
            }
            return (int) Values.number(Json.parseObject(response.body()).get("windows"));
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(dataDir.resolve(INFO));
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // fechando o aplicativo: nada a fazer
        }
    }
}
