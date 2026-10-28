package io.github.ravelroot.lume.db;

import java.util.List;

/**
 * Esquema do lume.db. As migrações são numeradas e a versão aplicada fica em PRAGMA user_version, então um banco
 * criado pela versão em Electron (esquema 2) abre direto nesta versão em Java, sem conversão.
 */
public final class Migrations {

    private Migrations() {
    }

    /** 1: esquema inicial. */
    static final String V1 = """
            CREATE TABLE documents (
              id               TEXT PRIMARY KEY,
              title            TEXT NOT NULL,
              title_sort       TEXT NOT NULL DEFAULT '',
              author           TEXT NOT NULL DEFAULT '',
              author_sort      TEXT NOT NULL DEFAULT '',
              description      TEXT NOT NULL DEFAULT '',
              metadata_locked  INTEGER NOT NULL DEFAULT 0 CHECK (metadata_locked IN (0, 1)),
              summary          TEXT NOT NULL DEFAULT '',
              summary_source   TEXT NOT NULL DEFAULT '',
              summary_model    TEXT NOT NULL DEFAULT '',
              summary_at       TEXT,
              kind             TEXT NOT NULL CHECK (kind IN ('pdf', 'docx')),
              original_name    TEXT NOT NULL,
              source_path      TEXT NOT NULL DEFAULT '',
              size_bytes       INTEGER NOT NULL CHECK (size_bytes > 0),
              sha256           TEXT NOT NULL UNIQUE,
              page_count       INTEGER,
              word_count       INTEGER,
              language         TEXT NOT NULL DEFAULT '',
              cover_width      INTEGER,
              cover_height     INTEGER,
              cover_color      TEXT NOT NULL DEFAULT '',
              cover_generated  INTEGER NOT NULL DEFAULT 0 CHECK (cover_generated IN (0, 1)),
              cover_version    INTEGER NOT NULL DEFAULT 0,
              has_reader_html  INTEGER NOT NULL DEFAULT 0 CHECK (has_reader_html IN (0, 1)),
              outline          TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(outline)),
              metadata         TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(metadata)),
              status           TEXT NOT NULL DEFAULT 'queued'
                               CHECK (status IN ('queued', 'extracting', 'describing', 'ready', 'failed')),
              status_detail    TEXT NOT NULL DEFAULT '',
              attempts         INTEGER NOT NULL DEFAULT 0,
              created_at       TEXT NOT NULL,
              updated_at       TEXT NOT NULL,
              processed_at     TEXT
            );
            CREATE INDEX documents_created ON documents (created_at DESC);
            CREATE INDEX documents_status ON documents (status);
            CREATE INDEX documents_title_sort ON documents (title_sort);

            CREATE TABLE document_content (
              document_id     TEXT PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
              text            TEXT NOT NULL DEFAULT '',
              text_truncated  INTEGER NOT NULL DEFAULT 0,
              updated_at      TEXT NOT NULL
            );

            CREATE VIRTUAL TABLE documents_fts USING fts5 (
              document_id UNINDEXED,
              title,
              author,
              extra,
              body,
              tokenize = 'unicode61 remove_diacritics 2'
            );

            CREATE TABLE tags (
              id          INTEGER PRIMARY KEY,
              name        TEXT NOT NULL,
              slug        TEXT NOT NULL UNIQUE,
              created_at  TEXT NOT NULL
            );

            CREATE TABLE document_tags (
              document_id  TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
              tag_id       INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
              source       TEXT NOT NULL DEFAULT 'usuario' CHECK (source IN ('ia', 'usuario')),
              created_at   TEXT NOT NULL,
              PRIMARY KEY (document_id, tag_id)
            );
            CREATE INDEX document_tags_tag ON document_tags (tag_id);

            CREATE TABLE collections (
              id           TEXT PRIMARY KEY,
              name         TEXT NOT NULL CHECK (length(trim(name)) > 0),
              description  TEXT NOT NULL DEFAULT '',
              created_at   TEXT NOT NULL,
              updated_at   TEXT NOT NULL
            );
            CREATE UNIQUE INDEX collections_name ON collections (lower(name));

            CREATE TABLE collection_items (
              collection_id  TEXT NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
              document_id    TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
              position       INTEGER NOT NULL DEFAULT 0,
              added_at       TEXT NOT NULL,
              PRIMARY KEY (collection_id, document_id)
            );
            CREATE INDEX collection_items_document ON collection_items (document_id);

            CREATE TABLE reading_states (
              document_id     TEXT PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
              shelf           TEXT NOT NULL DEFAULT '' CHECK (shelf IN ('', 'para_ler', 'lendo', 'lido')),
              page            INTEGER NOT NULL DEFAULT 1 CHECK (page >= 1),
              progress        REAL NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 1),
              location        TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(location)),
              favorite        INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
              started_at      TEXT,
              finished_at     TEXT,
              last_opened_at  TEXT,
              updated_at      TEXT NOT NULL
            );
            CREATE INDEX reading_states_recent ON reading_states (last_opened_at DESC);

            CREATE TABLE annotations (
              id           TEXT PRIMARY KEY,
              document_id  TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
              kind         TEXT NOT NULL CHECK (kind IN ('note', 'highlight', 'bookmark')),
              page         INTEGER,
              anchor       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(anchor)),
              quote        TEXT NOT NULL DEFAULT '',
              note         TEXT NOT NULL DEFAULT '',
              color        TEXT NOT NULL DEFAULT 'salvia' CHECK (color IN ('salvia', 'rose', 'ardosia', 'camurca')),
              created_at   TEXT NOT NULL,
              updated_at   TEXT NOT NULL
            );
            CREATE INDEX annotations_document ON annotations (document_id, page);

            CREATE TABLE jobs (
              id           INTEGER PRIMARY KEY,
              document_id  TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
              stage        TEXT NOT NULL CHECK (stage IN ('extract', 'describe')),
              status       TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'succeeded', 'retrying', 'failed')),
              attempt      INTEGER NOT NULL DEFAULT 1,
              started_at   TEXT NOT NULL,
              finished_at  TEXT,
              duration_ms  INTEGER,
              error        TEXT NOT NULL DEFAULT '',
              details      TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(details))
            );
            CREATE INDEX jobs_started ON jobs (started_at DESC);
            CREATE INDEX jobs_document ON jobs (document_id);

            CREATE TABLE settings (
              key         TEXT PRIMARY KEY,
              value       TEXT NOT NULL CHECK (json_valid(value)),
              updated_at  TEXT NOT NULL
            );
            """;

