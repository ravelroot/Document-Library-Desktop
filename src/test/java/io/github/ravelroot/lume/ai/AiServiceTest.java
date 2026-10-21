package io.github.ravelroot.lume.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.ravelroot.lume.core.Json;

class AiServiceTest {

    static final String TEXT = String.join("\n",
            "Relatório anual da cooperativa", "Página 1", "\f",
            "Cooperativa Vale do Ipê · Relatório anual",
            "A cooperativa ampliou a produção de café especial em vinte por cento durante o ano, com apoio técnico às famílias associadas.",
            "O investimento em secadores solares reduziu o custo de energia e melhorou a qualidade dos grãos vendidos para exportação.",
            "Página 2", "\f",
            "Cooperativa Vale do Ipê · Relatório anual",
            "Para o próximo ano, a cooperativa planeja abrir uma loja própria e oferecer cursos de torra para os associados mais jovens.",
            "A diretoria agradece às famílias pela confiança e pelo trabalho coletivo que sustenta a cooperativa desde a fundação.",
            "Página 3");

    static final DocumentFacts DOC = new DocumentFacts("Relatório anual", "", "pdf", 3, List.of("Produção"));

    @Test
    void resumoLocalEscolheFrasesDoCorpoEIgnoraCabecalhosRepetidos() {
        String summary = LocalSummarizer.summarize(TEXT, "Relatório anual da cooperativa", List.of(), false);
        assertThat(summary.length()).isBetween(61, 360);
        assertThat(summary).doesNotContain("Página").doesNotContain("Vale do Ipê · Relatório").containsIgnoringCase("cooperativa");
    }

    @Test
    void palavrasChaveEIdioma() {
        List<String> tags = LocalSummarizer.keywords(TEXT, "Relatório anual da cooperativa", List.of(), "Diretoria", false);
        assertThat(tags).contains("cooperativa").doesNotHaveDuplicates();
        assertThat(LocalSummarizer.guessLanguage(TEXT)).isEqualTo("pt");
        assertThat(LocalSummarizer.guessLanguage("The quick brown fox jumps over the lazy dog and the cat is in the house with the children of the town."))
                .isEqualTo("en");
        assertThat(LocalSummarizer.guessLanguage("12345 678")).isEmpty();
    }

    @Test
    void frasesAbreviacoesECorteComReticencias() {
        assertThat(LocalSummarizer.splitSentences("O Dr. Silva chegou cedo. Ele trouxe o relatório."))
                .containsExactly("O Dr. Silva chegou cedo.", "Ele trouxe o relatório.");
        assertThat(LocalSummarizer.shorten("uma frase bem comprida para cortar", 14)).isEqualTo("uma frase bem…");
        assertThat(LocalSummarizer.cleanText("linha que continua com hifeni-\nzação no meio da palavra até o fim da frase."))
                .containsExactly("linha que continua com hifenização no meio da palavra até o fim da frase.");
    }

    @Test
    void provedorAnthropicMontaAChamadaELeAResposta() {
        AtomicReference<URI> url = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AiService ai = new AiService((u, h, b, t) -> {
            url.set(u);
            headers.set(h);
            body.set(b);
            return new HttpTransport.Reply(200, Json.write(Map.of("content", List.of(Map.of("type", "text",
                    "text", "{\"descricao\": \"Relatório da cooperativa com resultados do ano e planos para os associados.\", \"tags\": [\"Café\", \"cooperativa\"]}")))));
        });
        Description r = ai.describe(DOC, TEXT.repeat(3), AiConfig.normalize(Map.of("provider", "anthropic", "api_key", "k")));
        assertThat(r.source()).isEqualTo("anthropic");
        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.tags()).containsExactly("café", "cooperativa");
        assertThat(url.get()).hasToString("https://api.anthropic.com/v1/messages");
        assertThat(headers.get()).containsEntry("x-api-key", "k").containsEntry("anthropic-version", "2023-06-01");
        assertThat(body.get()).contains("Sumário: Produção");
    }

    @Test
    void compativelComOpenAiEQuedaParaOResumoLocal() {
        AiService ok = new AiService((u, h, b, t) -> new HttpTransport.Reply(200, Json.write(Map.of("choices", List.of(Map.of("message",
                Map.of("content", "```json\n{\"descricao\": \"Um texto curto e informativo sobre a cooperativa e seus planos.\", \"tags\": [\"a\"]}\n```")))))));
        Description r = ok.describe(DOC, TEXT.repeat(3), AiConfig.normalize(Map.of("provider", "openai", "model", "llama3.1",
                "base_url", "http://localhost:11434/v1/")));
        assertThat(r.source()).isEqualTo("openai");
        assertThat(r.model()).isEqualTo("llama3.1");

        AiService down = new AiService((u, h, b, t) -> {
            throw new ConnectException("Connection refused");
        });
        Description fallback = down.describe(DOC, TEXT.repeat(3), AiConfig.normalize(Map.of("provider", "openai", "model", "x")));
        assertThat(fallback.source()).isEqualTo("local");
        assertThat(fallback.warnings().get(0)).contains("ECONNREFUSED");

        Description shortText = ok.describe(DOC, "pouco texto", AiConfig.normalize(Map.of("provider", "openai", "model", "x")));
        assertThat(shortText.source()).as("texto curto nem vai ao provedor").isEqualTo("local");
    }

    @Test
    void respostasForaDoFormatoViramErroDoProvedor() {
        assertThatThrownBy(() -> AiService.parseAnswer("sem json")).hasMessageContaining("não veio em JSON");
        assertThatThrownBy(() -> AiService.parseAnswer("{\"descricao\": \"curta\"}")).hasMessageContaining("vazia");
        assertThatThrownBy(() -> AiService.parseAnswer("{quebrado}")).hasMessageContaining("JSON");
        assertThat(AiConfig.normalize(Map.of("provider", "openai", "model", "", "base_url", "")).configured()).isFalse();
        assertThat(AiConfig.normalize(Map.of("provider", "desconhecido")).provider()).isEqualTo("local");
    }
}
