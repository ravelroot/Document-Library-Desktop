package io.github.ravelroot.lume.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ravelroot.lume.ai.HttpTransport;
import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.integrations.WebhookService;

/** Pastas monitoradas (importação automática) e webhooks. */
class IntegrationsApiTest extends IntegrationSupport {

    @Autowired
    private WebhookService webhooks;

    private record Sent(String url, Map<String, String> headers, String body) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void pastaMonitoradaImportaOQueENovoESemDuplicar(@TempDir Path dir) throws Exception {
        Files.copy(example("guia-pratico-de-python.pdf"), dir.resolve("python.pdf"));
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Files.copy(example("manual-do-colaborador.docx"), sub.resolve("manual.docx"));
        Files.writeString(dir.resolve("anotacao.txt"), "não é documento");

        String collection = (String) call("POST", "/api/v1/collections", Map.of("name", "Recebidos")).json().get("id");
        Reply added = call("POST", "/api/v1/integrations/watch-folders", Map.of("path", dir.toString(), "collection", collection));
        assertThat(added.status()).isEqualTo(200);
        List<Map<String, Object>> folders = (List<Map<String, Object>>) added.json().get("watch_folders");
        assertThat(folders).hasSize(1);
        assertThat(folders.get(0)).containsEntry("exists", true).containsEntry("enabled", true).containsEntry("collection_name", "Recebidos");
        assertThat(added.json()).containsKeys("webhooks", "scan_interval_s", "webhook_events");

        assertThat(call("POST", "/api/v1/integrations/watch-folders", Map.of("path", dir.toString())).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/integrations/watch-folders", Map.of("path", dir.resolve("nao-existe").toString())).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/integrations/watch-folders", Map.of("path", store.dataDir().toString())).status()).isEqualTo(400);

        Map<String, Object> first = call("POST", "/api/v1/integrations/watch-folders/scan", null).json();
        assertThat(first).containsEntry("imported", 2).containsEntry("failed", 0);
        settle();
        assertThat(db.scalar("SELECT count(*) FROM documents")).isEqualTo(2);
        assertThat(((Number) call("GET", "/api/v1/collections/" + collection, null).json().get("document_count")).intValue()).isEqualTo(2);

        Map<String, Object> second = call("POST", "/api/v1/integrations/watch-folders/scan", null).json();
        assertThat(second).containsEntry("imported", 0).containsEntry("duplicates", 0);

        Files.copy(example("guia-pratico-de-python.pdf"), dir.resolve("copia do python.pdf"));
        Map<String, Object> third = call("POST", "/api/v1/integrations/watch-folders/scan", null).json();
        assertThat(third).containsEntry("imported", 0).containsEntry("duplicates", 1);
        assertThat(db.scalar("SELECT count(*) FROM documents")).isEqualTo(2);

        Map<String, Object> overview = call("GET", "/api/v1/integrations", null).json();
        Map<String, Object> folder = ((List<Map<String, Object>>) overview.get("watch_folders")).get(0);
        assertThat(((Number) folder.get("imported_total")).intValue()).isEqualTo(2);
        assertThat(folder.get("last_scan_at")).isNotNull();