    /** 2: as cores das anotações passam a seguir a paleta do Lume web (Sálvia, Rosé, Ardósia, Camurça). */
    static final String V2 = """
            CREATE TABLE annotations_v2 (
              id           TEXT PRIMARY KEY,
              document_id  TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
              kind         TEXT NOT NULL CHECK (kind IN ('note', 'highlight', 'bookmark')),
              page         INTEGER,
              anchor       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(anchor)),
              quote        TEXT NOT NULL DEFAULT '',
              note         TEXT NOT NULL DEFAULT '',
              color        TEXT NOT NULL DEFAULT 'salvia' CHECK (color IN ('salvia', 'rose', 'ardosia', 'camurca')),
              created_at   TEXT NOT NULL,
              updated_at   TEXT NOT NULL
            );
            INSERT INTO annotations_v2
              SELECT id, document_id, kind, page, anchor, quote, note,
                     CASE color WHEN 'palha' THEN 'salvia' WHEN 'baunilha' THEN 'camurca' WHEN 'fume' THEN 'ardosia'
                                WHEN 'grafite' THEN 'rose' ELSE color END,
                     created_at, updated_at
                FROM annotations;
            DROP TABLE annotations;
            ALTER TABLE annotations_v2 RENAME TO annotations;
            CREATE INDEX annotations_document ON annotations (document_id, page);
            """;

    public static final List<String> ALL = List.of(V1, V2);

    /** Separa um script em comandos (os scripts acima não têm ponto e vírgula dentro de textos). */
    public static List<String> statements(String script) {
        return java.util.Arrays.stream(script.split(";\\s*(?:\\n|$)"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
