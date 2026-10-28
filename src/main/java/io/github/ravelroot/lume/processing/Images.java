package io.github.ravelroot.lume.processing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** JPEG das capas, cor média e detecção de página em branco. */
public final class Images {

    private Images() {
    }

    public static byte[] jpeg(BufferedImage image, float quality) {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Página praticamente lisa (em branco ou de uma cor só): desvio padrão da luminância abaixo de 2,5. */
    public static boolean isNearlyUniform(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int stepX = Math.max(1, width / 48);
        int stepY = Math.max(1, height / 48);
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int rgb = image.getRGB(x, y);
                double lum = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                sum += lum;
                squares += lum * lum;
                count++;
            }
        }
        if (count == 0) {
            return true;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean)) < 2.5;
    }

    /** Cor média em #rrggbb (cerca de 4000 amostras). */
    public static String averageColor(BufferedImage image) {
        int width = image.getWidth();
        int total = width * image.getHeight();
        int step = Math.max(1, total / 4000);
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int i = 0; i < total; i += step) {
            int rgb = image.getRGB(i % width, i / width);
            r += (rgb >> 16) & 0xff;
            g += (rgb >> 8) & 0xff;
            b += rgb & 0xff;
            n++;
        }
        long count = Math.max(n, 1);
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round((double) r / count), Math.round((double) g / count),
                Math.round((double) b / count));
    }
}
