package io.github.ravelroot.lume.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ravelroot.lume.core.ImportRejected;
import io.github.ravelroot.lume.library.LibraryService;

/** A API local de ponta a ponta, com o PDFBox, o leitor de DOCX e o resumo local de verdade. */
class ApiTest extends IntegrationSupport {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Reply reply) {
        return (List<Map<String, Object>>) reply.json().get("results");
    }

    @Test
    void importaPdfEDocxProcessaENaoDuplica() throws Exception {
        LibraryService.ImportResult pdf = library.importFile(example("guia-pratico-de-python.pdf"), null);
        LibraryService.ImportResult docx = library.importFile(example("manual-do-colaborador.docx"), null);
        assertThat(pdf.duplicate()).isFalse();
        assertThat(pdf.document().get("status")).isEqualTo("queued");
        assertThat(docx.document().get("kind")).isEqualTo("docx");
        settle();

        LibraryService.ImportResult again = library.importFile(example("guia-pratico-de-python.pdf"), null);
        assertThat(again.duplicate()).isTrue();
        assertThat(again.document().get("id")).isEqualTo(pdf.document().get("id"));

        Reply list = call("GET", "/api/v1/documents", null);
        assertThat(((Number) list.json().get("count")).intValue()).isEqualTo(2);
        for (Map<String, Object> d : results(list)) {
            assertThat(d.get("status")).as(String.valueOf(d.get("status_detail"))).isEqualTo("ready");
            assertThat((String) d.get("summary")).hasSizeGreaterThan(20);
            assertThat(d.get("cover_url")).isNotNull();
            assertThat((List<?>) d.get("tags")).isNotEmpty();
        }
        Map<String, Object> word = results(list).stream().filter(d -> "docx".equals(d.get("kind"))).findFirst().orElseThrow();
        assertThat(word.get("title")).isEqualTo("Manual do colaborador");
        assertThat(word.get("has_reader_html")).isEqualTo(true);
        Reply reader = call("GET", "/api/v1/documents/" + word.get("id") + "/reader", null);
        assertThat((String) reader.json().get("html")).contains("<h1 id=\"s-1\" data-b=\"0\">Manual do colaborador</h1>");
        assertThat((List<?>) reader.json().get("outline")).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void recusaArquivosQueNaoSaoPdfNemDocx(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("falso.pdf");
        Files.writeString(fake, "isto não é um pdf");
        assertThatThrownBy(() -> library.importFile(fake, null)).isInstanceOf(ImportRejected.class).hasMessageContaining("não corresponde a um arquivo .pdf");
        Path doc = dir.resolve("antigo.doc");
        Files.write(doc, new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1, 0, 0});
        assertThatThrownBy(() -> library.importFile(doc, null)).hasMessageContaining(".doc antigos não são suportados");
        Path empty = dir.resolve("vazio.pdf");
        Files.writeString(empty, "");
        assertThatThrownBy(() -> library.importFile(empty, null)).hasMessageContaining("vazio");
        assertThatThrownBy(() -> library.importFile(dir.resolve("nao-existe.pdf"), null)).hasMessageContaining("não foi encontrado");
        assertThat(db.scalar("SELECT count(*) FROM documents")).isZero();
        try (var tmp = Files.list(store.tmpDir())) {
            assertThat(tmp.toList()).as("não sobra arquivo temporário").isEmpty();
        }
    }

    @Test
    void buscaIgnoraAcentosAceitaPrefixoFraseEExclusaoEMarcaOTrecho() throws Exception {
        for (String f : List.of("ata-reuniao-planejamento-4-trimestre.docx", "politica-de-seguranca-da-informacao.docx", "guia-pratico-de-python.pdf")) {
            library.importFile(example(f), null);
        }
        settle();
        Reply r = call("GET", "/api/v1/documents?q=reuniao", null);
        assertThat((String) results(r).get(0).get("snippet")).contains("<mark>reunião</mark>");
        r = call("GET", "/api/v1/documents?q=seguran", null);
        assertThat(results(r).get(0).get("title")).isEqualTo("Política de segurança da informação");
        r = call("GET", "/api/v1/documents?q=%22reuniao+de+planejamento%22", null);
        assertThat(((Number) r.json().get("count")).intValue()).isEqualTo(1);
        r = call("GET", "/api/v1/documents?q=python+-fun%C3%A7%C3%B5es", null);
        assertThat(results(r)).allMatch(d -> "pdf".equals(d.get("kind")));
        r = call("GET", "/api/v1/documents?q=%3Cxyzzy%3E%22%27%29", null);
        assertThat(((Number) r.json().get("count")).intValue()).as("caracteres especiais não quebram a consulta").isZero();
    }

    @Test
    void etiquetasDaPessoaSubstituemAsDaIa() throws Exception {
        String id = (String) library.importFile(example("proposta-comercial-projeto-atlas.docx"), null).document().get("id");
        settle();
        Reply put = call("PUT", "/api/v1/documents/" + id + "/tags", Map.of("tags", List.of("Proposta", "proposta", "  Cliente  Atlas ")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) put.json().get("tags");
        assertThat(tags).extracting(t -> t.get("name") + "/" + t.get("source")).containsExactly("cliente atlas/usuario", "proposta/usuario");
        assertThat(call("GET", "/api/v1/tags", null).list()).hasSize(2);
        Reply tooMany = call("PUT", "/api/v1/documents/" + id + "/tags", Map.of("tags", IntStream.range(0, 13).mapToObj(i -> "t" + i).toList()));
        assertThat(tooMany.status()).isEqualTo(400);
    }

    @Test
    void edicaoDeTituloTravaOsMetadadosEEstantesFavoritosEProgresso() throws Exception {
        String id = (String) library.importFile(example("guia-pratico-de-python.pdf"), null).document().get("id");
        settle();
        assertThat(call("PATCH", "/api/v1/documents/" + id, Map.of("title", "   ")).status()).isEqualTo(400);
        Reply edited = call("PATCH", "/api/v1/documents/" + id, Map.of("title", "Álgebra para curiosos", "author", "Zeca"));
        assertThat(edited.json().get("title")).isEqualTo("Álgebra para curiosos");
        call("POST", "/api/v1/documents/" + id + "/reprocess", null);
        settle();
        assertThat(call("GET", "/api/v1/documents/" + id, null).json().get("title")).isEqualTo("Álgebra para curiosos");
        assertThat(((Number) call("GET", "/api/v1/documents?q=algebra", null).json().get("count")).intValue()).isEqualTo(1);

        Map<String, Object> opened = call("POST", "/api/v1/documents/" + id + "/open", null).json();
        assertThat(opened.get("shelf")).isEqualTo("lendo");
        Map<String, Object> reading = call("PATCH", "/api/v1/documents/" + id + "/reading", Map.of("page", 999, "progress", 3, "favorite", true)).json();
        assertThat(((Number) reading.get("progress")).doubleValue()).isEqualTo(1.0);
        Map<String, Object> stats = call("GET", "/api/v1/stats", null).json();
        assertThat(((Number) stats.get("favorites")).intValue()).isEqualTo(1);
        assertThat(call("PATCH", "/api/v1/documents/" + id + "/reading", Map.of("shelf", "lido")).json().get("finished_at")).isNotNull();
        assertThat(call("PATCH", "/api/v1/documents/" + id + "/reading", Map.of("shelf", "talvez")).status()).isEqualTo(400);
        assertThat(((Number) call("GET", "/api/v1/documents?shelf=lido&favorite=1", null).json().get("count")).intValue()).isEqualTo(1);
    }

    @Test
    void anotacoesEColecoes() throws Exception {
        String a = (String) library.importFile(example("contos-da-serra.pdf"), null).document().get("id");
        String b = (String) library.importFile(example("caderno-de-receitas-da-vo-lucia.pdf"), null).document().get("id");
        settle();
        String url = "/api/v1/documents/" + a + "/annotations";
        assertThat(call("POST", url, Map.of("kind", "note", "note", "  ")).status()).isEqualTo(400);
        assertThat(call("POST", url, Map.of("kind", "highlight", "color", "azul", "quote", "x")).status()).isEqualTo(400);
        assertThat(call("POST", url, Map.of("kind", "highlight", "color", "salvia", "quote", "Pedra Alta", "page", 1)).status()).isEqualTo(201);
        String note = (String) call("POST", url, Map.of("kind", "note", "note", "Revisar", "page", 2)).json().get("id");
        assertThat(call("PATCH", "/api/v1/annotations/" + note, Map.of("color", "ardosia")).json().get("color")).isEqualTo("ardosia");
        assertThat((String) call("GET", url + "/export", null).json().get("content")).contains("## Página 1\n\n> Pedra Alta");
        assertThat(call("DELETE", "/api/v1/annotations/" + note, null).status()).isEqualTo(204);
        assertThat(call("DELETE", "/api/v1/annotations/" + note, null).status()).isEqualTo(404);

        String col = (String) call("POST", "/api/v1/collections", Map.of("name", "Fim de semana")).json().get("id");
        assertThat(call("POST", "/api/v1/collections", Map.of("name", "fim de SEMANA")).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/collections/" + col + "/documents", Map.of("document_ids", List.of(a, b, a))).json().get("added")).isEqualTo(2);
        call("PUT", "/api/v1/collections/" + col + "/order", Map.of("document_ids", List.of(b, a)));
        assertThat(results(call("GET", "/api/v1/documents?collection=" + col, null))).extracting(d -> d.get("id")).containsExactly(b, a);
        call("DELETE", "/api/v1/documents/" + a, null);
        assertThat(((Number) call("GET", "/api/v1/collections/" + col, null).json().get("document_count")).intValue()).isEqualTo(1);
        assertThat(Files.exists(store.folderOf(a))).as("a pasta do documento é apagada").isFalse();
    }

    @Test
    void arquivoOriginalComRangeETagEDownload() throws Exception {
        String id = (String) library.importFile(example("guia-pratico-de-python.pdf"), null).document().get("id");
        long size = Files.size(example("guia-pratico-de-python.pdf"));
        HttpResponse<byte[]> full = bytes("/api/v1/documents/" + id + "/file");
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).hasSize((int) size);
        HttpResponse<byte[]> part = bytes("/api/v1/documents/" + id + "/file", "Range", "bytes=0-99");
        assertThat(part.statusCode()).isEqualTo(206);
        assertThat(new String(part.body(), 0, 5)).isEqualTo("%PDF-");
        assertThat(part.headers().firstValue("Content-Range")).hasValue("bytes 0-99/" + size);
        assertThat(bytes("/api/v1/documents/" + id + "/file", "Range", "bytes=-10").body()).hasSize(10);
        assertThat(bytes("/api/v1/documents/" + id + "/file", "Range", "bytes=" + (size + 5) + "-").statusCode()).isEqualTo(416);
        String etag = full.headers().firstValue("ETag").orElseThrow();
        assertThat(bytes("/api/v1/documents/" + id + "/file", "If-None-Match", etag).statusCode()).isEqualTo(304);
        db.update("UPDATE documents SET original_name = 'relatório final.pdf' WHERE id = ?", id);
        assertThat(bytes("/api/v1/documents/" + id + "/file?download=1").headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"relatrio final.pdf\"; filename*=UTF-8''relat%C3%B3rio%20final.pdf");
        settle();
    }

    @Test
    void errosSeguemOFormatoESessaoEObrigatoria() throws Exception {
        Reply r = call("GET", "/api/v1/documents/nao-e-uuid", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error")).isEqualTo("not_found");
        assertThat(call("GET", "/api/v1/documents/00000000-0000-4000-8000-000000000000", null).json().get("message")).isEqualTo("Documento não encontrado.");
        assertThat(call("DELETE", "/api/v1/stats", null).status()).isEqualTo(405);
        assertThat(call("GET", "/api/v1/rota/que/nao/existe", null).status()).isEqualTo(404);
        Reply broken = raw("PATCH", "/api/v1/me", "{não é json", true);
        assertThat(broken.status()).isEqualTo(400);
        assertThat(broken.json().get("error")).isEqualTo("parse_error");

        assertThat(raw("GET", "/api/v1/me", null, false).status()).as("sem o cookie da janela").isEqualTo(401);
        assertThat(raw("GET", "/", null, false).status()).isEqualTo(403);
        assertThat(raw("POST", "/api/v1/collections", "{\"name\":\"x\"}", true, "Origin", "https://site-malicioso.example").status()).isEqualTo(403);
        Reply wrongKey = raw("GET", "/entrar?chave=errada", null, false);
        assertThat(wrongKey.status()).isEqualTo(403);
        Reply enter = raw("GET", "/entrar?chave=chave-de-teste", null, false);
        assertThat(enter.status()).isEqualTo(303);
        assertThat(enter.header("Set-Cookie")).contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void preferenciasPerfilEChaveDaIaGuardadaSemAparecer() throws Exception {
        Map<String, Object> me = call("PATCH", "/api/v1/me", Map.of("name", "Erik", "theme", "dark", "reader_prefs", Map.of("theme", "sepia"))).json();
        assertThat(me).containsEntry("name", "Erik").containsEntry("initials", "ER").containsEntry("theme", "dark");
        assertThat(call("PATCH", "/api/v1/me", Map.of("name", " ")).status()).isEqualTo(400);
        Map<String, Object> ai = call("PUT", "/api/v1/settings/ai", Map.of("provider", "anthropic", "api_key", "chave-secreta-123")).json();
        assertThat(ai).containsEntry("has_key", true).containsEntry("model", "claude-haiku-5-5");
        assertThat(ai.toString()).doesNotContain("chave-secreta");
        assertThat(db.one("SELECT value FROM settings WHERE key = 'ai'").str("value")).doesNotContain("chave-secreta-123");
        assertThat(call("PUT", "/api/v1/settings/ai", Map.of("base_url", "ftp://x")).status()).isEqualTo(400);
        Map<String, Object> cleared = call("PUT", "/api/v1/settings/ai", Map.of("api_key", "")).json();
        assertThat(cleared).containsEntry("has_key", false).containsEntry("configured", false);
    }

    @Test
    void paginaInicialComDadosDePartidaCspEArquivosDaInterface() throws Exception {
        Reply page = call("GET", "/documento/123", null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<script id=\"boot\" type=\"application/json\">{\"user\":");
        assertThat(page.header("Content-Security-Policy")).contains("script-src 'self'");
        Reply css = call("GET", "/css/app.css", null);
        assertThat(css.status()).isEqualTo(200);
        assertThat(css.header("Content-Type")).contains("text/css");
        assertThat(call("GET", "/vendor/pdfjs/pdf.min.mjs", null).header("Content-Type")).contains("javascript");
        assertThat(call("GET", "/..%2F..%2Fpom.xml", null).status()).isIn(400, 404);
    }

    @Test
    void imagensDoDocxSoSaemDaPastaMediaDoDocumento() throws Exception {
        String id = (String) library.importFile(example("manual-do-colaborador.docx"), null).document().get("id");
        settle();
        String html = (String) call("GET", "/api/v1/documents/" + id + "/reader", null).json().get("html");
        String src = html.replaceAll("(?s).*?<img src=\"([^\"]+)\".*", "$1");
        assertThat(src).matches("/api/v1/documents/" + id + "/media/[A-Za-z0-9_.-]+");
        HttpResponse<byte[]> image = bytes(src);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type").orElse("")).startsWith("image/");
        assertThat(call("GET", "/api/v1/documents/" + id + "/media/..%2F..%2Flume.db", null).status()).isIn(400, 404);
        assertThat(call("GET", "/api/v1/documents/" + id + "/media/nao-existe.png", null).status()).isEqualTo(404);
    }

    @Test
    void importacaoDePastaEncontraPdfsEDocxNasSubpastas(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub/mais"));
        for (String name : List.of("a.pdf", "sub/b.DOCX", "sub/mais/c.pdf", "nota.txt", "~$rascunho.docx", ".oculto.pdf")) {
            Files.writeString(dir.resolve(name), "x");
        }
        assertThat(library.scanFolder(dir).stream().map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList())
                .containsExactly("a.pdf", "sub/b.DOCX", "sub/mais/c.pdf");
    }
}
