package com.musicmind.memory;

import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;
import com.musicmind.memory.repository.SqlitePreferenceRepository;
import com.musicmind.memory.repository.SqliteProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips profiles and preferences through an in-memory SQLite database. */
class MemoryManagerTest {

    private Database db;
    private MemoryManager memory;

    @BeforeEach
    void setUp() {
        db = new Database("jdbc:sqlite::memory:");
        db.init();
        memory = new MemoryManager(new SqliteProfileRepository(db), new SqlitePreferenceRepository(db));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void savesAndLoadsTasteProfile() {
        ListeningProfile profile = new ListeningProfile(
                Map.of("pop", 0.6, "rock", 0.4),
                List.of(new Artist("a1", "Artist One", List.of("pop", "rock"))),
                List.of(new Track("t1", "spotify:track:t1", "Song", 200_000, false, List.of("a1"))),
                Instant.now());

        memory.saveProfile("user-1", profile);
        Optional<ListeningProfile> loaded = memory.loadProfile("user-1");

        assertTrue(loaded.isPresent());
        assertEquals("pop", loaded.get().topGenre());
        assertEquals(1, loaded.get().topArtists().size());
        assertEquals("Artist One", loaded.get().topArtists().get(0).name());
        assertEquals("Song", loaded.get().topTracks().get(0).title());
    }

    @Test
    void latestProfileReplacesPrevious() {
        memory.saveProfile("user-1", new ListeningProfile(Map.of("pop", 1.0), List.of(), List.of(), Instant.now()));
        memory.saveProfile("user-1", new ListeningProfile(Map.of("jazz", 1.0), List.of(), List.of(), Instant.now()));
        assertEquals("jazz", memory.loadProfile("user-1").orElseThrow().topGenre());
    }

    @Test
    void savesAndLoadsPreferences() {
        memory.savePreference("user-1", new UserPreference("explicit", "false", false, 0.7));
        List<UserPreference> prefs = memory.preferences("user-1");
        assertEquals(1, prefs.size());
        assertEquals("explicit", prefs.get(0).key());
        assertEquals(0.7, prefs.get(0).familiarBias(), 1e-9);
        assertTrue(memory.preferences("someone-else").isEmpty());
    }
}
