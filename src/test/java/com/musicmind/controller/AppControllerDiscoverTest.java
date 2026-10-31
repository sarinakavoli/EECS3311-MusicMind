package com.musicmind.controller;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;
import com.musicmind.domain.Verdict;
import com.musicmind.llm.LLMProvider;
import com.musicmind.memory.Database;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F4, F8 and F9 through the controller, the facade and the real (embedded PostgreSQL) memory layer. */
class AppControllerDiscoverTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /** Library t0..t5 by artists a0..a5 (rock); the rock search finds twelve tracks by strangers x0..x11. */
    private static class Music extends StubMusicService {
        final List<String> calls = new ArrayList<>();
        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Artist> getTopArtists(String r) {
            return IntStream.range(0, 6).mapToObj(i -> new Artist("a" + i, "N" + i, List.of("rock"))).toList();
        }
        @Override public List<Track> getTopItems(String type, String r) {
            calls.add("top");
            return IntStream.range(0, 6).mapToObj(i ->
                    new Track("t" + i, "spotify:track:t" + i, "T" + i, 1000, false, List.of("a" + i))).toList();
        }
        @Override public List<Track> getSavedTracks() { return List.of(); }
        @Override public List<Track> getRecentlyPlayed() { return List.of(); }
        @Override public List<Track> search(String query, int limit) {
            calls.add("search " + query);
            if (!query.equals("rock")) return List.of();
            return IntStream.range(0, 12).mapToObj(i ->
                    new Track("n" + i, "spotify:track:n" + i, "N" + i, 1000, i == 3, List.of("x" + i))).toList();
        }
        @Override public List<com.musicmind.domain.Album> getArtistAlbums(String id) { return List.of(); }
    }

    private MemoryManager memory(Database database) {
        return new MemoryManager(database,
                new PreferenceRepository(database, () -> "sp-user"),
                new FeedbackRepository(database, () -> "sp-user"),
                new SessionRepository(database, () -> "sp-user"),
                new PlaylistHistoryRepository(database, () -> "sp-user"));
    }

    private AppController controller(Music music) {
        return new AppController(music, memory(db), null, config);
    }

    private static List<String> ids(List<Recommendation> recs) {
        return recs.stream().map(r -> r.track().id()).toList();
    }

    @Test
    void discoverBuildsTheProfileOnDemandAndReturnsNewTracksWithReasons() {
        AppController c = controller(new Music());

        List<Recommendation> recs = c.discover(c.discoverSpec(0.7));

        assertFalse(recs.isEmpty());
        assertTrue(recs.stream().allMatch(r -> !r.reason().isBlank()));
        assertTrue(recs.stream().anyMatch(r -> r.track().id().startsWith("n")), "a new track from the genre search");
        assertEquals("sp-user", c.currentUser().id());
        assertNotNull(c.lastDecisionTrace());
    }

    @Test
    void discoverDoesNotPersistItsResultsAndReusesTheStoredProfile() {
        Music music = new Music();
        AppController c = controller(music);

        List<Recommendation> first = c.discover(c.discoverSpec(0.5));
        int sessionsAfterFirst = c.memory().loadHistory("sp-user").size();
        List<Recommendation> second = c.discover(c.discoverSpec(0.5)); // from the id-only stored profile

        assertEquals(1, sessionsAfterFirst, "only the taste profile is stored");
        assertEquals(1, c.memory().loadHistory("sp-user").size(), "discover itself saves nothing");
        assertEquals(ids(first), ids(second), "the stored profile gives the same picks as the fresh one");
        assertTrue(second.stream().noneMatch(r -> r.track().id().startsWith("t")), "library tracks are never recommended");
        assertTrue(second.stream().anyMatch(r -> r.track().id().startsWith("n") && r.familiarity() == 0.0),
                "genre-search picks are novel");
        assertEquals(ids(second).size(), ids(second).stream().distinct().count());
        assertTrue(second.stream().allMatch(r -> !r.reason().isBlank()));
    }

    /** Six top artists alternate rock and jazz; each has one album with a new track; searches return known and unknown artists. */
    private static class VariedMusic extends Music {
        @Override public List<Artist> getTopArtists(String r) {
            return IntStream.range(0, 6).mapToObj(i ->
                    new Artist("a" + i, "N" + i, List.of(i % 2 == 0 ? "rock" : "jazz"))).toList();
        }
        @Override public List<com.musicmind.domain.Album> getArtistAlbums(String id) {
            return List.of(new com.musicmind.domain.Album("al-" + id, "Album", "2020"));
        }
        @Override public List<Track> getAlbumTracks(String albumId) {
            String artist = albumId.substring("al-".length());
            return List.of(new Track("new-" + artist, "spotify:track:new-" + artist, "New", 1000, false, List.of(artist)));
        }
        @Override public List<Track> search(String query, int limit) {
            return IntStream.range(0, 12).mapToObj(i -> new Track("n" + i, "spotify:track:n" + i, "N" + i, 1000, false,
                    List.of(i % 3 == 0 ? "a" + (i % 6) : "x" + i))).toList();
        }
    }

    @Test
    void aStoredProfileScoresTheSameAsAFreshOne() {
        AppController c = controller(new VariedMusic());
        PlaylistSpec spec = c.discoverSpec(0.5);

        List<Recommendation> fresh = c.discover(spec);   // no stored profile yet: built just now
        List<Recommendation> stored = c.discover(spec);  // read back from the stored session

        assertFalse(fresh.isEmpty());
        assertEquals(fresh, stored, "same tracks, reasons, familiarity and scores");
        assertTrue(fresh.stream().anyMatch(r -> r.familiarity() == 1.0), "known-artist picks depend on stored track artists");
    }

    private static final String OLD_FORMAT = "{\"range\":\"medium\",\"genreHistogram\":{\"rock\":1.0},"
            + "\"artistIds\":[\"a0\",\"a1\"],\"trackIds\":[\"t0\"],\"savedTrackIds\":[],\"summary\":\"s\","
            + "\"partial\":false,\"notices\":[],\"generatedAt\":\"%s\"}";

    private void storeProfile(String generatedAt) {
        com.musicmind.domain.AgentSession s = new com.musicmind.domain.AgentSession(
                java.util.UUID.randomUUID().toString(), java.time.Instant.now(), null);
        s.setStatus("done");
        s.setRequest(new com.musicmind.domain.AgentRequest("view taste profile (medium)",
                com.musicmind.domain.RequestKind.ANALYZE_TASTE));
        s.setDerivedData(String.format(OLD_FORMAT, generatedAt));
        memory(db).saveSession(s);
    }

    @Test
    void aStoredProfileOlderThanTheLimitIsRebuilt() {
        storeProfile(java.time.Instant.now().minus(java.time.Duration.ofHours(config.profileMaxAgeHours() + 1)).toString());
        AppController c = controller(new Music());

        c.discover(c.discoverSpec(0.5));

        assertEquals(2, c.memory().loadHistory("sp-user").size(), "a new profile was built and stored");
    }

    @Test
    void aRecentStoredProfileInTheOldFormatStillLoadsAndIsReused() {
        storeProfile(java.time.Instant.now().minus(java.time.Duration.ofHours(1)).toString());
        AppController c = controller(new Music());

        c.discover(c.discoverSpec(0.5));

        assertEquals(1, c.memory().loadHistory("sp-user").size(), "the stored profile was reused");
    }

    @Test
    void discoverTellsTheUserWhenSomeSavedTracksWereNotChecked() {
        AppController c = controller(new Music() {
            @Override public List<Track> getSavedTracks() { return new com.musicmind.music.TrackList(List.of(), true); }
        });
        String notice = String.format(config.wording("savedTracksCapped"), config.spotifyMaxSavedTracks());

        c.discover(c.discoverSpec(0.5));                 // profile built now
        assertTrue(c.lastDiscoverNotices().contains(notice), c.lastDiscoverNotices().toString());
        c.discover(c.discoverSpec(0.5));                 // profile read back from storage
        assertTrue(c.lastDiscoverNotices().contains(notice), c.lastDiscoverNotices().toString());
    }

    @Test
    void discoverShowsNoSavedTracksNoticeForACompleteLibrary() {
        AppController c = controller(new Music());

        c.discover(c.discoverSpec(0.5));

        assertTrue(c.lastDiscoverNotices().stream().noneMatch(n -> n.contains("not checked")),
                c.lastDiscoverNotices().toString());
    }

    @Test
    void discoverNeverCallsTheLlm() {
        AtomicInteger calls = new AtomicInteger();
        LLMProvider counting = prompt -> { calls.incrementAndGet(); return "A line about your taste."; };
        AppController c = new AppController(new Music(), memory(db), counting, config);
        c.analyzeTaste(c.login());
        int afterProfile = calls.get();

        c.discover(c.discoverSpec(0.5));

        assertEquals(afterProfile, calls.get());
    }

    @Test
    void theFamiliarRatioPicksTheStrategy() {
        AppController c = controller(new Music());
        assertEquals("FamiliarStrategy", strategyAfter(c, 0.0));
        assertEquals("DiscoveryStrategy", strategyAfter(c, 1.0));
        assertEquals("BalancedStrategy", strategyAfter(c, 0.3));
    }

    private static String strategyAfter(AppController c, double novelty) {
        c.discover(c.discoverSpec(novelty));
        DecisionTrace trace = c.lastDecisionTrace();
        return trace.strategyName();
    }

    @Test
    void blockedArtistPreferencesApplyToDiscovery() {
        AppController c = controller(new Music());
        c.setPreference(new UserPreference("blockedArtists", "x0,x1,a0", false, 0.5));

        List<Recommendation> recs = c.discover(c.discoverSpec(0.5));

        assertFalse(ids(recs).contains("n0") || ids(recs).contains("n1") || ids(recs).contains("t0"), ids(recs).toString());
        assertFalse(recs.isEmpty());
    }

    @Test
    void explicitPolicyBlockRemovesExplicitTracks() {
        AppController c = controller(new Music());
        assertTrue(ids(c.discover(c.discoverSpec(1.0))).contains("n3"), "explicit allowed by default");

        c.setPreference(new UserPreference("explicitPolicy", "block", false, 0.5));

        assertFalse(ids(c.discover(c.discoverSpec(1.0))).contains("n3"));
    }

    @Test
    void theStoredFamiliarBiasIsTheDefaultButNoveltyOverridesIt() {
        AppController c = controller(new Music());
        assertEquals(config.discoverDefaultFamiliarRatio(), c.discoverSpec(null).familiarRatio(), 1e-9);

        c.setPreference(new UserPreference("familiarBias", "0.9", false, 0.9));

        assertEquals(0.9, c.discoverSpec(null).familiarRatio(), 1e-9);
        assertEquals(0.7, c.discoverSpec(0.3).familiarRatio(), 1e-9);
    }

    @Test
    void anOutOfRangeNoveltyIsRejectedWithAMessage() {
        AppController c = controller(new Music());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.discoverSpec(1.5));
        assertTrue(e.getMessage().contains("between 0 and 1"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> c.discoverSpec(-0.1));
    }

    @Test
    void preferencesRoundTripAndAreValidated() {
        AppController c = controller(new Music());
        c.setPreference(new UserPreference("familiarBias", "0.4", false, 0.4));
        c.setPreference(new UserPreference("familiarBias", "0.6", false, 0.6));

        List<UserPreference> prefs = c.getPreferences();
        assertEquals(1, prefs.size(), "same key is updated, not duplicated");
        assertEquals(0.6, prefs.get(0).familiarBias(), 1e-9);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.setPreference(new UserPreference("familiarBias", "2", false, 2.0)));
        assertTrue(e.getMessage().contains("between 0 and 1"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> c.setPreference(new UserPreference(" ", "x", false, 0.5)));
    }

    @Test
    void feedbackIsStoredOncePerTrackAndChangesLaterRecommendations() {
        AppController c = controller(new Music());
        PlaylistSpec spec = c.discoverSpec(1.0);
        List<String> before = ids(c.discover(spec));
        assertTrue(before.contains("n7"));

        c.recordFeedback("n7", Verdict.REJECT);
        c.recordFeedback("spotify:track:n7", Verdict.REJECT);
        c.recordFeedback("n8", Verdict.ACCEPT);

        List<Feedback> stored = c.memory().loadFeedback("sp-user");
        assertEquals(2, stored.size(), "repeat feedback on a track is an upsert");
        List<String> after = ids(c.discover(spec));
        assertEquals("n7", after.get(after.size() - 1), "a rejected track sinks to the bottom: " + after);
        assertEquals("n8", after.get(0));

        c.recordFeedback("n7", Verdict.ACCEPT);
        assertEquals(Verdict.ACCEPT, c.memory().loadFeedback("sp-user").stream()
                .filter(f -> f.trackId().equals("n7")).findFirst().orElseThrow().verdict());
    }

    @Test
    void feedbackNeedsATrackAndAVerdict() {
        AppController c = controller(new Music());
        assertThrows(IllegalArgumentException.class, () -> c.recordFeedback(" ", Verdict.ACCEPT));
        assertThrows(IllegalArgumentException.class, () -> c.recordFeedback("n1", null));
    }

    @Test
    void everyCandidateBlockedIsReportedThroughTheController() {
        AppController c = controller(new Music() {
            @Override public List<Track> search(String q, int l) {
                return List.of(new Track("b0", "spotify:track:b0", "B0", 1000, false, List.of("a0")));
            }
        });
        c.setPreference(new UserPreference("blockedArtists",
                "a0,a1,a2,a3,a4,a5", false, 0.5));

        List<Recommendation> recs = c.discover(c.discoverSpec(0.5));

        assertTrue(recs.isEmpty());
        assertTrue(c.lastDiscoverNotices().stream().anyMatch(n -> n.contains("blocked artists") && n.contains("Adjust")),
                c.lastDiscoverNotices().toString());
    }

    @Test
    void withTheDatabaseDownEverythingStillRunsWithSafeDefaultsAndAWarning() {
        Database dead = new Database("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        AppController c = new AppController(new Music(), memory(dead), null, config);

        List<Recommendation> recs = c.discover(c.discoverSpec(null));

        assertFalse(recs.isEmpty(), "discovery works without the database");
        assertEquals(0.5, c.discoverSpec(null).familiarRatio(), 1e-9, "the safe default leaning");
        assertTrue(c.lastDiscoverNotices().stream().anyMatch(n -> n.toLowerCase().contains("database unavailable")),
                c.lastDiscoverNotices().toString());
        assertTrue(recs.stream().noneMatch(r -> r.track().isExplicit()), "safe default: no explicit content");

        assertEquals(1, c.getPreferences().size());
        assertNotNull(c.memory().warning());
        c.setPreference(new UserPreference("familiarBias", "0.3", false, 0.3));
        assertTrue(c.memory().warning().toLowerCase().contains("unavailable"));
        c.recordFeedback("n1", Verdict.REJECT);
        assertTrue(c.memory().warning().toLowerCase().contains("unavailable"));
    }

    @Test
    void withoutSpotifyDiscoveryExplainsWhatIsMissing() {
        AppController c = new AppController(null, memory(db), null, config);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.discover(c.discoverSpec(0.5)));
        assertTrue(e.getMessage().contains("SPOTIFY_CLIENT_ID"));
        assertNull(c.lastDecisionTrace());
        assertTrue(c.lastDiscoverNotices().isEmpty());
    }
}
