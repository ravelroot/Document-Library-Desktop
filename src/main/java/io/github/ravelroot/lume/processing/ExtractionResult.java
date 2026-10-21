package io.github.ravelroot.lume.processing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que a etapa de leitura descobriu num arquivo (PDF ou DOCX): metadados, sumário, texto, capa e, no DOCX, o HTML
 * de leitura com as imagens.
 */
public class ExtractionResult {

    private final String kind;
    private Integer pageCount;
    private String title;
    private String author;
    private String subject;
    private String keywords;
    private String creator;
    private String producer;
    private String createdAt;
    private String modifiedAt;
    private String language = "";
    private boolean encrypted;
    private List<OutlineItem> outline = new ArrayList<>();
    private String text = "";
    private boolean textTruncated;
    private Integer charCount;
    private int wordCount;
    private Double pageWidth;
    private Double pageHeight;
    private Cover cover;
    private String html;
    private List<String> media = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private long elapsedMs;

    public ExtractionResult(String kind) {
        this.kind = kind;
    }

    /** Registra um aviso (sem repetir a mesma mensagem). */
    public void warn(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
        }
    }

    /** Os campos que vão para a coluna metadata (JSON), sem os vazios. */
    public Map<String, Object> metadata() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("subject", subject);
        all.put("keywords", keywords);
        all.put("created_at", createdAt);
        all.put("modified_at", modifiedAt);
        all.put("producer", producer);
        all.put("creator", creator);
        all.put("page_width", pageWidth);
        all.put("page_height", pageHeight);
        all.put("encrypted", encrypted);
        all.put("warnings", warnings);
        all.put("elapsed_ms", elapsedMs);
        all.put("char_count", charCount);
        all.put("text_truncated", textTruncated);
        Map<String, Object> out = new LinkedHashMap<>();
        all.forEach((key, value) -> {
            boolean empty = value == null || "".equals(value) || (value instanceof List<?> list && list.isEmpty());
            if (!empty) {
                out.put(key, value instanceof List<?> list ? List.copyOf(list) : value);
            }
        });
        return out;
    }

    public String kind() {
        return kind;
    }

    public Integer pageCount() {
        return pageCount;
    }

    public ExtractionResult pageCount(Integer value) {
        this.pageCount = value;
        return this;
    }

    public String title() {
        return title;
    }

    public ExtractionResult title(String value) {
        this.title = value;
        return this;
    }

    public String author() {
        return author;
    }

    public ExtractionResult author(String value) {
        this.author = value;
        return this;
    }

    public String subject() {
        return subject;
    }

    public ExtractionResult subject(String value) {
        this.subject = value;
        return this;
    }

    public String keywords() {
        return keywords;
    }

    public ExtractionResult keywords(String value) {
        this.keywords = value;
        return this;
    }

    public String creator() {
        return creator;
    }

    public ExtractionResult creator(String value) {
        this.creator = value;
        return this;
    }

    public String producer() {
        return producer;
    }

    public ExtractionResult producer(String value) {
        this.producer = value;
        return this;
    }

    public String createdAt() {
        return createdAt;
    }

    public ExtractionResult createdAt(String value) {
        this.createdAt = value;
        return this;
    }

    public String modifiedAt() {
        return modifiedAt;
    }

    public ExtractionResult modifiedAt(String value) {
        this.modifiedAt = value;
        return this;
    }

    public String language() {
        return language;
    }

    public ExtractionResult language(String value) {
        this.language = value == null ? "" : value;
        return this;
    }

    public boolean encrypted() {
        return encrypted;
    }

    public ExtractionResult encrypted(boolean value) {
        this.encrypted = value;
        return this;
    }

    public List<OutlineItem> outline() {
        return outline;
    }

    public ExtractionResult outline(List<OutlineItem> value) {
        this.outline = new ArrayList<>(value);
        return this;
    }

    public String text() {
        return text;
    }

    public ExtractionResult text(String value) {
        this.text = value == null ? "" : value;
        return this;
    }

    public boolean textTruncated() {
        return textTruncated;
    }

    public ExtractionResult textTruncated(boolean value) {
        this.textTruncated = value;
        return this;
    }

    public Integer charCount() {
        return charCount;
    }

    public ExtractionResult charCount(Integer value) {
        this.charCount = value;
        return this;
    }

    public int wordCount() {
        return wordCount;
    }

    public ExtractionResult wordCount(int value) {
        this.wordCount = value;
        return this;
    }

    public Double pageWidth() {
        return pageWidth;
    }

    public Double pageHeight() {
        return pageHeight;
    }

    public ExtractionResult pageSize(Double width, Double height) {
        this.pageWidth = width;
        this.pageHeight = height;
        return this;
    }

    public Cover cover() {
        return cover;
    }

    public ExtractionResult cover(Cover value) {
        this.cover = value;
        return this;
    }

    /** HTML de leitura (só DOCX); null no PDF, que é lido pelo pdf.js. */
    public String html() {
        return html;
    }

    public ExtractionResult html(String value) {
        this.html = value;
        return this;
    }

    public List<String> media() {
        return media;
    }

    public ExtractionResult media(List<String> value) {
        this.media = new ArrayList<>(value);
        return this;
    }

    public List<String> warnings() {
        return warnings;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public ExtractionResult elapsedMs(long value) {
        this.elapsedMs = value;
        return this;
    }
}
