package com.musicmind.memory;

import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.Verdict;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest extends PostgresTestBase {

    private MemoryManager manager(Database database) {
        return new MemoryManager(database,
                new PreferenceRepository(database, currentUser::get),
                new FeedbackRepository(database, currentUser::get),
                new SessionRepository(database, currentUser::get),
                new PlaylistHistoryRepository(database, currentUser::get));
    }

    /** A database pointing at a port nothing listens on. */
    private Database deadDatabase() {
        return new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
    }

    @Test
    void preferencesRoundTripPerUser() {
        MemoryManager memory = manager(db);
        memory.savePreference(new UserPreference("explicit", "false", false, 0.7));
        List<UserPreference> prefs = memory.loadPreferences("user-1");
        assertEquals(1, prefs.size());
        assertEquals(0.7, prefs.get(0).familiarBias(), 1e-9);
        assertFalse(prefs.get(0).explicitAllowed());
        assertTrue(memory.isAvailable());
        assertNull(memory.warning());
        // a user with nothing saved gets an empty list (defaults apply only when the database is down)
        assertTrue(memory.loadPreferences("someone-else").isEmpty());
    }

    @Test
    void invalidPreferencesAreRejectedBeforeAnyWrite() {
        MemoryManager memory = manager(db);
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference(" ", "x", true, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference(null, "x", true, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference("k", "x", true, 1.5)));
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference("k", "x", true, -0.1)));
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference("k", "x", true, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> memory.savePreference(null));
        assertTrue(memory.loadPreferences("user-1").isEmpty());
        memory.savePreference(new UserPreference("k", "x", true, 0.0));
        memory.savePreference(new UserPreference("k2", "x", true, 1.0));
        assertEquals(2, memory.loadPreferences("user-1").size());
    }

    @Test
    void feedbackUpsertsAndIsPerUser() {
        MemoryManager memory = manager(db);
        memory.saveFeedback(new Feedback("t1", Verdict.ACCEPT, Instant.now()));
        memory.saveFeedback(new Feedback("t1", Verdict.REJECT, Instant.now()));
        List<Feedback> mine = memory.loadFeedback("user-1");
        assertEquals(1, mine.size());
        assertEquals(Verdict.REJECT, mine.get(0).verdict());
        assertTrue(memory.loadFeedback("user-2").isEmpty());
    }

    @Test
    void sessionsAndHistoryAndLookupByPlaylist() {
        MemoryManager memory = manager(db);
        AgentSession s = new AgentSession("s1", Instant.now(), null);
        s.setRequest(new AgentRequest("make a mix", RequestKind.CREATE_PLAYLIST));
        s.setPlaylistId("pl-7");
        memory.saveSession(s);

        List<AgentSession> history = memory.loadHistory("user-1");
        assertEquals(1, history.size());
        assertEquals("pl-7", history.get(0).playlistId());
        assertEquals("s1", memory.findSessionByPlaylist("pl-7").id());
        assertNull(memory.findSessionByPlaylist("other"));
        assertTrue(memory.loadHistory("user-2").isEmpty());
    }

    @Test
    void snapshotsRoundTrip() {
        MemoryManager memory = manager(db);
        Playlist p = new Playlist("pl-1", "Mix", List.of(
                new Track("a", "spotify:track:a", "T", 1, false, List.of()),
                new Track("b", "spotify:track:b", "T", 1, false, List.of())));
        int id = memory.saveSnapshot(p);
        assertTrue(id > 0, "snapshot ids are positive");
        assertTrue(memory.saveSnapshot(p) > id, "each snapshot gets a new, larger id");
        Playlist loaded = memory.loadSnapshot(id);
        assertEquals("Mix", loaded.name());
        assertEquals(List.of("a", "b"), loaded.tracks().stream().map(Track::id).toList());
        assertNull(memory.loadSnapshot(id + 100));
    }

    @Test
    void unavailableDatabaseFallsBackToDefaultsAndWarns() {
        MemoryManager memory = manager(deadDatabase());
        assertFalse(memory.isAvailable());

        List<UserPreference> defaults = memory.loadPreferences("user-1");
        assertFalse(defaults.isEmpty());
        assertTrue(defaults.stream().allMatch(p -> p.familiarBias() >= 0 && p.familiarBias() <= 1));
        assertNotNull(memory.warning());

        assertDoesNotThrow(() -> memory.savePreference(new UserPreference("k", "v", true, 0.5)));
        assertDoesNotThrow(() -> memory.saveFeedback(new Feedback("t", Verdict.ACCEPT, Instant.now())));
        assertDoesNotThrow(() -> memory.saveSession(new AgentSession("s", Instant.now(), null)));
        assertTrue(memory.loadFeedback("user-1").isEmpty());
        assertTrue(memory.loadHistory("user-1").isEmpty());
        assertNull(memory.findSessionByPlaylist("pl"));
        assertTrue(memory.warning().toLowerCase().contains("unavailable"));
    }

    @Test
    void validationStillAppliesWhenUnavailable() {
        MemoryManager memory = manager(deadDatabase());
        assertThrows(IllegalArgumentException.class,
                () -> memory.savePreference(new UserPreference("k", "v", true, 2)));
    }

    @Test
    void recoversWhenTheDatabaseComesBack() {
        MemoryManager memory = manager(db);
        db.close();   // simulate a dropped connection; the next call reconnects
        memory.savePreference(new UserPreference("k", "v", true, 0.5));
        assertEquals(1, memory.loadPreferences("user-1").size());
        assertNull(memory.warning());
    }
}
