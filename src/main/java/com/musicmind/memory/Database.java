package com.musicmind.memory;

import com.musicmind.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Owns the PostgreSQL JDBC connection and the schema. The connection is opened
 * lazily and re-opened if it was closed or dropped, and the schema is ensured
 * whenever a new connection is made, so a database that comes up after the app
 * started is picked up without a restart.
 */
public class Database implements AutoCloseable {

    private final String url;
    private final String user;
    private final String password;
    private final int connectTimeoutSeconds = Config.defaults().databaseConnectTimeoutSeconds();
    private Connection connection;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** The live connection, opening it (and creating the schema) if needed. */
    public synchronized Connection connection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(connectTimeoutSeconds)) {
                closeQuietly();
                Properties props = new Properties();
                props.setProperty("user", user == null ? "" : user);
                props.setProperty("password", password == null ? "" : password);
                props.setProperty("connectTimeout", String.valueOf(connectTimeoutSeconds));
                Connection fresh = DriverManager.getConnection(url, props);
                try {
                    createSchema(fresh);
                } catch (SQLException e) {
                    fresh.close();
                    throw e;
                }
                connection = fresh;
            }
            return connection;
        } catch (SQLException e) {
            throw new PersistenceException("Could not connect to the database: " + url, e);
        }
    }

    /** Connect and create the tables if they do not yet exist. Safe to call repeatedly. */
    public void init() {
        connection();
    }

    /** True when a connection can be made right now. */
    public boolean isAvailable() {
        try {
            connection();
            return true;
        } catch (PersistenceException e) {
            return false;
        }
    }

    private static void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS user_preference (
                        user_id          TEXT NOT NULL,
                        key              TEXT NOT NULL,
                        value            TEXT,
                        explicit_allowed BOOLEAN NOT NULL DEFAULT TRUE,
                        familiar_bias    DOUBLE PRECISION NOT NULL DEFAULT 0.5,
                        PRIMARY KEY (user_id, key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS feedback (
                        user_id    TEXT NOT NULL,
                        track_id   TEXT NOT NULL,
                        verdict    TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL,
                        PRIMARY KEY (user_id, track_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_session (
                        id           TEXT PRIMARY KEY,
                        user_id      TEXT NOT NULL,
                        started_at   TIMESTAMPTZ NOT NULL,
                        status       TEXT NOT NULL,
                        request_text TEXT,
                        request_kind TEXT,
                        plan_steps   TEXT NOT NULL DEFAULT '[]',
                        playlist_id  TEXT,
                        derived_json TEXT,
                        trace_json   TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS agent_session_user_idx ON agent_session (user_id, started_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_snapshot (
                        id          SERIAL PRIMARY KEY,
                        user_id     TEXT NOT NULL,
                        playlist_id TEXT NOT NULL,
                        name        TEXT,
                        track_uris  TEXT NOT NULL,
                        created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
        }
    }

    private void closeQuietly() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // closing is best-effort
        }
        connection = null;
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }
}
