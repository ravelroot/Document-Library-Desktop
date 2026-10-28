package io.github.ravelroot.lume.processing;

import java.nio.file.Path;
import java.util.Map;

/**
 * Leitura de PDF e desenho de capas. Fazia o papel da janela invisível com pdf.js na versão em Electron; aqui é o
 * Apache PDFBox ({@link PdfBoxProcessor}). Os testes do pipeline usam um processador de mentira.
 */
public interface DocumentProcessor {

    /**
     * Metadados, sumário (marcadores), texto completo com \f entre as páginas e a capa da primeira página útil.
     *
     * @throws io.github.ravelroot.lume.core.ProcessingRejected arquivo corrompido ou protegido por senha (não adianta tentar de novo)
     * @throws Exception                                         falha passageira (o pipeline tenta de novo)
     */
    ExtractionResult extractPdf(Path file, int maxChars, int coverWidth) throws Exception;

    /** Capa tipográfica nas cores da paleta (DOCX e PDFs sem capa). */
    Cover renderCover(CoverRequest request) throws Exception;

    /** Estado para a tela Sistema. */
    Map<String, Object> health();

    /** Prepara o que for demorado (cache de fontes) em segundo plano. */
    default void warmUp() {
    }
}
