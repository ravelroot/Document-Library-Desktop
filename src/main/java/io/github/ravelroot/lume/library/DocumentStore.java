package io.github.ravelroot.lume.library;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.LumeProperties;
import io.github.ravelroot.lume.core.ApiException;
import io.github.ravelroot.lume.db.Database;
import io.github.ravelroot.lume.db.Row;
import io.github.ravelroot.lume.events.EventBus;

/**
 * Onde cada documento fica: a linha na tabela documents e a pasta {@code <dados>/documentos/<id>/} com o original,
 * a capa, o HTML de leitura e as imagens.
 */
@Component
public class DocumentStore {

    /** Arquivos de um documento. */
    public record Files(Path folder, Path file, Path cover, Path reader, Path media) {
    }

    private final Database db;
    private final EventBus events;
    private final Path dataDir;
    private final Path documentsDir;
    private final Path tmpDir;

    public DocumentStore(Database db, EventBus events, LumeProperties properties) {
        this.db = db;
        this.events = events;
        this.dataDir = properties.dataDir().toAbsolutePath().normalize();
        this.documentsDir = dataDir.resolve("documentos");
        this.tmpDir = dataDir.resolve("tmp");
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path documentsDir() {
        return documentsDir;
    }

    public Path tmpDir() {
        return tmpDir;
    }

    public Path folderOf(String id) {
        return documentsDir.resolve(id);
    }

    public Files files(Row row) {
        Path folder = folderOf(row.id());
        return new Files(folder, folder.resolve("original." + row.str("kind")), folder.resolve("capa.jpg"),
                folder.resolve("leitura.html"), folder.resolve("media"));
    }

    public Row row(String id) {
        return db.one("SELECT * FROM documents WHERE id = ?", String.valueOf(id));
    }

    public Row requireRow(String id) {
        Row found = row(id);
        if (found == null) {
            throw ApiException.notFound("Documento não encontrado.");
        }
        return found;
    }

    public void emit(Row doc, String type) {
        events.emit(type, Map.of("id", doc.id(), "status", doc.str("status")));
    }

    public void emitUpdated(String id) {
        Row doc = row(id);
        if (doc != null) {
            emit(doc, "document.updated");
        }
    }
}
