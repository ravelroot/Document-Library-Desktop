package io.github.ravelroot.lume.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ravelroot.lume.processing.Palette;

class DatabaseTest {

    @TempDir
    Path dir;

    @Test
    void migracoesCriamOEsquemaEAVersao() {
        try (Database db = Database.open(dir.resolve("lume.db"))) {
            assertThat(db.schemaVersion()).isEqualTo(Migrations.ALL.size());
            List<String> tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").stream().map(r -> r.str("name")).toList();
            assertThat(tables).contains("annotations", "collection_items", "collections", "document_content", "document_tags", "documents",
                    "documents_fts", "jobs", "reading_states", "settings", "tags");
            assertThat(db.one("PRAGMA journal_mode").str("journal_mode")).isEqualTo("wal");
            assertThat(db.one("PRAGMA foreign_keys").integer("foreign_keys")).isEqualTo(1);
        }
        try (Database again = Database.open(dir.resolve("lume.db"))) {
            assertThat(again.schemaVersion()).isEqualTo(Migrations.ALL.size());
        }
    }

    @Test
    void bancosDaVersaoAnteriorRecebemAsCoresNovasDasAnotacoes() {
        try (Database old = Database.open(dir.resolve("velho.db"))) {
            old.execute("PRAGMA foreign_keys = OFF");
            old.execute("DROP TABLE annotations");
            old.execute("CREATE TABLE annotations (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, kind TEXT NOT NULL, page INTEGER, "
                    + "anchor TEXT NOT NULL DEFAULT '{}', quote TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', "
                    + "color TEXT NOT NULL DEFAULT 'palha', created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            old.update("INSERT INTO documents (id, title, kind, original_name, size_bytes, sha256, created_at, updated_at) "
                    + "VALUES ('d1', 'Doc', 'pdf', 'a.pdf', 10, 'x', 't', 't')");
            String[] colors = {"palha", "baunilha", "fume", "grafite"};
            for (int i = 0; i < colors.length; i++) {
                old.update("INSERT INTO annotations (id, document_id, kind, color, created_at, updated_at) VALUES (?, 'd1', 'highlight', ?, 't', 't')",
                        "a" + i, colors[i]);
            }
            old.execute("PRAGMA user_version = 1");
        }
        try (Database db = Database.open(dir.resolve("velho.db"))) {
            assertThat(db.query("SELECT color FROM annotations ORDER BY id").stream().map(r -> r.str("color")).toList())
                    .containsExactly("salvia", "camurca", "ardosia", "rose");
            assertThatThrownBy(() -> db.update("UPDATE annotations SET color = 'palha'")).hasMessageContaining("CHECK");
        }
    }

    @Test
    void restricoesSeguramDadosInvalidosETransacaoDesfazTudo() {
        try (Database db = Database.open(dir.resolve("lume.db"))) {
            String insert = "INSERT INTO documents (id, title, kind, original_name, size_bytes, sha256, status, created_at, updated_at) "
                    + "VALUES (?, 't', ?, 'a', ?, ?, ?, 'n', 'n')";
            db.update(insert, "a", "pdf", 10, "1", "queued");
            assertThatThrownBy(() -> db.update(insert, "b", "pdf", 10, "1", "queued")).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> db.update(insert, "c", "doc", 10, "2", "queued")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> db.update(insert, "d", "pdf", 0, "3", "queued")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> db.update(insert, "e", "pdf", 10, "4", "pronto")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> db.update("INSERT INTO reading_states (document_id, progress, updated_at) VALUES ('a', 1.5, 'n')"))
                    .hasMessageContaining("CHECK");
            assertThatThrownBy(() -> db.update("INSERT INTO reading_states (document_id, updated_at) VALUES ('nao-existe', 'n')"))
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> db.transaction(() -> {
                db.update(insert, "f", "pdf", 10, "5", "queued");
                db.update(insert, "g", "pdf", 10, "5", "queued");
            }));
            assertThat(db.scalar("SELECT count(*) FROM documents WHERE id = 'f'")).isZero();
        }
    }

    @Test
    void paletaTemCorEstavelEContraste() {
        assertThat(Palette.TONES).hasSize(6);
        assertThat(Palette.toneFor("Guia prático")).isEqualTo(Palette.toneFor("  guia prático "));
        for (Palette.Tone tone : Palette.TONES) {
            assertThat(Palette.contrast(tone.hex(), tone.ink())).isGreaterThanOrEqualTo(3.5);
        }
    }
}
