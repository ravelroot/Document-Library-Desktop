package io.github.ravelroot.lume.docx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ravelroot.lume.core.ProcessingRejected;
import io.github.ravelroot.lume.processing.ExtractionResult;
import io.github.ravelroot.lume.processing.HtmlSanitizer;
import io.github.ravelroot.lume.processing.OutlineItem;

class DocxExtractorTest {

    static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    @TempDir
    Path dir;

    private final DocxExtractor extractor = new DocxExtractor();

    private Path makeDocx(String body, Map<String, String> extra) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        files.put("_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                + "Target=\"word/document.xml\"/></Relationships>");
        files.put("word/document.xml", "<?xml version=\"1.0\"?><w:document " + W + "><w:body>" + body + "</w:body></w:document>");
        files.putAll(extra);
        Path file = Files.createTempFile(dir, "teste", ".docx");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void osQuatroDocxDeExemploViramHtmlComSumarioTituloEAutor() throws IOException {
        for (String name : new String[] {"manual-do-colaborador.docx", "politica-de-seguranca-da-informacao.docx",
            "ata-reuniao-planejamento-4-trimestre.docx", "proposta-comercial-projeto-atlas.docx"}) {
            Path out = Files.createTempDirectory(dir, "out");
            ExtractionResult r = extractor.extract(Path.of("exemplos", name), out);
            assertThat(r.kind()).isEqualTo("docx");
            assertThat(r.title()).as(name).isNotBlank();
            assertThat(r.author()).as(name).isNotBlank();
            assertThat(r.outline()).as(name + ": sumário").hasSizeGreaterThanOrEqualTo(5);
            assertThat(r.wordCount()).as(name + ": texto").isGreaterThan(300);
            assertThat(r.html()).containsPattern("<h2 id=\"s-\\d+\" data-b=\"\\d+\">");
            assertThat(r.pageCount()).isEqualTo(3);
        }
    }

    @Test
    void listasTabelasMescladasLinksEFormatacao() throws IOException {
        String body = """
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Seção um</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">negrito </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>itálico</w:t></w:r><w:hyperlink r:id="rIdL"><w:r><w:t>site</w:t></w:r></w:hyperlink><w:hyperlink r:id="rIdJ"><w:r><w:t>perigo</w:t></w:r></w:hyperlink></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>um</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>um.a</w:t></w:r></w:p>
                <w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Cabeçalho</w:t></w:r></w:p></w:tc></w:tr>
                  <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
                  <w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr>""";
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("word/styles.xml", "<?xml version=\"1.0\"?><w:styles " + W + "><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style></w:styles>");
        extra.put("word/numbering.xml", "<?xml version=\"1.0\"?><w:numbering " + W + "><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl>"
                + "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>");
        extra.put("word/_rels/document.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdS\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rIdN\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"
                + "<Relationship Id=\"rIdL\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://exemplo.org/a?b=1\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"rIdJ\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"javascript:alert(1)\" TargetMode=\"External\"/></Relationships>");
        ExtractionResult r = extractor.extract(makeDocx(body, extra), dir);
        assertThat(r.html()).contains("<h2 id=\"s-1\" data-b=\"0\">Seção um</h2>");
        assertThat(r.html()).contains("<strong>negrito </strong><em>itálico</em><a href=\"https://exemplo.org/a?b=1\" rel=\"noopener noreferrer\">site</a>perigo");
        assertThat(r.html()).containsPattern("<ol data-b=\"\\d+\"><li>um<ul><li>um.a</li></ul></li></ol>");
        assertThat(r.html()).contains("<th colspan=\"2\">Cabeçalho</th>");
        assertThat(r.html()).contains("<td rowspan=\"2\">A</td><td>B</td></tr><tr><td>C</td>");
        assertThat(r.pageWidth()).isEqualTo(595.3);
        assertThat(r.outline()).containsExactly(new OutlineItem("Seção um", null, 1, "s-1"));
        assertThat(r.text()).contains("A\tB");
    }

    @Test
    void docxInvalidosProtegidosEMalformadosSaoRecusados() throws IOException {
        Path ole = dir.resolve("senha.docx");
        byte[] head = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        Files.write(ole, concat(head, "EncryptionInfo".getBytes(StandardCharsets.UTF_16LE)));
        assertThatThrownBy(() -> extractor.extract(ole, dir)).isInstanceOfSatisfying(ProcessingRejected.class,
                e -> assertThat(e.code()).isEqualTo("encrypted"));
        Path broken = dir.resolve("quebrado.docx");
        Files.write(broken, "PK\u0003\u0004 lixo".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> extractor.extract(broken, dir)).isInstanceOf(ProcessingRejected.class).hasMessageContaining("corrompido");
        Path malformed = makeDocx("<w:p><w:r><w:t>sem fechar</w:r></w:p>", Map.of());
        assertThatThrownBy(() -> extractor.extract(malformed, dir)).isInstanceOf(ProcessingRejected.class).hasMessageContaining("XML inválido");
        Path entity = makeDocx("<w:p><w:r><w:t>&xxe;</w:t></w:r></w:p>", Map.of());
        assertThatThrownBy(() -> extractor.extract(entity, dir)).as("entidades desconhecidas não são expandidas")
                .isInstanceOf(ProcessingRejected.class);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void caminhosDasRelacoesNaoSaemDoPacote() {
        assertThat(DocxPackage.resolve("word/document.xml", "media/a.png")).isEqualTo("word/media/a.png");
        assertThat(DocxPackage.resolve("word/document.xml", "../customXml/x.xml")).isEqualTo("customXml/x.xml");
        assertThat(DocxPackage.resolve("word/document.xml", "../../../etc/passwd")).isNull();
        assertThat(DocxPackage.resolve("word/document.xml", "/word/media/b.png")).isEqualTo("word/media/b.png");
    }

    @Test
    void htmlDeLeituraPassaPorListaDePermissoes() {
        String id = "11111111-2222-4333-8444-555555555555";
        String html = HtmlSanitizer.readerHtml("<h2 id=\"s-1\" data-b=\"0\" onclick=\"x()\">T</h2><script>alert(1)</script>"
                + "<img src=\"media/a.png\" onerror=\"x\"><img src=\"https://rastreio.example/p.gif\"><a href=\"javascript:alert(1)\">x</a>"
                + "<p class=\"subtitulo outra\" style=\"color:red\">s</p>", id);
        assertThat(html).doesNotContain("<script").doesNotContain("onclick").doesNotContain("onerror")
                .doesNotContain("rastreio.example").doesNotContain("javascript:");
        assertThat(html).contains("<img src=\"/api/v1/documents/" + id + "/media/a.png\" loading=\"lazy\">");
        assertThat(html).contains("<p class=\"subtitulo\">s</p>");
    }
}
