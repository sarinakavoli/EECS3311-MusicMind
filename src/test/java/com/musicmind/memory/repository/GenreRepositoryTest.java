package com.musicmind.memory.repository;

import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

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
}
