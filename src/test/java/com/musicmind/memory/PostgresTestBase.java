package com.musicmind.memory;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared base for persistence tests: one embedded PostgreSQL server is started
 * per JVM (lazily, stopped by a shutdown hook) and every test gets its own
 * freshly created database, so tests are isolated and need no external server.
 */
public abstract class PostgresTestBase {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();
    private static EmbeddedPostgres server;

    protected Database db;
    /** The "logged in" user stamped on writes by repositories created in a test. */
    protected final AtomicReference<String> currentUser = new AtomicReference<>("user-1");

    private static synchronized EmbeddedPostgres server() throws IOException {
        if (server == null) {
            server = EmbeddedPostgres.start();
            EmbeddedPostgres started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.close();
                } catch (IOException ignored) {
                    // best-effort shutdown
                }
            }));
        }
        return server;
    }

    @BeforeEach
    void startDatabase() throws IOException, SQLException {
        EmbeddedPostgres pg = server();
        String name = "mm_test_" + DB_COUNTER.incrementAndGet();
        try (Connection admin = pg.getPostgresDatabase().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        db = new Database(pg.getJdbcUrl("postgres", name), "postgres", "postgres");
        db.init();
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }
}
