package com.musicmind.memory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite JDBC connection and creates the schema. A shared
 * connection keeps an in-memory database ({@code jdbc:sqlite::memory:}) alive
 * for the whole run, which the tests rely on.
 */
public class Database implements AutoCloseable {

    private final String url;
    private Connection connection;

    public Database(String url) {
        this.url = url;
    }

    public synchronized Connection connection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(url);
            }
            return connection;
        } catch (SQLException e) {
            throw new PersistenceException("Could not open SQLite database: " + url, e);
        }
    }

    /** Create tables if they do not yet exist. Safe to call repeatedly. */
    public synchronized void init() {
        try (Statement st = connection().createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS taste_profile (
                        user_id      TEXT PRIMARY KEY,
                        generated_at TEXT NOT NULL,
                        json         TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS user_preference (
                        user_id          TEXT NOT NULL,
                        key              TEXT NOT NULL,
                        value            TEXT,
                        explicit_allowed INTEGER NOT NULL DEFAULT 1,
                        familiar_bias    REAL NOT NULL DEFAULT 0.5,
                        PRIMARY KEY (user_id, key)
                    )""");
        } catch (SQLException e) {
            throw new PersistenceException("Could not initialise database schema.", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // closing is best-effort
        }
    }
}
