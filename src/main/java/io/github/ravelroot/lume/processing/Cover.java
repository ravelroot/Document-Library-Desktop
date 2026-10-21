package io.github.ravelroot.lume.processing;

/**
 * Capa pronta, em JPEG.
 *
 * @param bytes     a imagem
 * @param width     largura em pixels
 * @param height    altura em pixels
 * @param color     cor média (capas de PDF) ou tom da paleta (capas tipográficas), em #rrggbb
 * @param generated true quando é capa tipográfica (DOCX ou PDF sem página aproveitável)
 */
public record Cover(byte[] bytes, int width, int height, String color, boolean generated) {

    public Cover withStyle(String newColor, boolean isGenerated) {
        return new Cover(bytes, width, height, newColor, isGenerated);
    }
}
