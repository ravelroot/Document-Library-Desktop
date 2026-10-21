package io.github.ravelroot.lume.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ravelroot.lume.core.ProcessingRejected;

class PdfExtractorTest {

    @Test
    void lePdfDeExemploComTextoSumarioMetadadosECapa() throws Exception {
        ExtractionResult r = PdfExtractor.extract(Path.of("exemplos", "guia-pratico-de-python.pdf"), 2_000_000, 480);
        assertThat(r.pageCount()).isGreaterThan(1);
        assertThat(r.text()).contains("\f").containsIgnoringCase("python");
        assertThat(r.text().split("\f")).hasSize(r.pageCount());
        assertThat(r.wordCount()).isGreaterThan(300);
        assertThat(r.title()).isNotBlank();
        assertThat(r.pageWidth()).isPositive();
        Cover cover = r.cover();
        assertThat(cover).isNotNull();
        assertThat(cover.generated()).isFalse();
        assertThat(cover.width()).isEqualTo(480);
        assertThat(cover.color()).matches("#[0-9a-f]{6}");
        assertThat(ImageIO.read(new ByteArrayInputStream(cover.bytes()))).isNotNull();
    }

    @Test
    void textoGrandeECortadoNoLimite() throws Exception {
        ExtractionResult r = PdfExtractor.extract(Path.of("exemplos", "guia-pratico-de-python.pdf"), 500, 480);
        assertThat(r.textTruncated()).isTrue();
        assertThat(r.text().length()).isLessThanOrEqualTo(500);
    }

    @Test
    void arquivoQueNaoEPdfEPdfCorrompidoSaoRecusados(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("falso.pdf");
        Files.writeString(fake, "isto não é um pdf");
        assertThatThrownBy(() -> PdfExtractor.extract(fake, 1000, 480)).isInstanceOf(ProcessingRejected.class).hasMessageContaining("%PDF");
        Path broken = dir.resolve("quebrado.pdf");
        Files.writeString(broken, "%PDF-1.7\nlixo sem objetos");
        assertThatThrownBy(() -> PdfExtractor.extract(broken, 1000, 480)).isInstanceOf(ProcessingRejected.class);
    }

    @Test
    void capaTipograficaUsaAPaletaEMantemProporcao() throws Exception {
        Palette.Tone tone = Palette.toneFor("Estudo de caso");
        Cover cover = TypographicCover.render(new CoverRequest("Estudo de caso: migração para a nuvem com um título bem comprido para quebrar",
                "Autora Exemplo", "PDF", tone.hex(), tone.ink(), 480));
        assertThat(cover.width()).isEqualTo(480);
        assertThat(cover.height()).isEqualTo(640);
        assertThat(cover.generated()).isTrue();
        assertThat(ImageIO.read(new ByteArrayInputStream(cover.bytes())).getWidth()).isEqualTo(480);
    }
}
