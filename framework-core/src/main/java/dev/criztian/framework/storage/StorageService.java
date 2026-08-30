package dev.criztian.framework.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.criztian.framework.plugin.Service;
import dev.criztian.framework.scheduler.SchedulerService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sql.DataSource;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pooled SQL storage. SQLite by default (zero-setup file DB with WAL), MariaDB/
 * MySQL for external servers. Drivers and HikariCP arrive through the plugin's
 * generated {@code plugin.yml} {@code libraries:} block — nothing is shaded.
 *
 * <p>Run queries off the game threads via {@link #async} — it hops through the
 * plugin's Folia-safe async scheduler.</p>
 */
public final class StorageService implements Service, AutoCloseable {

    private final HikariDataSource dataSource;
    private final SchedulerService scheduler;

    private StorageService(HikariDataSource dataSource, SchedulerService scheduler) {
        this.dataSource = dataSource;
        this.scheduler = scheduler;
    }

    public static StorageService create(JavaPlugin plugin, SchedulerService scheduler,
                                        StorageSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(plugin.getName() + "-db");

        switch (settings.type) {
            case SQLITE -> {
                Path dbFile = plugin.getDataFolder().toPath().resolve(settings.file);
                try {
                    Files.createDirectories(dbFile.getParent());
                } catch (IOException e) {
                    throw new StorageException("Could not create data folder for " + dbFile, e);
                }
                // URL params map to SQLite pragmas (sqlite-jdbc): WAL for concurrent
                // reads, busy_timeout instead of instant SQLITE_BUSY errors.
                String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString().replace('\\', '/')
                        + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on";
                config.setJdbcUrl(url);
                // SQLite is a single-writer engine — one pooled connection avoids
                // writer contention entirely.
                config.setMaximumPoolSize(1);
            }
            case MARIADB -> {
                config.setJdbcUrl("jdbc:mariadb://" + settings.host + ":" + settings.port
                        + "/" + settings.database);
                config.setUsername(settings.username);
                config.setPassword(settings.password);
                config.setMaximumPoolSize(Math.max(1, settings.poolSize));
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }
        }
        return new StorageService(new HikariDataSource(config), scheduler);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Applies any migrations newer than the recorded schema version, each in
     * its own transaction, recording progress in {@code schema_version}.
     */
    public void migrate(Migration... migrations) {
        List<Migration> ordered = new ArrayList<>(Arrays.asList(migrations));
        ordered.sort(Comparator.comparingInt(Migration::version));

        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                        + "version INT PRIMARY KEY, "
                        + "description VARCHAR(255) NOT NULL, "
                        + "applied_at VARCHAR(32) NOT NULL)");
            }

            int current = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }

            for (Migration migration : ordered) {
                if (migration.version() <= current) {
                    continue;
                }
                connection.setAutoCommit(false);
                try {
                    for (String sql : migration.statements()) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute(sql);
                        }
                    }
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO schema_version (version, description, applied_at) VALUES (?, ?, ?)")) {
                        ps.setInt(1, migration.version());
                        ps.setString(2, migration.description());
                        ps.setString(3, java.time.Instant.now().toString());
                        ps.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw new StorageException("Migration v" + migration.version()
                            + " (" + migration.description() + ") failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Schema migration failed", e);
        }
    }

    // --- synchronous helpers (call from async contexts for anything hot) ---

    public int update(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = prepare(connection, sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("update failed: " + sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = prepare(connection, sql, params);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new StorageException("query failed: " + sql, e);
        }
    }

    public <T> List<T> queryAll(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = prepare(connection, sql, params);
             ResultSet rs = ps.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapper.map(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new StorageException("query failed: " + sql, e);
        }
    }

    /**
     * Runs everything in {@code work} in a single committed transaction.
     * Any failure — checked or unchecked — rolls the whole thing back.
     */
    public void transaction(SqlConsumer<Connection> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.accept(connection);
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw new StorageException("transaction rolled back", e);
            } catch (RuntimeException | Error e) {
                // Without this, an unchecked escape would fall through to the
                // finally block and leave the partial work committed.
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("transaction failed", e);
        }
    }

    /** Rolls back, attaching any rollback failure to the original exception. */
    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    // --- async bridges ---

    public <T> CompletableFuture<T> async(SqlFunction<Connection, T> work) {
        return scheduler.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(new StorageException("async SQL failed", e));
            }
        });
    }

    public CompletableFuture<Void> asyncRun(SqlConsumer<Connection> work) {
        return async(connection -> {
            work.accept(connection);
            return null;
        });
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @Override
    public void shutdown() {
        close();
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }
}
