package com.musicmind.memory.repository;

import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenreRepositoryTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2026-01-10T00:00:00Z");

    @Test
    void roundTripsGenresInOrder() {
        GenreRepository repo = new GenreRepository(db);
        repo.save("a1", List.of("jazz", "post-bop"), T0);

        assertEquals(Optional.of(List.of("jazz", "post-bop")), repo.find("a1", T0.minusSeconds(1)));
    }

    @Test
    void anUnknownArtistIsAMissNotAnEmptyResult() {
        assertEquals(Optional.empty(), new GenreRepository(db).find("nobody", T0));
    }

    @Test
    void anEmptyListIsStoredAndReadBackAsAKnownNoMatch() {
        GenreRepository repo = new GenreRepository(db);
        repo.save("a1", List.of(), T0);

        assertEquals(Optional.of(List.of()), repo.find("a1", T0));
    }

    @Test
    void entriesFetchedBeforeTheCutoffAreMisses() {
        GenreRepository repo = new GenreRepository(db);
        repo.save("a1", List.of("jazz"), T0);

        assertEquals(Optional.empty(), repo.find("a1", T0.plusSeconds(1)));
        assertEquals(Optional.of(List.of("jazz")), repo.find("a1", T0));
    }

    @Test
    void savingAgainReplacesTheEntry() {
        GenreRepository repo = new GenreRepository(db);
        repo.save("a1", List.of("jazz"), T0);
        repo.save("a1", List.of("rock"), T0.plusSeconds(60));

        assertEquals(Optional.of(List.of("rock")), repo.find("a1", T0));
    }

    @Test
    void saveThenFind_roundTripsLastfmTags() {
        GenreRepository repo = new GenreRepository(db);
        repo.save("a1", List.of("shoegaze", "dream pop"), T0);

        assertEquals(Optional.of(List.of("shoegaze", "dream pop")), repo.find("a1", T0));
    }

    @Test
    void oldMusicBrainzRowsAreNeverRead() throws SQLException {
        try (Statement st = db.connection().createStatement()) {
            st.execute("DROP TABLE artist_genre");
            st.execute("CREATE TABLE artist_genre (artist_id TEXT PRIMARY KEY, genres TEXT NOT NULL, "
                    + "fetched_at TIMESTAMPTZ NOT NULL)");
            st.execute("INSERT INTO artist_genre VALUES ('old', '[\"jazz\"]', now())");
        }
        db.close();
        db.init();

        assertEquals(Optional.empty(), new GenreRepository(db).find("old", T0));
        try (Statement st = db.connection().createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM artist_genre")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
