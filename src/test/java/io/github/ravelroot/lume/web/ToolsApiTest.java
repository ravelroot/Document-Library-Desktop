package io.github.ravelroot.lume.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.tools.ToolsService;

/** Ações em lote, perguntar ao documento, parecidos, estatísticas, exportações, backup e manutenção. */
class ToolsApiTest extends IntegrationSupport {

    @Autowired
    private ToolsService tools;

    private String importReady(String file) throws Exception {
        String id = (String) library.importFile(example(file), null).document().get("id");
        settle();
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tagsOf(String id) throws Exception {
        return (List<Map<String, Object>>) call("GET", "/api/v1/documents/" + id, null).json().get("tags");
    }

    @Test
    void acoesEmLoteMudamEstanteFavoritoEtiquetasColecaoEExcluem() throws Exception {
        String a = importReady("guia-pratico-de-python.pdf");
        String b = importReady("introducao-ao-postgresql.pdf");
        String c = importReady("manual-do-colaborador.docx");
        List<String> all = List.of(a, b, c);
        int iaTags = tagsOf(a).size();

        Reply shelf = call("POST", "/api/v1/documents/batch", Map.of("ids", all, "action", "shelf", "value", "lido"));
        assertThat(shelf.json()).containsEntry("ok", true).containsEntry("affected", 3);
        assertThat(call("GET", "/api/v1/documents/" + b + "/reading", null).json().get("shelf")).isEqualTo("lido");

        call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(a, b), "action", "favorite"));
        assertThat(((Number) call("GET", "/api/v1/stats", null).json().get("favorites")).intValue()).isEqualTo(2);
        call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(b), "action", "unfavorite"));
        assertThat(((Number) call("GET", "/api/v1/stats", null).json().get("favorites")).intValue()).isEqualTo(1);

        Reply tags = call("POST", "/api/v1/documents/batch", Map.of("ids", all, "action", "tags_add", "value", List.of("Urgente")));
        assertThat(tags.json().get("affected")).isEqualTo(3);
        assertThat(tagsOf(a)).hasSize(iaTags + 1).anyMatch(t -> "urgente".equals(t.get("name")) && "usuario".equals(t.get("source")));

        String collection = (String) call("POST", "/api/v1/collections", Map.of("name", "Leituras")).json().get("id");
        Reply added = call("POST", "/api/v1/documents/batch", Map.of("ids", all, "action", "collection_add", "value", collection));
        assertThat(added.json().get("affected")).isEqualTo(3);
        Reply removed = call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(c), "action", "collection_remove", "value", collection));
        assertThat(removed.json().get("affected")).isEqualTo(1);
        assertThat(((Number) call("GET", "/api/v1/collections/" + collection, null).json().get("document_count")).intValue()).isEqualTo(2);

        Reply again = call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(a), "action", "redescribe"));
        assertThat(again.json().get("affected")).isEqualTo(1);
        settle();

        Reply deleted = call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(c, "00000000-0000-0000-0000-000000000000"), "action", "delete"));
        assertThat(deleted.json().get("affected")).isEqualTo(1);
        assertThat(db.scalar("SELECT count(*) FROM documents")).isEqualTo(2);

        assertThat(call("POST", "/api/v1/documents/batch", Map.of("ids", all, "action", "voar")).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(), "action", "favorite")).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(a), "action", "shelf", "value", "sotao")).status()).isEqualTo(400);
    }

    @Test
    @SuppressWarnings("unchecked")
    void perguntaAoDocumentoNoModoLocalTrazOTrechoEAPagina() throws Exception {
        String id = importReady("guia-pratico-de-python.pdf");
        Reply reply = call("POST", "/api/v1/documents/" + id + "/ask", Map.of("question", "Como criar um ambiente virtual?"));
        assertThat(reply.status()).isEqualTo(200);
        Map<String, Object> answer = reply.json();
        assertThat(answer.get("mode")).isEqualTo("local");
        assertThat(answer.get("warning")).isNull();
        List<Map<String, Object>> passages = (List<Map<String, Object>>) answer.get("passages");
        assertThat(passages).isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(String.valueOf(passages.get(0).get("text")).toLowerCase()).contains("ambiente virtual");
        assertThat(passages.get(0).get("page")).isNotNull();
        assertThat((String) answer.get("answer")).containsIgnoringCase("ambiente virtual");

        Reply nothing = call("POST", "/api/v1/documents/" + id + "/ask", Map.of("question", "zzzqqq xptoxyz"));
        assertThat((String) nothing.json().get("answer")).startsWith("Não encontrei");

        assertThat(call("POST", "/api/v1/documents/" + id + "/ask", Map.of("question", "a")).status()).isEqualTo(400);
        String docx = importReady("manual-do-colaborador.docx");
        Map<String, Object> word = call("POST", "/api/v1/documents/" + docx + "/ask", Map.of("question", "Quais são os valores do ateliê?")).json();
        assertThat((List<?>) word.get("passages")).isNotEmpty();
        assertThat(((List<Map<String, Object>>) word.get("passages")).get(0).get("page")).isNull();
    }

    @Test
    void documentosParecidosUsamEtiquetasEmComum() throws Exception {
        String python = importReady("guia-pratico-de-python.pdf");
        String postgres = importReady("introducao-ao-postgresql.pdf");
        String receitas = importReady("caderno-de-receitas-da-vo-lucia.pdf");
        call("POST", "/api/v1/documents/batch", Map.of("ids", List.of(python, postgres), "action", "tags_add", "value", List.of("tecnologia")));
        List<Map<String, Object>> related = call("GET", "/api/v1/documents/" + python + "/related?limit=5", null).list();
        assertThat(related).isNotEmpty();
        assertThat(related.get(0).get("id")).isEqualTo(postgres);
        assertThat(related).noneMatch(d -> python.equals(d.get("id"))).allMatch(d -> "ready".equals(d.get("status")));
        assertThat(related.get(0)).containsKeys("title", "cover_url", "tags", "reading");
        assertThat(receitas).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void estatisticasContamDocumentosLeiturasEMeses() throws Exception {
        String a = importReady("guia-pratico-de-python.pdf");
        importReady("manual-do-colaborador.docx");
        call("PATCH", "/api/v1/documents/" + a + "/reading", Map.of("shelf", "lido"));
        call("POST", "/api/v1/documents/" + a + "/annotations", Map.of("kind", "highlight", "page", 2, "quote", "ambiente virtual"));
        Map<String, Object> data = call("GET", "/api/v1/insights", null).json();
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertThat(((Number) totals.get("documents")).intValue()).isEqualTo(2);
        assertThat(((Number) totals.get("pages")).intValue()).isGreaterThan(0);
        assertThat(((Number) totals.get("highlights")).intValue()).isEqualTo(1);
        Map<String, Object> reading = (Map<String, Object>) data.get("reading");
        assertThat(((Number) reading.get("finished_total")).intValue()).isEqualTo(1);
        assertThat(((Number) reading.get("finished_this_year")).intValue()).isEqualTo(1);
        assertThat(((Number) reading.get("pages_read")).intValue()).isGreaterThan(0);
        List<Map<String, Object>> added = (List<Map<String, Object>>) data.get("added_by_month");
        assertThat(added).hasSize(12);
        assertThat(((Number) added.get(11).get("count")).intValue()).isEqualTo(2);
        assertThat(((Number) ((List<Map<String, Object>>) data.get("finished_by_month")).get(11).get("count")).intValue()).isEqualTo(1);
        assertThat((Map<String, Object>) data.get("kinds")).containsEntry("pdf", 1).containsEntry("docx", 1);
        assertThat((List<?>) data.get("top_tags")).isNotEmpty();
        assertThat((List<?>) data.get("largest")).hasSize(2);
        assertThat((List<?>) data.get("languages")).isNotEmpty();
    }

    @Test
    void exportaOCatalogoEmCsvJsonBibtexEMarkdown() throws Exception {
        String a = importReady("guia-pratico-de-python.pdf");
        importReady("manual-do-colaborador.docx");
        call("PATCH", "/api/v1/documents/" + a + "/reading", Map.of("shelf", "lendo", "favorite", true));

        Map<String, Object> csv = call("GET", "/api/v1/tools/export/catalog?format=csv", null).json();
        assertThat((String) csv.get("filename")).startsWith("lume-catalogo-").endsWith(".csv");
        assertThat((String) csv.get("content")).startsWith("﻿Título;Autor;Tipo").contains("Guia prático de Python").contains(";Lendo;sim;");
        assertThat(csv.get("count")).isEqualTo(2);

        Map<String, Object> json = call("GET", "/api/v1/tools/export/catalog?format=json", null).json();
        List<Object> items = Json.parseList((String) json.get("content"));
        assertThat(items).hasSize(2);
        assertThat((Map<String, Object>) items.get(0)).containsKeys("id", "title", "tags", "shelf", "summary");

        String bib = (String) call("GET", "/api/v1/tools/export/catalog?format=bibtex", null).json().get("content");
        assertThat(bib).contains("@misc{").contains("title = {Guia prático de Python}").contains("file = {guia-pratico-de-python.pdf}");

        String md = (String) call("GET", "/api/v1/tools/export/catalog?format=markdown", null).json().get("content");
        assertThat(md).startsWith("# Catálogo do Lume").contains("## Lendo (1)").contains("**Guia prático de Python**");

        Map<String, Object> one = call("GET", "/api/v1/tools/export/catalog?format=json&ids=" + a, null).json();
        assertThat(one.get("count")).isEqualTo(1);
        assertThat(call("GET", "/api/v1/tools/export/catalog?format=xls", null).status()).isEqualTo(400);
    }

    @Test
    void exportaNotasEmMarkdownComFrontMatter(@TempDir Path dir) throws Exception {
        String a = importReady("guia-pratico-de-python.pdf");
        call("POST", "/api/v1/documents/" + a + "/annotations", Map.of("kind", "highlight", "page", 2, "quote", "Crie sempre um ambiente virtual",
                "note", "Lembrar disso", "color", "rose"));
        assertThat(tools.exportMarkdownFolder(dir)).isEqualTo(1);
        Path note;
        try (var files = Files.list(dir)) {
            note = files.filter(p -> p.getFileName().toString().endsWith(".md")).findFirst().orElseThrow();
        }
        assertThat(note.getFileName().toString()).startsWith("Guia pr");
        String text = Files.readString(note);
        assertThat(text).startsWith("---\ntitle: \"Guia prático de Python\"").contains("lume_id: " + a).contains("## Destaques e notas")
                .contains("### Página 2").contains("> Crie sempre um ambiente virtual").contains("Lembrar disso").contains("rosé");
        // Sem janela, o seletor de pasta não existe: a API responde 501 em vez de travar.
        assertThat(call("POST", "/api/v1/tools/export/markdown-folder", null).status()).isEqualTo(501);
    }

    @Test
    void backupTemOBancoOsDocumentosEOLeiame(@TempDir Path dir) throws Exception {
        String a = importReady("guia-pratico-de-python.pdf");
        Path zip = dir.resolve("backup.zip");
        Map<String, Object> result = tools.backupTo(zip);
        assertThat(result).containsEntry("ok", true).containsEntry("documents", 1L);
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            file.stream().map(ZipEntry::getName).forEach(names::add);
            Path db = dir.resolve("restaurado.db");
            Files.copy(file.getInputStream(file.getEntry("lume.db")), db);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 ResultSet rs = connection.createStatement().executeQuery("SELECT count(*) FROM documents")) {
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
        assertThat(names).contains("lume.db", "LEIAME.txt", "documentos/" + a + "/original.pdf", "documentos/" + a + "/capa.jpg");
        assertThat(dir.resolve("backup.zip.part")).doesNotExist();
        assertThat(call("POST", "/api/v1/tools/backup", null).status()).isEqualTo(501);
    }

    @Test
    @SuppressWarnings("unchecked")
    void manutencaoConfereReindexaOtimizaELimpa() throws Exception {
        importReady("guia-pratico-de-python.pdf");
        Path orphan = store.documentsDir().resolve("pasta-perdida");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("lixo.txt"), "sobra");
        Files.createDirectories(store.tmpDir());
        Path old = store.tmpDir().resolve("velho.part");
        Files.writeString(old, "x");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

        Map<String, Object> check = call("POST", "/api/v1/tools/maintenance", Map.of("action", "check")).json();
        assertThat(check).containsEntry("integrity", "ok").containsEntry("documents", 1).containsEntry("orphan_folders", 1);
        assertThat((List<?>) check.get("missing_files")).isEmpty();
        assertThat(((Number) check.get("temp_files")).intValue()).isGreaterThanOrEqualTo(1);

        Map<String, Object> cleanup = call("POST", "/api/v1/tools/maintenance", Map.of("action", "cleanup")).json();
        assertThat(((Number) cleanup.get("removed")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(orphan).doesNotExist();
        assertThat(old).doesNotExist();

        db.update("DELETE FROM documents_fts");
        assertThat(((Number) call("GET", "/api/v1/documents?q=ambiente", null).json().get("count")).intValue()).isZero();
        Map<String, Object> reindex = call("POST", "/api/v1/tools/maintenance", Map.of("action", "reindex")).json();
        assertThat(reindex.get("reindexed")).isEqualTo(1);
        assertThat(((Number) call("GET", "/api/v1/documents?q=ambiente", null).json().get("count")).intValue()).isEqualTo(1);

        Map<String, Object> optimize = call("POST", "/api/v1/tools/maintenance", Map.of("action", "optimize")).json();
        assertThat(((Number) optimize.get("after_bytes")).longValue()).isPositive();

        Map<String, Object> redescribe = call("POST", "/api/v1/tools/maintenance", Map.of("action", "redescribe")).json();
        assertThat(redescribe.get("requeued")).isEqualTo(1);
        settle();
        assertThat(call("POST", "/api/v1/tools/maintenance", Map.of("action", "formatar")).status()).isEqualTo(400);
        assertThat(check.get("missing_files")).isInstanceOf(List.class);
        assertThat((Map<String, Object>) check).containsKey("orphan_bytes");
    }
}
