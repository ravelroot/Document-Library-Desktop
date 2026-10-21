package io.github.ravelroot.lume.docx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.ravelroot.lume.core.ProcessingRejected;

/**
 * O pacote OOXML (um zip). As partes são descompactadas sob demanda e com limites de tamanho, então um "zip bomb" é
 * recusado antes de ocupar memória ou disco.
 */
public final class DocxPackage implements AutoCloseable {

    static final int MAX_ENTRIES = 10_000;
    private static final long MAX_XML_BYTES = 64L * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};
    private static final byte[] OLE_MAGIC = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0};
    private static final byte[] ENCRYPTION_INFO = "EncryptionInfo".getBytes(StandardCharsets.UTF_16LE);

    private final ZipFile zip;
    private final Map<String, Long> entries;
    private long totalRead;

    private DocxPackage(ZipFile zip, Map<String, Long> entries) {
        this.zip = zip;
        this.entries = entries;
    }

    public static DocxPackage open(Path file) {
        byte[] head = readHead(file, 8);
        if (startsWith(head, OLE_MAGIC)) {
            // DOCX cifrado pelo Word vira um arquivo OLE com um fluxo "EncryptionInfo" dentro.
            if (indexOf(readHead(file, 32 * 1024 * 1024), ENCRYPTION_INFO) >= 0) {
                throw new ProcessingRejected("O DOCX está protegido por senha e não pode ser aberto.", "encrypted");
            }
            throw new ProcessingRejected("Arquivos .doc do Word 97-2003 não são suportados. Abra no Word e salve como .docx.",
                    "unsupported");
        }
        if (!startsWith(head, ZIP_MAGIC)) {
            throw new ProcessingRejected("O arquivo não é um DOCX válido.");
        }
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new ProcessingRejected("O DOCX está corrompido (zip inválido).");
        }
        Map<String, Long> entries = new LinkedHashMap<>();
        try {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw new ProcessingRejected("O DOCX tem arquivos internos demais para ser processado com segurança.");
                }
                ZipEntry entry = all.nextElement();
                entries.put(entry.getName(), entry.getSize());
            }
        } catch (ProcessingRejected e) {
            closeQuietly(zip);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(zip);
            throw new ProcessingRejected("O DOCX está corrompido (zip inválido).");
        }
        DocxPackage pkg = new DocxPackage(zip, entries);
        if (!pkg.has("[Content_Types].xml")) {
            pkg.close();
            throw new ProcessingRejected("O arquivo não é um documento do Word (falta [Content_Types].xml).");
        }
        return pkg;
    }

    /** Lista os nomes internos de um zip (usado na importação para saber se é mesmo um DOCX). */
    public static List<String> entryNames(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements() && names.size() < MAX_ENTRIES) {
                names.add(all.nextElement().getName());
            }
            return names;
        }
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    byte[] read(String name, long maxBytes) {
        Long declared = entries.get(name);
        if (declared == null) {
            throw new ProcessingRejected("Parte ausente no DOCX: " + name);
        }
        String tooBig = "A parte " + name + " do DOCX é grande demais para ser lida com segurança.";
        if (declared > maxBytes) {
            throw new ProcessingRejected(tooBig);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(declared, 1 << 20)));
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new ProcessingRejected(tooBig);
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ProcessingRejected rejected) {
                throw rejected;
            }
            throw new ProcessingRejected("A parte " + name + " do DOCX está corrompida.");
        }
        totalRead += out.size();
        if (totalRead > MAX_TOTAL_BYTES) {
            throw new ProcessingRejected("O DOCX descompactado passa do limite de segurança.");
        }
        return out.toByteArray();
    }

    /** Lê uma parte XML e devolve a árvore. */
    public XmlNode xml(String name) {
        byte[] data = read(name, MAX_XML_BYTES);
        try {
            return XmlParser.parse(data);
        } catch (XmlParser.XmlException e) {
            throw new ProcessingRejected("O DOCX contém XML inválido (" + name + "): " + e.getMessage());
        }
    }

    public byte[] media(String name) {
        return read(name, MAX_MEDIA_BYTES);
    }

    /**
     * Resolve o alvo de uma relação (relativo à pasta da parte de origem) para o nome dentro do zip. Devolve null se o
     * caminho tentar sair do pacote.
     */
    public static String resolve(String sourcePart, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String base = sourcePart.contains("/") ? sourcePart.substring(0, sourcePart.lastIndexOf('/') + 1) : "";
        String combined = target.startsWith("/") ? target.substring(1) : base + target;
        List<String> parts = new ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException ignored) {
            // nada a fazer
        }
    }

    private static byte[] readHead(Path file, int max) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler o arquivo: " + e.getMessage(), e);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
