package io.github.ravelroot.lume.docx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import io.github.ravelroot.lume.core.Texts;
import io.github.ravelroot.lume.core.Times;

/** Propriedades do documento (docProps/core.xml e docProps/app.xml). */
public final class DocProperties {

    String title;
    String subject;
    String creator;
    String keywords;
    String description;
    String language;
    String created;
    String modified;
    String application;
    int pages;

    public static DocProperties read(DocxPackage pkg) {
        DocProperties props = new DocProperties();
        if (pkg.has("docProps/core.xml")) {
            XmlNode.walk(pkg.xml("docProps/core.xml"), node -> {
                switch (node.name()) {
                    case "title" -> props.title = Texts.singleLineOrNull(node.text());
                    case "subject" -> props.subject = Texts.singleLineOrNull(node.text());
                    case "creator" -> props.creator = Texts.singleLineOrNull(node.text());
                    case "keywords" -> props.keywords = Texts.singleLineOrNull(node.text());
                    case "description" -> props.description = Texts.singleLineOrNull(node.text());
                    case "language" -> props.language = Texts.singleLineOrNull(node.text());
                    case "created" -> props.created = isoDate(node.text());
                    case "modified" -> props.modified = isoDate(node.text());
                    default -> {
                        // outros campos não interessam
                    }
                }
            });
        }
        if (pkg.has("docProps/app.xml")) {
            XmlNode.walk(pkg.xml("docProps/app.xml"), node -> {
                if (node.name().equals("Pages")) {
                    Integer pages = XmlNode.parseLeadingInt(node.text());
                    props.pages = pages == null ? 0 : pages;
                } else if (node.name().equals("Application")) {
                    props.application = Texts.singleLineOrNull(node.text());
                }
            });
        }
        return props;
    }

    /** W3CDTF (2025-03-01T10:00:00Z) para ISO 8601 em UTC, sem frações; null se inválida. */
    static String isoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        try {
            return Times.isoSeconds(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            // tenta os outros formatos
        }
        try {
            return Times.isoSeconds(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // tenta os outros formatos
        }
        try {
            return Times.isoSeconds(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // tenta os outros formatos
        }
        try {
            return Times.isoSeconds(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
