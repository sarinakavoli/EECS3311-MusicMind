package com.musicmind.controller;

import com.musicmind.agent.ClarificationNeededException;
import com.musicmind.agent.InfeasibleRequestException;
import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.agent.NegotiationHandler;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.Artist;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.RequestKind;
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;
import com.musicmind.llm.LLMException;
import com.musicmind.llm.LLMProvider;
import com.musicmind.llm.MockLLMProvider;
import com.musicmind.memory.MemoryManager;
import com.musicmind.memory.PostgresTestBase;
import com.musicmind.memory.repository.FeedbackRepository;
import com.musicmind.memory.repository.PlaylistHistoryRepository;
import com.musicmind.memory.repository.PreferenceRepository;
import com.musicmind.memory.repository.SessionRepository;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F5 and F11 through the controller, the state lifecycle and the real (embedded PostgreSQL) memory layer. */
class CreatePlaylistFlowTest extends PostgresTestBase {

    private final Config config = Config.of(Map.of());

    /**
     * In-memory Spotify. The library is {@code libraryTracks} tracks by three known rock artists, the "rock"
     * search (and any other query) finds {@code novelTracks} tracks by strangers, all {@code minutes} long.
     */
    private static class Music extends StubMusicService {
        final List<String> calls = new ArrayList<>();
        final List<String> searches = new ArrayList<>();
        final Map<String, List<Track>> playlists = new HashMap<>();
        int libraryTracks = 12;
        int novelTracks = 12;
        int minutes = 4;
        RuntimeException failOnSearch;
        RuntimeException failOnAdd;

        private Track track(String prefix, int i, String artist) {
            return new Track(prefix + i, "spotify:track:" + prefix + i, "Title", minutes * 60_000, false, List.of(artist));
        }
        @Override public String currentUserId() { return "sp-user"; }
        @Override public List<Artist> getTopArtists(String r) {
            return IntStream.range(0, 3).mapToObj(i -> new Artist("a" + i, "N", List.of("rock"))).toList();
        }
        @Override public List<Track> getTopItems(String type, String r) {
            return IntStream.range(0, libraryTracks).mapToObj(i -> track("t", i, "a" + i % 3)).toList();
        }
        @Override public List<Track> getSavedTracks() { return List.of(); }
        @Override public List<Track> getRecentlyPlayed() { return List.of(); }
        @Override public List<com.musicmind.domain.Album> getArtistAlbums(String id) { return List.of(); }
        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            if (failOnSearch != null) {
                RuntimeException e = failOnSearch;
                failOnSearch = null;
                throw e;
            }
            return IntStream.range(0, novelTracks).mapToObj(i -> track("n", i, "x" + i)).toList();
        }
        @Override public Playlist createPlaylist(String userId, String name) {
            calls.add("create " + name);
            playlists.put("pl1", new ArrayList<>());
            return new Playlist("pl1", name, List.of());
        }
        @Override public void addItems(String playlistId, List<String> uris) {
            calls.add("add " + uris.size());
            if (failOnAdd != null) {
                throw failOnAdd;
            }
            uris.forEach(u -> playlists.get(playlistId).add(new Track(u.substring(u.lastIndexOf(':') + 1), u, "", 0, false, List.of())));
        }
        @Override public void removeItems(String playlistId, List<String> uris) { calls.add("remove " + uris.size()); }
        @Override public void unfollowPlaylist(String playlistId) { calls.add("unfollow " + playlistId); }

