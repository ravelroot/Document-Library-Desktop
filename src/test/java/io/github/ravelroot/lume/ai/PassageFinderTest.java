package io.github.ravelroot.lume.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Trechos e pontuação do "Perguntar ao documento" no modo local. */
class PassageFinderTest {

    private static final String PDF = "Capítulo 1. Instalação\nInstale o Python e crie um ambiente virtual para cada projeto. "
            + "O ambiente virtual isola as bibliotecas.\f"
            + "Capítulo 2. Arquivos\nPara ler um arquivo CSV use o módulo csv e abra o arquivo com encoding utf-8. "
            + "Escrever arquivos é parecido.\f"
            + "Capítulo 3. Datas\nO módulo datetime trabalha com datas, horas e fusos horários.";

    @Test
    void termosIgnoramAcentosPalavrasVaziasERepeticoes() {
        assertThat(PassageFinder.terms("Como é que se cria um Ambiente virtual? ambientes")).containsExactly("cria", "ambien", "virtua");
        assertThat(PassageFinder.terms("de que o")).isEmpty();
    }

    @Test
    void separaPorPaginaEOrdenaPelaPergunta() {
        List<PassageFinder.Passage> chunks = PassageFinder.chunks(PDF, true);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(PassageFinder.Passage::page).containsExactly(1, 2, 3);
        List<PassageFinder.Passage> ranked = PassageFinder.rank(chunks, "Como abrir um arquivo csv?", 4);
        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).page()).isEqualTo(2);
        assertThat(ranked.get(0).score()).isPositive();
        assertThat(PassageFinder.rank(chunks, "fotossíntese clorofila", 4)).isEmpty();
    }

    @Test
    void cabecalhosERodapesRepetidosSaem() {
        String pdf = "Guia de Python · Marina\n1\nInstalação do Python no Windows com o instalador oficial e a opção PATH.\f"
                + "Guia de Python · Marina\n2\nArquivos CSV são lidos com o módulo csv e encoding utf-8.\f"
                + "Guia de Python · Marina\n3\nDatas e horas ficam com o módulo datetime.";
        List<PassageFinder.Passage> chunks = PassageFinder.chunks(pdf, true);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allMatch(c -> !c.text().contains("Marina"));
        assertThat(chunks.get(0).text()).startsWith("Instalação do Python");
    }

    @Test
    void trechosLongosSaoDivididosEDocxNaoTemPagina() {
        String longText = "Frase número um com bastante conteúdo para encher o trecho. ".repeat(40);
        List<PassageFinder.Passage> chunks = PassageFinder.chunks(longText, true);
        assertThat(chunks).hasSizeGreaterThan(2).allMatch(c -> c.text().length() <= PassageFinder.MAX_CHUNK);
        List<PassageFinder.Passage> docx = PassageFinder.chunks("Título\nPrimeiro parágrafo sobre férias e benefícios do time.\nSegundo parágrafo.", false);
        assertThat(docx).isNotEmpty().allMatch(c -> c.page() == null);
    }

    @Test
    void respostaLocalTrazAsFrasesMaisRelevantes() {
        PassageFinder.Passage passage = new PassageFinder.Passage("O Lume organiza documentos. Ele gera capas automáticas para PDFs. "
                + "As descrições são escritas por IA. O leitor guarda a página onde você parou.", 1, 1);
        String answer = PassageFinder.bestSentences(passage, "Como o Lume gera as capas?");
        assertThat(answer).contains("gera capas automáticas");
    }

    @Test
    void comProvedorExternoOModeloRecebeOsTrechosNumerados() {
        StringBuilder sent = new StringBuilder();
        AiService ai = new AiService((u, h, b, t) -> {
            sent.append(b);
            return new HttpTransport.Reply(200, io.github.ravelroot.lume.core.Json.write(java.util.Map.of("content",
                    List.of(java.util.Map.of("type", "text", "text", "Use um ambiente virtual por projeto [1].")))));
        });
        AiConfig cfg = AiConfig.normalize(java.util.Map.of("provider", "anthropic", "api_key", "sk-teste"));
        DocumentFacts doc = new DocumentFacts("Guia", "", "pdf", 3, List.of());
        AiService.QaAnswer answer = ai.answerQuestion(doc, PDF, "Resumo.", "Como criar um ambiente virtual?", cfg);
        assertThat(answer.mode()).isEqualTo("anthropic");
        assertThat(answer.answer()).contains("[1]");
        assertThat(sent.toString()).contains("[1] (pág. 1)").contains("Pergunta: Como criar um ambiente virtual?");

        AiService down = new AiService((u, h, b, t) -> {
            throw new java.net.ConnectException("recusada");
        });
        AiService.QaAnswer local = down.answerQuestion(doc, PDF, "Resumo.", "Como criar um ambiente virtual?", cfg);
        assertThat(local.mode()).isEqualTo("local");
        assertThat(local.warning()).contains("falhou");
        assertThat(local.passages()).isNotEmpty();
    }
}