        String id = (String) folder.get("id");
        Map<String, Object> paused = call("PATCH", "/api/v1/integrations/watch-folders/" + id, Map.of("enabled", false)).json();
        assertThat(((List<Map<String, Object>>) paused.get("watch_folders")).get(0)).containsEntry("enabled", false);
        Map<String, Object> removed = call("DELETE", "/api/v1/integrations/watch-folders/" + id, null).json();
        assertThat((List<?>) removed.get("watch_folders")).isEmpty();
        assertThat(call("POST", "/api/v1/integrations/watch-folders", null).status()).as("sem janela não há seletor de pasta").isEqualTo(501);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webhooksRecebemOsEventosComAssinaturaEFormatoDoSlack() throws Exception {
        List<Sent> sent = new CopyOnWriteArrayList<>();
        int[] status = {204};
        HttpTransport previous = webhooks.useTransport((url, headers, body, timeout) -> {
            sent.add(new Sent(url.toString(), headers, body));
            return new HttpTransport.Reply(status[0], status[0] >= 400 ? "erro do servidor" : "");
        });
        try {
            assertThat(call("POST", "/api/v1/integrations/webhooks", Map.of("url", "ftp://x")).status()).isEqualTo(400);
            Reply json = call("POST", "/api/v1/integrations/webhooks", Map.of("url", "https://exemplo.test/lume", "secret", "s3gredo",
                    "events", List.of("document.ready", "annotation.created", "shelf.finished")));
            assertThat(json.status()).isEqualTo(200);
            Reply slack = call("POST", "/api/v1/integrations/webhooks", Map.of("url", "https://hooks.slack.com/services/T0/B0/xyz"));
            List<Map<String, Object>> hooks = (List<Map<String, Object>>) slack.json().get("webhooks");
            assertThat(hooks).hasSize(2);
            assertThat(hooks.get(0)).containsEntry("format", "json").containsEntry("has_secret", true).doesNotContainKey("secret");
            assertThat(hooks.get(1)).containsEntry("format", "slack").containsEntry("events", List.of("document.ready"));

            String id = (String) library.importFile(example("guia-pratico-de-python.pdf"), null).document().get("id");
            settle();
            call("PATCH", "/api/v1/documents/" + id + "/reading", Map.of("shelf", "lido"));
            call("POST", "/api/v1/documents/" + id + "/annotations", Map.of("kind", "highlight", "page", 2, "quote", "ambiente virtual"));
            for (int i = 0; i < 200 && sent.size() < 4; i++) {
                Thread.sleep(25);
            }
            Sent ready = sent.stream().filter(s -> s.url().contains("exemplo.test") && "document.ready".equals(s.headers().get("x-lume-event")))
                    .findFirst().orElseThrow();
            Map<String, Object> payload = Json.parseObject(ready.body());
            assertThat(payload).containsEntry("event", "document.ready").containsEntry("app", "Lume");
            assertThat((Map<String, Object>) payload.get("document")).containsEntry("id", id).containsEntry("title", "Guia prático de Python");
            assertThat(ready.headers().get("x-lume-signature")).startsWith("sha256=").hasSize(71);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("s3gredo".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(ready.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(ready.headers().get("x-lume-signature")).isEqualTo(expected);

            Sent slackReady = sent.stream().filter(s -> s.url().contains("hooks.slack.com")).findFirst().orElseThrow();
            assertThat((String) Json.parseObject(slackReady.body()).get("text")).startsWith("📄 Pronto no Lume: “Guia prático de Python”");
            assertThat(sent).anyMatch(s -> "shelf.finished".equals(s.headers().get("x-lume-event")));
            Sent note = sent.stream().filter(s -> "annotation.created".equals(s.headers().get("x-lume-event"))).findFirst().orElseThrow();
            assertThat((Map<String, Object>) Json.parseObject(note.body()).get("annotation")).containsEntry("quote", "ambiente virtual");
            assertThat(sent).noneMatch(s -> s.url().contains("hooks.slack.com") && !"document.ready".equals(s.headers().get("x-lume-event")));

            String hookId = (String) hooks.get(0).get("id");
            Map<String, Object> test = call("POST", "/api/v1/integrations/webhooks/" + hookId + "/test", null).json();
            assertThat(test).containsEntry("ok", true).containsEntry("status", 204);
            status[0] = 500;
            Map<String, Object> failed = call("POST", "/api/v1/integrations/webhooks/" + hookId + "/test", null).json();
            assertThat(failed).containsEntry("ok", false).containsEntry("status", 500);
            Map<String, Object> listed = ((List<Map<String, Object>>) call("GET", "/api/v1/integrations", null).json().get("webhooks")).get(0);
            assertThat(listed).containsEntry("last_status", 500);
            assertThat((String) listed.get("last_error")).contains("500");

            Map<String, Object> off = call("PATCH", "/api/v1/integrations/webhooks/" + hookId, Map.of("enabled", false, "events", List.of("document.deleted"))).json();
            assertThat(((List<Map<String, Object>>) off.get("webhooks")).get(0)).containsEntry("enabled", false);
            assertThat(call("PATCH", "/api/v1/integrations/webhooks/" + hookId, Map.of("events", List.of("nada"))).status()).isEqualTo(400);
            Map<String, Object> gone = call("DELETE", "/api/v1/integrations/webhooks/" + hookId, null).json();
            assertThat((List<?>) gone.get("webhooks")).hasSize(1);
        } finally {
            webhooks.useTransport(previous);
        }
    }
}
