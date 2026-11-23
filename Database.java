package kz.taxi.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** One connection and monitor per application; transactions serialize dispatch decisions. */
public final class Database implements AutoCloseable {
    private final Connection connection;
    @FunctionalInterface public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    public Database(Path file) {
        Connection opened = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            connection = opened;
            update("PRAGMA foreign_keys = ON");
            query("PRAGMA busy_timeout = 5000", rs -> rs.getInt(1));
            initialize();
        } catch (Exception e) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw new IllegalStateException("Дерекқорды ашу мүмкін емес", e);
        }
    }

    private void initialize() {
        update("CREATE TABLE IF NOT EXISTS passengers (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
        update("""
                CREATE TABLE IF NOT EXISTS drivers (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL,
                  model TEXT NOT NULL, plate TEXT NOT NULL UNIQUE, tariff TEXT NOT NULL,
                  status TEXT NOT NULL CHECK(status IN ('AVAILABLE','BUSY','OFFLINE')))
                """);
        update("""
                CREATE TABLE IF NOT EXISTS orders (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, passenger_id INTEGER NOT NULL REFERENCES passengers(id),
                  pickup_x REAL NOT NULL, pickup_y REAL NOT NULL, destination_x REAL NOT NULL, destination_y REAL NOT NULL,
                  tariff TEXT NOT NULL, distance_km TEXT NOT NULL, minutes TEXT NOT NULL, surge TEXT NOT NULL, price TEXT NOT NULL,
                  status TEXT NOT NULL CHECK(status IN ('WAITING','ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED')),
                  created_at TEXT NOT NULL)
                """);
        update("""
                CREATE TABLE IF NOT EXISTS trips (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL REFERENCES orders(id),
                  driver_id INTEGER NOT NULL REFERENCES drivers(id),
                  status TEXT NOT NULL CHECK(status IN ('CREATED','IN_PROGRESS','COMPLETED','CANCELLED')),
                  price TEXT NOT NULL, offered_at TEXT NOT NULL, deadline TEXT NOT NULL,
                  accepted_at TEXT, started_at TEXT, completed_at TEXT, rating INTEGER CHECK(rating BETWEEN 1 AND 5))
                """);
        update("CREATE UNIQUE INDEX IF NOT EXISTS one_active_driver ON trips(driver_id) WHERE status IN ('CREATED','IN_PROGRESS')");
        update("CREATE UNIQUE INDEX IF NOT EXISTS one_active_order ON trips(order_id) WHERE status IN ('CREATED','IN_PROGRESS')");
        update("CREATE INDEX IF NOT EXISTS orders_status ON orders(status)");
    }

    public synchronized <T> T transaction(Function<Database, T> action) {
        try {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Nested transaction");
            connection.setAutoCommit(false);
        } catch (SQLException e) { throw failure(e); }
        try {
            T result = action.apply(this);
            connection.commit();
            return result;
        } catch (RuntimeException | Error | SQLException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (e instanceof RuntimeException runtime) throw runtime;
            if (e instanceof Error error) throw error;
            throw failure((SQLException) e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { throw failure(e); }
        }
    }

    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) { return statement.executeUpdate(); }
        catch (SQLException e) { throw failure(e); }
    }

    public synchronized long insert(String sql, Object... args) {
        update(sql, args);
        return query("SELECT last_insert_rowid()", rs -> rs.getLong(1)).get(0);
    }

    public synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (PreparedStatement statement = prepare(sql, args); ResultSet rs = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (rs.next()) results.add(mapper.map(rs));
            return List.copyOf(results);
        } catch (SQLException e) { throw failure(e); }
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        } catch (SQLException e) { statement.close(); throw e; }
    }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("Дерекқор қатесі: " + e.getMessage(), e); }
    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException e) { throw failure(e); }
    }
}
