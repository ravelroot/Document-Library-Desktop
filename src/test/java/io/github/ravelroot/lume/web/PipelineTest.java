package io.github.ravelroot.lume.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import io.github.ravelroot.lume.ai.HttpTransport;
import io.github.ravelroot.lume.core.ProcessingRejected;
import io.github.ravelroot.lume.core.Times;
import io.github.ravelroot.lume.processing.Cover;
import io.github.ravelroot.lume.processing.CoverRequest;
import io.github.ravelroot.lume.processing.DocumentProcessor;
import io.github.ravelroot.lume.processing.ExtractionResult;
import io.github.ravelroot.lume.processing.OutlineItem;
import io.github.ravelroot.lume.processing.Palette;
import io.github.ravelroot.lume.processing.TypographicCover;

/** Fila, novas tentativas e falhas, com um processador de mentira que pode ser programado para falhar. */
@Import(PipelineTest.Fakes.class)
class PipelineTest extends IntegrationSupport {

    /** Processador de mentira: texto fixo para PDFs e capa de verdade nas capas tipográficas. */
    static class FakeProcessor implements DocumentProcessor {
        final Deque<Exception> failures = new ArrayDeque<>();
        final AtomicInteger pdfCalls = new AtomicInteger();
        volatile boolean withoutCover;

        @Override
        public synchronized ExtractionResult extractPdf(Path file, int maxChars, int coverWidth) throws Exception {
            pdfCalls.incrementAndGet();
            Exception failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            ExtractionResult result = new ExtractionResult("pdf").pageCount(4).title("Guia de teste em PDF").author("Autora Exemplo")
                    .text("Python é uma linguagem de programação muito usada em ciência de dados. Ela tem uma sintaxe simples e clara.\f"
                            + "A segunda página fala de funções, módulos e testes automatizados com exemplos práticos.\fTerceira.\fQuarta.")
                    .wordCount(40).outline(List.of(new OutlineItem("Introdução", 1, 1, null)));
            return withoutCover ? result : result.cover(new Cover(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}, 480, 640, "#ffffff", false));
        }

        @Override
        public Cover renderCover(CoverRequest request) {
            return TypographicCover.render(request);
        }

        @Override
        public Map<String, Object> health() {
            return Map.of("online", true);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeProcessor fakeProcessor() {
            return new FakeProcessor();
        }

        @Bean
        @Primary
        HttpTransport unavailableAi() {
            return (url, headers, body, timeout) -> new HttpTransport.Reply(503, "serviço fora");
        }
    }

    @Autowired
    FakeProcessor processor;

    @BeforeEach
    void resetProcessor() {
        processor.failures.clear();
        processor.pdfCalls.set(0);
        processor.withoutCover = false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jobs(String id, String stage) throws Exception {
        List<Map<String, Object>> all = (List<Map<String, Object>>) call("GET", "/api/v1/system", null).json().get("jobs");
        return all.stream().filter(j -> id.equals(j.get("document_id")) && stage.equals(j.get("stage"))).toList();
    }

    @Test
    void falhaPassageiraTentaDeNovoETerminaPronta() throws Exception {
        processor.failures.add(new IllegalStateException("O processador parou."));
        String id = (String) library.importFile(example("guia-pratico-de-python.pdf"), null).document().get("id");
        settle();
        assertThat(processor.pdfCalls.get()).isEqualTo(2);
        assertThat(call("GET", "/api/v1/documents/" + id, null).json().get("status")).isEqualTo("ready");
        assertThat(jobs(id, "extract")).extracting(j -> j.get("status")).containsExactlyInAnyOrder("retrying", "succeeded");
    }

    @Test
    void pdfProtegidoFalhaNaHoraEVoltaComTentarDeNovo() throws Exception {
        processor.failures.add(new ProcessingRejected("O PDF está protegido por senha e não pode ser aberto.", "encrypted"));
        String id = (String) library.importFile(example("contos-da-serra.pdf"), null).document().get("id");
        settle();
        Map<String, Object> doc = call("GET", "/api/v1/documents/" + id, null).json();
        assertThat(doc.get("status")).isEqualTo("failed");
        assertThat((String) doc.get("status_detail")).contains("protegido por senha");
        assertThat(processor.pdfCalls.get()).as("não insiste").isEqualTo(1);
        assertThat(call("POST", "/api/v1/system/retry-failed", null).json().get("requeued")).isEqualTo(1);
        settle();
        assertThat(call("GET", "/api/v1/documents/" + id, null).json().get("status")).isEqualTo("ready");
    }

    @Test
    void depoisDeTresFalhasODocumentoFicaComErro() throws Exception {
        for (int i = 0; i < 3; i++) {
            processor.failures.add(new IllegalStateException("travou"));
        }
        String id = (String) library.importFile(example("redes-neurais-sem-misterio.pdf"), null).document().get("id");
        settle();
        Map<String, Object> doc = call("GET", "/api/v1/documents/" + id, null).json();
        assertThat(doc.get("status")).isEqualTo("failed");
        assertThat((String) doc.get("status_detail")).contains("várias tentativas");
        assertThat(processor.pdfCalls.get()).isEqualTo(3);
    }

    @Test
    void pdfSemCapaGanhaCapaTipograficaNasCoresDaPaleta() throws Exception {
        processor.withoutCover = true;
        String id = (String) library.importFile(example("estudo-de-caso-migracao-para-a-nuvem.pdf"), null).document().get("id");
        settle();
        Map<String, Object> doc = call("GET", "/api/v1/documents/" + id, null).json();
        assertThat(doc.get("cover_generated")).isEqualTo(true);
        assertThat(Palette.TONES).anyMatch(t -> t.hex().equals(doc.get("cover_color")));
        assertThat(bytes("/api/v1/documents/" + id + "/cover?v=1").headers().firstValue("Content-Type")).hasValue("image/jpeg");
    }

    @Test
    void aoAbrirDeNovoOQueFicouPelaMetadeVoltaParaAFila() throws Exception {
        String id = (String) library.importFile(example("introducao-ao-postgresql.pdf"), null).document().get("id");
        settle();
        db.update("UPDATE documents SET status = 'extracting' WHERE id = ?", id);
        db.update("INSERT INTO jobs (document_id, stage, status, started_at) VALUES (?, 'extract', 'running', ?)", id, Times.now());
        pipeline.recover();
        settle();
        assertThat(call("GET", "/api/v1/documents/" + id, null).json().get("status")).isEqualTo("ready");
        assertThat(db.scalar("SELECT count(*) FROM jobs WHERE status = 'running'")).isZero();
    }

    @Test
    void descricaoDeNovoCaiParaOResumoLocalSeOProvedorFalhar() throws Exception {
        String id = (String) library.importFile(example("manual-do-colaborador.docx"), null).document().get("id");
        settle();
        call("PUT", "/api/v1/settings/ai", Map.of("provider", "openai", "model", "modelo-x", "base_url", "http://localhost:9/v1"));
        assertThat(call("POST", "/api/v1/documents/" + id + "/describe", null).status()).isEqualTo(200);
        settle();
        assertThat(call("GET", "/api/v1/documents/" + id, null).json().get("summary_source")).isEqualTo("local");
        Map<String, Object> job = jobs(id, "describe").get(0);
        assertThat((String) job.get("error")).contains("503");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) job.get("details");
        assertThat(details.get("fallback")).isEqualTo(true);
    }
}
