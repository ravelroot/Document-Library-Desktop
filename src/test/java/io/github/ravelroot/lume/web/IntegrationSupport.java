package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.desktop.DesktopSession;
import io.github.ravelroot.lume.library.DocumentStore;
import io.github.ravelroot.lume.library.LibraryService;
import io.github.ravelroot.lume.pipeline.PipelineService;

/** Sobe o Lume de verdade (Spring Boot + Tomcat numa porta livre) e fala com a API por HTTP, com o cookie da janela. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lume.launch-key=chave-de-teste", "lume.retry-delay-ms=20", "lume.window=false"})
public abstract class IntegrationSupport {

    static final Path DATA_DIR = createDataDir();

    @LocalServerPort
    protected int port;
    @Autowired
    protected DesktopSession session;
    @Autowired
    protected Database db;
    @Autowired
    protected PipelineService pipeline;
    @Autowired
    protected LibraryService library;
    @Autowired
    protected DocumentStore store;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("lume-teste-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("lume.data-dir", DATA_DIR::toString);
    }

    protected static Path example(String name) {
        return Path.of("exemplos", name).toAbsolutePath();
    }

    /** Cada teste começa com a biblioteca vazia. */
    @BeforeEach
    void emptyLibrary() throws Exception {
        settle();
        pipeline.reset();
        for (String table : List.of("collections", "documents", "documents_fts", "tags", "settings", "jobs")) {
            db.update("DELETE FROM " + table);
        }
        LibraryServiceAccess.deleteTree(store.documentsDir());
    }

    protected void settle() throws InterruptedException {
        long started = System.currentTimeMillis();
        Thread.sleep(20);
        while (!pipeline.idle()) {
            if (System.currentTimeMillis() - started > 30_000) {
                throw new IllegalStateException("O pipeline não terminou a tempo.");
            }
            Thread.sleep(20);
        }
    }

    protected record Reply(int status, String body, HttpHeaders headers) {

        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return (Map<String, Object>) Json.parse(body, null);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) Json.parse(body, null);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    protected Reply call(String method, String path, Object body, String... headers) throws IOException, InterruptedException {
        return raw(method, path, body == null ? null : Json.write(body), true, headers);
    }

    protected Reply raw(String method, String path, String body, boolean withSession, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (withSession) {
            request.header("Cookie", DesktopSession.COOKIE + "=" + session.token());
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.headers());
    }

    protected HttpResponse<byte[]> bytes(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DesktopSession.COOKIE + "=" + session.token());
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> document(LibraryService.ImportResult result) {
        return result.document();
    }

    /** Acesso ao utilitário de apagar pastas (pacote da biblioteca). */
    static final class LibraryServiceAccess {
        static void deleteTree(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