        List<String> writes() {
            return calls;
        }
    }

    private MemoryManager memory() {
        return new MemoryManager(db,
                new PreferenceRepository(db, () -> "sp-user"),
                new FeedbackRepository(db, () -> "sp-user"),
                new SessionRepository(db, () -> "sp-user"),
                new PlaylistHistoryRepository(db, () -> "sp-user"));
    }

    private AppController controller(Music music, LLMProvider llm) {
        return new AppController(music, memory(), llm, config);
    }

    /** The status stored for the creation run (the profile built for it is stored too). */
    private String storedCreateStatus() {
        return memory().loadHistory("sp-user").stream()
                .filter(s -> s.request().kind() == RequestKind.CREATE_PLAYLIST)
                .findFirst().orElseThrow().status();
    }

    private static AgentRequest request(String text) {
        return new AgentRequest(text, RequestKind.CREATE_PLAYLIST);
    }

    private static List<String> record(AppController c) {
        List<String> names = new ArrayList<>();
        c.setProgressListener(names::add);
        return names;
    }

    private static final String PLAN_60 = "{\"targetMinutes\":60,\"allowExplicit\":true,\"familiarRatio\":0.7,"
            + "\"name\":\"MusicMind Mix\",\"confidence\":0.9,\"mood\":\"\"}";

    @Test
    void aRequestWalksTheLifecycleAndCreatesThePlaylist() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        List<String> states = record(c);

        Playlist playlist = c.createPlaylist(request("a mix for working"));

        assertEquals(List.of("idle", "planning", "retrieving", "selecting", "validating", "executing", "done"), states);
        assertEquals("pl1", playlist.spotifyId());
        assertEquals(15, playlist.tracks().size());
        assertEquals(List.of("create MusicMind Mix", "add 15"), music.calls);
        assertEquals(15, music.playlists.get("pl1").size());
        AgentSession session = c.lastSession();
        assertEquals("done", session.status());
        assertEquals("pl1", session.playlistId());
        assertNotNull(session.explanation());
        assertFalse(session.explanation().isBlank());
        assertEquals(15, session.trace().reasons().size());
        assertEquals("BalancedStrategy", session.trace().strategyName());
    }

    @Test
    void theFinishedSessionIsPersistedWithItsRequestPlanTraceAndSpec() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        c.createPlaylist(request("a mix for working"));

        AgentSession stored = memory().findSessionByPlaylist("pl1");
        assertNotNull(stored);
        assertEquals(c.lastSession().id(), stored.id());
        assertEquals("done", stored.status());
        assertEquals("a mix for working", stored.request().rawText());
        assertEquals(RequestKind.CREATE_PLAYLIST, stored.request().kind());
        assertFalse(stored.planSteps().isEmpty());
        assertEquals(15, stored.trace().reasons().size());
        assertTrue(stored.derivedData().contains("\"targetMinutes\":60"), stored.derivedData());
        assertTrue(stored.derivedData().contains("\"trackIds\""), stored.derivedData());
        assertFalse(stored.derivedData().contains("Title"));
    }

    @Test
    void validationRetriesAreBoundedAndNothingIsCreated() {
        Music music = new Music();
        music.minutes = 50; // no whole number of 50-minute tracks lands within 10% of 60 minutes
        AppController c = controller(music, new MockLLMProvider());
        List<String> states = record(c);

        assertThrows(InfeasibleRequestException.class, () -> c.createPlaylist(request("x")));

        long selections = states.stream().filter("selecting"::equals).count();
        assertEquals(1 + config.planMaxValidationRetries(), selections);
        assertTrue(music.calls.isEmpty(), music.calls.toString());
        assertEquals("failed", c.lastSession().status());
    }

    @Test
    void aTooLongFirstSelectionIsReselectedUntilItFits() {
        Music music = new Music();
        music.minutes = 5; // 15 tracks would run 75 minutes
        AppController c = controller(music, new MockLLMProvider());
        List<String> states = record(c);

        Playlist playlist = c.createPlaylist(request("x"));

        assertEquals(12, playlist.tracks().size());
        assertEquals(2, states.stream().filter("selecting"::equals).count());
        assertEquals(List.of("create MusicMind Mix", "add 12"), music.calls);
    }

    @Test
    void anInfeasibleRequestIsNegotiatedRelaxedAndRegenerated() {
        Music music = new Music();
        music.libraryTracks = 5;
        music.novelTracks = 5;
        AppController c = controller(music, request90Llm());
        List<FeasibilityReport> reports = new ArrayList<>();
        List<List<String>> phrased = new ArrayList<>();
        c.setNegotiationHandler((report, options) -> {
            reports.add(report);
            phrased.add(options);
            return Optional.of(report.relaxations().get(0));
        });

        Playlist playlist = c.createPlaylist(request("a long one"));

        assertEquals(1, reports.size());
        assertFalse(reports.get(0).conflicts().isEmpty());
        assertEquals(reports.get(0).relaxations().size(), phrased.get(0).size());
        assertEquals(Relaxation.Kind.SHORTEN_DURATION, reports.get(0).relaxations().get(0).kind());
        assertEquals(7, playlist.tracks().size()); // 28 minutes of four-minute tracks
        assertEquals(List.of("create MusicMind Mix", "add 7"), music.calls);
        assertTrue(c.lastSession().notices().stream().anyMatch(n -> n.contains("28")), c.lastSession().notices().toString());
    }

    private LLMProvider request90Llm() {
        return new MockLLMProvider() {
            @Override public String complete(String prompt) {
                return prompt.startsWith("MUSICMIND_TASK: PLAN")
                        ? PLAN_60.replace("\"targetMinutes\":60", "\"targetMinutes\":90") : super.complete(prompt);
            }
        };
    }

    @Test
    void cancellingTheNegotiationChangesNothingOnSpotify() {
        Music music = new Music();
        music.libraryTracks = 5;
        music.novelTracks = 5;
        AppController c = controller(music, request90Llm());
        List<String> states = record(c);
        c.setNegotiationHandler((report, options) -> Optional.empty());

        NegotiationCancelledException e = assertThrows(NegotiationCancelledException.class,
                () -> c.createPlaylist(request("a long one")));

        assertEquals(config.wording("negotiationCancelled"), e.getMessage());
        assertTrue(music.calls.isEmpty(), music.calls.toString());
        assertEquals("cancelled", c.lastSession().status());
        assertEquals("failed", states.get(states.size() - 1));
        assertEquals("cancelled", storedCreateStatus());
    }

    @Test
    void withoutAHandlerAnInfeasibleRequestStopsAndListsTheOptions() {
        Music music = new Music();
        music.libraryTracks = 5;
        music.novelTracks = 5;
        AppController c = controller(music, request90Llm());

        InfeasibleRequestException e = assertThrows(InfeasibleRequestException.class,
                () -> c.createPlaylist(request("a long one")));

        assertTrue(e.getMessage().contains("28"), e.getMessage());
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void whenNoRelaxationCanHelpTheAgentExplainsAndStops() {
        Music music = new Music();
        music.libraryTracks = 0;
        music.novelTracks = 0;
        AppController c = controller(music, new MockLLMProvider());
        AtomicInteger asked = new AtomicInteger();
        c.setNegotiationHandler((report, options) -> {
            asked.incrementAndGet();
            return Optional.empty();
        });

        InfeasibleRequestException e = assertThrows(InfeasibleRequestException.class,
                () -> c.createPlaylist(request("x")));

        assertEquals(0, asked.get());
        assertTrue(e.getMessage().contains(config.wording("conflictNoTracks")), e.getMessage());
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void aFailureWhileCreatingRollsBackAndIsReported() {
        Music music = new Music();
        music.failOnAdd = new MusicServiceException("Spotify is unavailable");
        AppController c = controller(music, new MockLLMProvider());
        List<String> states = record(c);

        MusicServiceException e = assertThrows(MusicServiceException.class, () -> c.createPlaylist(request("x")));

        assertEquals("Spotify is unavailable", e.getMessage());
        assertEquals(List.of("create MusicMind Mix", "add 15", "unfollow pl1"), music.calls);
        assertEquals("executing", states.get(states.size() - 2));
        assertEquals("failed", states.get(states.size() - 1));
        assertEquals("failed", c.lastSession().status());
        assertNull(c.lastSession().playlistId());
        assertEquals("failed", storedCreateStatus());
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndTheRunResumes() {
        Music music = new Music();
        music.failOnSearch = new AuthExpiredException("expired");
        AtomicInteger reauth = new AtomicInteger();
        AppController c = new AppController(music, memory(), new MockLLMProvider(), config, reauth::incrementAndGet);

        Playlist playlist = c.createPlaylist(request("x"));

        assertEquals(1, reauth.get());
        assertEquals(15, playlist.tracks().size());
        assertEquals("done", c.lastSession().status());
    }

    @Test
    void aSecondExpiryInTheSameRunFails() {
        Music music = new Music() {
            @Override public List<Track> search(String query, int limit) {
                throw new AuthExpiredException("expired");
            }
        };
        AtomicInteger reauth = new AtomicInteger();
        AppController c = new AppController(music, memory(), new MockLLMProvider(), config, reauth::incrementAndGet);

        assertThrows(AuthExpiredException.class, () -> c.createPlaylist(request("x")));

        assertEquals(1, reauth.get());
        assertTrue(music.calls.isEmpty());
    }

    @Test
    void withoutAnLlmKeyTheRequestSaysSoAndTouchesNothing() {
        Music music = new Music();
        AppController c = controller(music, null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.createPlaylist(request("x")));

        assertTrue(e.getMessage().contains("LLM_API_KEY"), e.getMessage());
        assertTrue(music.calls.isEmpty() && music.searches.isEmpty());
    }

    @Test
    void anUnclearGoalAsksForClarificationAndTouchesNothing() {
        Music music = new Music();
        LLMProvider unsure = prompt -> "{\"targetMinutes\":60,\"confidence\":0.1}";
        AppController c = controller(music, unsure);

        assertThrows(ClarificationNeededException.class, () -> c.createPlaylist(request("hmm")));

        assertTrue(music.calls.isEmpty() && music.searches.isEmpty());
        assertEquals("failed", c.lastSession().status());
    }

    @Test
    void anLlmOutageIsReportedWithoutRawDetail() {
        AppController c = controller(new Music(), prompt -> {
            throw new LLMException("HTTP 503 secret body");
        });

        LLMException e = assertThrows(LLMException.class, () -> c.createPlaylist(request("x")));

        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void aMoodSearchesItsGenresFirstAndTheReasonsSaySo() {
        Music music = new Music();
        AppController c = controller(music, prompt -> prompt.startsWith("MUSICMIND_TASK: PLAN")
                ? PLAN_60.replace("\"mood\":\"\"", "\"mood\":\"chill\"") : new MockLLMProvider().complete(prompt));
        c.setPreference(new UserPreference("preferredStyles", "jazz", false, 0.5));

        c.createPlaylist(request("something chill"));

        assertEquals("chill", music.searches.get(0));
        assertTrue(music.searches.contains("jazz") || music.searches.contains("ambient"), music.searches.toString());
        assertTrue(c.lastSession().trace().reasons().values().stream()
                .anyMatch(r -> r.contains("genre you asked for")), c.lastSession().trace().reasons().toString());
        assertTrue(c.lastSession().trace().constraintsSatisfied().stream().anyMatch(s -> s.contains("chill")));
    }

    @Test
    void explicitFlagsOverrideTheGoalText() {
        Music music = new Music();
        AppController c = controller(music, new MockLLMProvider());
        PlaylistSpec flags = new PlaylistSpec(32, false, 0.5, List.of(), "Evening");

        Playlist playlist = c.createPlaylist(new AgentRequest("x", RequestKind.CREATE_PLAYLIST, flags));

        assertEquals("Evening", playlist.name());
        assertEquals(8, playlist.tracks().size());
        assertEquals(List.of("create Evening", "add 8"), music.calls);
    }

    @Test
    void blockedArtistsFromPreferencesKeepTheirTracksOut() {
        Music music = new Music();
        music.libraryTracks = 20;
        AppController c = controller(music, new MockLLMProvider());
        c.setPreference(new UserPreference("blockedArtists", "a0", false, 0.5));

        Playlist playlist = c.createPlaylist(request("x"));

        assertTrue(playlist.tracks().stream().noneMatch(t -> t.artistIds().contains("a0")));
        assertEquals(15, playlist.tracks().size());
    }
}
