package io.github.ravelroot.lume.ai;

import java.util.List;

/**
 * Resultado da etapa de descrição.
 *
 * @param summary  duas ou três frases sobre o documento
 * @param tags     etiquetas sugeridas (em minúsculas)
 * @param source   provedor que escreveu: local, anthropic ou openai
 * @param model    modelo usado (vazio no resumo local)
 * @param language idioma provável do texto (pt, en, es ou vazio)
 * @param warnings o que deu errado antes de cair para o resumo local
 */
public record Description(String summary, List<String> tags, String source, String model, String language, List<String> warnings) {
}
