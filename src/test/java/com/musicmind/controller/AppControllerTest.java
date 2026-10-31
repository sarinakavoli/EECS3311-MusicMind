package com.musicmind.controller;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Track;
import com.musicmind.domain.TrendReport;
import com.musicmind.domain.User;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppControllerTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /** Spotify fake whose user id is only known after "login", like the real adapter. */
    private class Music extends StubMusicService {
        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Artist> getTopArtists(String r) {
            String genre = r.startsWith("short") ? "synthwave" : "indie";
            return IntStream.range(0, 6).mapToObj(i -> new Artist(genre + i, "N" + i, List.of(genre))).toList();
        }
        @Override public List<Track> getTopItems(String type, String r) {
            return IntStream.range(0, 6)
                    .mapToObj(i -> new Track("t" + i, "spotify:track:t" + i, "T" + i, 1, false, List.of())).toList();
        }
        @Override public List<Track> getSavedTracks() { return List.of(); }
    }

    private MemoryManager memory(Music music) {
        // Same wiring as AppController.create(): repositories stamp the Spotify user id.
        return new MemoryManager(db,
                new PreferenceRepository(db, music::currentUserId),
                new FeedbackRepository(db, music::currentUserId),
                new SessionRepository(db, music::currentUserId),
                new PlaylistHistoryRepository(db, music::currentUserId));
    }

    private AppController controller(Music music) {
        return new AppController(music, memory(music), null, config);
    }

    @Test
    void worksWithNoLlmKeyAndLabelsTheComputedSummary() {
        AppController c = controller(new Music());
        assertFalse(c.isLlmConfigured());

        ListeningProfile p = c.analyzeTaste(c.login());

        assertTrue(p.summary().endsWith(config.computedLabel()), p.summary());
        TrendReport t = c.analyzeTrends(c.currentUser());
        assertFalse(t.addedGenres().isEmpty(), "the ranges differ, so there is something to narrate");
        assertTrue(t.narrative().endsWith(config.computedLabel()), t.narrative());
    }

    @Test
    void loginReturnsTheSpotifyUserAndCurrentUserRemembersIt() {
        AppController c = controller(new Music());
        assertNull(c.currentUser(), "nobody is logged in yet");

        User u = c.login();

        assertEquals("sp-user", u.id());
        assertEquals("sp-user", u.spotifyUserId());
        assertEquals(u.id(), c.currentUser().id());
    }

    @Test
    void sessionsAreStoredUnderTheLoggedInUserAndLastProfileFindsThem() {
        AppController c = controller(new Music());
        User u = c.login();
        assertTrue(c.lastProfile(u).isEmpty());

        ListeningProfile built = c.analyzeTaste(u);

        assertEquals(1, c.memory().loadHistory("sp-user").size());
        assertEquals(built.genreHistogram(), c.lastProfile(u).orElseThrow().genreHistogram());
        assertTrue(c.lastProfile(new User("other", "o", "other")).isEmpty());
    }

    @Test
    void analyzeTasteWithARangeUsesThatRange() {
        AppController c = controller(new Music());
        ListeningProfile p = c.analyzeTaste(c.login(), "long");
        assertFalse(p.genreHistogram().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.analyzeTaste(c.currentUser(), "nope"));
    }

    @Test
    void withoutSpotifyEveryFeatureSaysSoInsteadOfFailingObscurely() {
        AppController c = new AppController(null, memory(new Music()), null, config);
        assertFalse(c.isSpotifyConfigured());
        IllegalStateException e = assertThrows(IllegalStateException.class, c::login);
        assertTrue(e.getMessage().contains("SPOTIFY_CLIENT_ID"), e.getMessage());
        assertNull(c.currentUser());
    }

    @Test
    void spotifyAttributionComesFromConfiguration() {
        assertEquals(config.spotifyAttribution(), controller(new Music()).spotifyAttribution());
    }

    @Test
    void searchWorksWithNoLlmAndExposesTheLabelAndNotice() {
        Music music = new Music() {
            @Override public List<Track> search(String q, int limit) {
                return q.equals("nothing") ? List.of()
                        : List.of(new Track("s1", "spotify:track:s1", "Jazz", 1, false, List.of()));
            }
        };
        AppController c = controller(music);

        assertEquals(1, c.searchMusic("jazz").size());
        assertEquals(config.searchFallbackLabel(), c.lastSearchLabel());
        assertNull(c.lastSearchNotice());

        assertTrue(c.searchMusic("nothing").isEmpty());
        assertTrue(c.lastSearchNotice().contains("nothing"));
    }
}
