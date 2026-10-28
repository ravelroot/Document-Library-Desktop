package io.github.ravelroot.lume.processing;

/**
 * Pedido de capa tipográfica.
 *
 * @param title      título do documento
 * @param author     autor (vazio para não mostrar)
 * @param label      rótulo no topo (PDF ou DOCX)
 * @param background tom da paleta
 * @param ink        cor do texto
 * @param width      largura em pixels (a altura é 4:3)
 */
public record CoverRequest(String title, String author, String label, String background, String ink, int width) {
}
