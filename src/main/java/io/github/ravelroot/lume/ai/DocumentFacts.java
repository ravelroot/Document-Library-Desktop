package io.github.ravelroot.lume.ai;

import java.util.List;

/** O que o pedido de descrição precisa saber do documento, além do texto. */
public record DocumentFacts(String title, String author, String kind, Integer pageCount, List<String> outline) {
}
