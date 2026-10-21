package io.github.ravelroot.lume.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * O banco local: um arquivo só (lume.db, SQLite com FTS5) e uma conexão só, usada por um fio de cada vez.
 *
 * <p>Num aplicativo de mesa isso é mais simples e mais rápido do que um pool: o SQLite grava um comando por vez de
 * qualquer jeito, e a trava deixa as transações da API e do pipeline sem se misturar. As consultas passam pelo
 * {@link JdbcTemplate} do Spring.</p>
 */
public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Path file;
    private final Connection connection;
    private final JdbcTemplate jdbc;
    private final ReentrantLock lock = new ReentrantLock();
    private int depth;

    private Database(Path file, Connection connection) {
        this.file = file;
        this.connection = connection;
        this.jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    /** Abre (ou cria) o banco, liga WAL e chaves estrangeiras e aplica as migrações que faltarem. */
    public static Database open(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            Database db = new Database(file, connection);
            db.execute("PRAGMA journal_mode = WAL");
            db.execute("PRAGMA synchronous = NORMAL");
            db.execute("PRAGMA foreign_keys = ON");
            db.execute("PRAGMA busy_timeout = 5000");
            db.migrate();
            return db;
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("Não foi possível abrir o banco " + file + ": " + e.getMessage(), e);
        }
    }

    public Path file() {
        return file;
    }

    public int schemaVersion() {
        return one("PRAGMA user_version").integer("user_version", 0);
    }

    private void migrate() {
        int version = schemaVersion();
        if (version > Migrations.ALL.size()) {
            throw new IllegalStateException("O banco foi criado por uma versão mais nova do Lume (esquema " + version
                    + "). Atualize o aplicativo.");
        }
        while (version < Migrations.ALL.size()) {
            final int next = version + 1;
            final String script = Migrations.ALL.get(version);
            transaction(() -> {
                for (String statement : Migrations.statements(script)) {
                    jdbc.execute(statement);
                }
                jdbc.execute("PRAGMA user_version = " + next);
            });
            log.info("Banco atualizado para o esquema {}", next);
            version = next;
        }
    }

    /* ---------- Consultas ---------- */

    public List<Row> query(String sql, Object... args) {
        lock.lock();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, normalize(args));
            List<Row> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                out.add(new Row(row));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** A primeira linha, ou null. */
    public Row one(String sql, Object... args) {
        List<Row> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Um número (count, sum, max...); 0 quando vem NULL. */
    public long scalar(String sql, Object... args) {
        Row row = one(sql, args);
        if (row == null || row.isEmpty()) {
            return 0;
        }
        Object value = row.values().iterator().next();
        return value instanceof Number n ? n.longValue() : 0;
    }

    /** INSERT, UPDATE ou DELETE; devolve quantas linhas mudaram. */
    public int update(String sql, Object... args) {
        lock.lock();
        try {
            return jdbc.update(sql, normalize(args));
        } finally {
            lock.unlock();
        }
    }

    /** INSERT que devolve o rowid da linha criada. */
    public long insert(String sql, Object... args) {
        lock.lock();
        try {
            jdbc.update(sql, normalize(args));
            Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            return id == null ? 0 : id;
        } finally {
            lock.unlock();
        }
    }

    public void execute(String sql) {
        lock.lock();
        try {
            jdbc.execute(sql);
        } finally {
            lock.unlock();
        }
    }

    /* ---------- Transações ---------- */

    /** Executa {@code work} numa transação; transações aninhadas viram savepoints. */
    public <T> T transaction(Supplier<T> work) {
        lock.lock();
        try {
            String savepoint = "sp" + depth;
            jdbc.execute(depth == 0 ? "BEGIN IMMEDIATE" : "SAVEPOINT " + savepoint);
            depth++;
            try {
                T result = work.get();
                depth--;
                jdbc.execute(depth == 0 ? "COMMIT" : "RELEASE " + savepoint);
                return result;
            } catch (RuntimeException | Error e) {
                depth--;
                if (depth == 0) {
                    jdbc.execute("ROLLBACK");
                } else {
                    jdbc.execute("ROLLBACK TO " + savepoint);
                    jdbc.execute("RELEASE " + savepoint);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    public void transaction(Runnable work) {
        transaction(() -> {
            work.run();
            return null;
        });
    }

    /** Fecha gravando o WAL no arquivo principal (a pasta de dados fica pronta para cópia de segurança). */
    @Override
    public void close() {
        lock.lock();
        try {
            if (connection.isClosed()) {
                return;
            }
            try {
                jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (RuntimeException e) {
                log.debug("checkpoint ao fechar: {}", e.getMessage());
            }
            connection.close();
        } catch (SQLException e) {
            log.warn("Erro ao fechar o banco: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Booleanos viram 0/1, como o SQLite guarda. */
    private static Object[] normalize(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof Boolean b ? (b ? 1 : 0) : args[i];
        }
        return out;
    }
}
