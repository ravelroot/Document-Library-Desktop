package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Respostas de arquivo com Range (o pdf.js baixa só os trechos que precisa) e ETag. */
public final class FileResponder {

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private FileResponder() {
    }

    /** encodeURIComponent do JavaScript (para o filename* do Content-Disposition). */
    static String encodeUriComponent(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0;
            if (keep) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    static String contentDisposition(String filename, boolean attachment) {
        String ascii = filename.replaceAll("[^\\x20-\\x7e]", "").replace("\"", "");
        if (ascii.isEmpty()) {
            ascii = "arquivo";
        }
        return (attachment ? "attachment" : "inline") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encodeUriComponent(filename);
    }

    public static void send(HttpServletRequest request, HttpServletResponse response, Path file, String contentType, String filename,
                            boolean attachment, String cacheControl) throws IOException {
        BasicFileAttributes stat;
        try {
            stat = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            response.setStatus(404);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"not_found\",\"message\":\"Arquivo não encontrado.\"}");
            return;
        }
        long size = stat.size();
        String etag = "\"" + stat.lastModifiedTime().toMillis() + "-" + size + "\"";
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", cacheControl);
        if (filename != null) {
            response.setHeader("Content-Disposition", contentDisposition(filename, attachment));
        }
        if (etag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(304);
            return;
        }
        long start = 0;
        long end = size - 1;
        int status = 200;
        String range = request.getHeader("Range");
        Matcher match = range == null ? null : RANGE.matcher(range.strip());
        String ifRange = request.getHeader("If-Range");
        if (match != null && match.matches() && size > 0 && (ifRange == null || ifRange.equals(etag))) {
            String first = match.group(1);
            String last = match.group(2);
            if (first.isEmpty() && !last.isEmpty()) {
                start = Math.max(size - Long.parseLong(last), 0);
            } else if (!first.isEmpty()) {
                start = Long.parseLong(first);
                end = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            }
            if (start > end || start >= size) {
                response.setHeader("Content-Range", "bytes */" + size);
                response.setStatus(416);
                return;
            }
            status = 206;
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        long length = Math.max(0, end - start + 1);
        response.setStatus(status);
        response.setContentLengthLong(length);
        if ("HEAD".equalsIgnoreCase(request.getMethod()) || length == 0) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(start);
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[256 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }
}
