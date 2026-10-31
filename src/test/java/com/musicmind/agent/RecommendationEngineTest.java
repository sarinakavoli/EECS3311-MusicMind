package com.musicmind.agent;

import com.musicmind.agent.strategy.BalancedStrategy;
import com.musicmind.agent.strategy.DiscoveryStrategy;
import com.musicmind.agent.strategy.FamiliarStrategy;
import com.musicmind.config.Config;
import com.musicmind.domain.Album;
import com.musicmind.domain.Artist;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.Verdict;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationEngineTest {

    private final Config config = Config.of(Map.of());

    private static Track track(String id, String artist) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 1000, false, List.of(artist));
    }

    private static Track explicit(String id, String artist) {
        return new Track(id, "spotify:track:" + id, "Title " + id, 1000, true, List.of(artist));
    }

    /** In-memory Spotify: library pools, genre searches and artist albums. */
    private static class FakeMusic extends StubMusicService {
        List<Track> top = new ArrayList<>();
        List<Track> saved = new ArrayList<>();
        List<Track> recent = new ArrayList<>();
        final Map<String, List<Track>> byGenre = new LinkedHashMap<>();
        final Map<String, List<Album>> albumsByArtist = new LinkedHashMap<>();
        final Map<String, List<Track>> tracksByAlbum = new LinkedHashMap<>();
        final List<String> searches = new ArrayList<>();
        RuntimeException searchFailure;
        RuntimeException libraryFailure;

        @Override public List<Track> getTopItems(String type, String range) {
            if (libraryFailure != null) throw libraryFailure;
            return top;
        }
        @Override public List<Track> getSavedTracks() {
            if (libraryFailure != null) throw libraryFailure;
            return saved;
        }
        @Override public List<Track> getRecentlyPlayed() {
            if (libraryFailure != null) throw libraryFailure;
            return recent;
        }
        @Override public List<Track> search(String query, int limit) {
            searches.add(query);
            if (searchFailure != null) throw searchFailure;
            for (Map.Entry<String, List<Track>> e : byGenre.entrySet()) {
                if (query.equals("genre:" + (e.getKey().contains(" ") ? "\"" + e.getKey() + "\"" : e.getKey()))) {
                    return e.getValue();
                }
            }
            return List.of();
        }
        @Override public List<Album> getArtistAlbums(String artistId) {
            if (libraryFailure != null) throw libraryFailure;
            return albumsByArtist.getOrDefault(artistId, List.of());
        }
        @Override public List<Track> getAlbumTracks(String albumId) {
            return tracksByAlbum.getOrDefault(albumId, List.of());
        }
    }

    private RecommendationEngine engine(FakeMusic music) {
        ToolManager tools = new ToolManager();
        SpotifyTool.allFor(music).forEach(tools::register);
        return new RecommendationEngine(tools, config);
    }

    /** Knows artists a1 and a2; genres rock > indie > jazz > blues > folk > soul, one weight each step. */
    private static ListeningProfile profile() {
        Map<String, Double> hist = new LinkedHashMap<>();
        double w = 0.30;
        for (String g : List.of("rock", "indie", "jazz", "blues", "folk", "soul")) {
            hist.put(g, w);
            w -= 0.04;
        }
        return new ListeningProfile(hist,
                List.of(new Artist("a1", "A1", List.of("rock", "indie")), new Artist("a2", "A2", List.of("jazz"))),
                List.of(track("top0", "a1")), List.of("saved0"), null, Instant.EPOCH);
    }

    private static PlaylistSpec spec(double familiarRatio) {
        return new PlaylistSpec(40, true, familiarRatio, List.of(), "x"); // 40 min = 10 tracks
    }

    private static List<Track> unknownTracks(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> track(prefix + i, "stranger-" + prefix + i)).toList();
    }

    private static List<String> ids(List<Recommendation> recs) {
        return recs.stream().map(r -> r.track().id()).toList();
    }

    /** A fake with 5 library tracks and 20 new tracks by unknown artists in the "rock" genre. */
    private static FakeMusic richMusic() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"), track("top1", "a1"), track("top2", "a2"));
        m.saved = List.of(track("saved0", "a2"));
        m.recent = List.of(track("rec0", "a1"));
        m.byGenre.put("rock", unknownTracks("r", 20));
        return m;
    }

    @Test
    void familiarStrategyFavoursTheLibrary() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new FamiliarStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(1.0), List.of());

        assertEquals(10, recs.size());
        assertEquals(5, recs.stream().filter(r -> r.familiarity() == 1.0).count(), "all five library tracks, then new ones");
    }

    @Test
    void discoveryStrategyFavoursNewArtists() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of());

        assertEquals(10, recs.size());
        assertTrue(recs.stream().allMatch(r -> r.familiarity() == 0.0));
    }

    @Test
    void balancedStrategyMixesByTheRatio() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new BalancedStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.3), List.of());

        assertEquals(10, recs.size());
        assertEquals(3, recs.stream().filter(r -> r.familiarity() == 1.0).count());
    }

    @Test
    void chooseStrategyFollowsTheRatio() {
        RecommendationEngine e = engine(richMusic());
        e.chooseStrategy(0.0);
        e.recommend(profile(), spec(0.0), List.of());
        assertEquals("DiscoveryStrategy", e.lastTrace().strategyName());
        e.chooseStrategy(1.0);
        e.recommend(profile(), spec(1.0), List.of());
        assertEquals("FamiliarStrategy", e.lastTrace().strategyName());
    }

    @Test
    void newPicksNeverIncludeTracksTheUserAlreadyHas() {
        FakeMusic m = richMusic();
        // The genre search also returns a library track, a saved track and a recently played track.
        List<Track> mixed = new ArrayList<>(m.byGenre.get("rock"));
        mixed.addAll(List.of(track("top0", "a1"), track("saved0", "a2"), track("rec0", "a1")));
        m.byGenre.put("rock", mixed);
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of());

        assertEquals(10, recs.size());
        assertTrue(ids(recs).stream().noneMatch(id -> List.of("top0", "saved0", "rec0").contains(id)), ids(recs).toString());
        assertEquals(ids(recs).size(), ids(recs).stream().distinct().count());
    }

    @Test
    void albumTracksOfTopArtistsAreCandidates() {
        FakeMusic m = new FakeMusic();
        m.albumsByArtist.put("a1", List.of(new Album("alb1", "Album", "2020")));
        m.tracksByAlbum.put("alb1", List.of(track("deep1", "a1"), track("deep2", "a1")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of());

        assertEquals(List.of("deep1", "deep2"), ids(recs).stream().sorted().toList());
        assertTrue(recs.stream().allMatch(r -> r.reason().contains("album")), recs.toString());
    }

    @Test
    void blockedArtistsAreRemovedFromEveryPool() {
        FakeMusic m = richMusic();
        RecommendationEngine e = engine(m);
        e.setStrategy(new BalancedStrategy());
        PlaylistSpec blocked = new PlaylistSpec(40, true, 0.5, List.of("a1", "stranger-r0", "stranger-r1"), "x");

        List<Recommendation> recs = e.recommend(profile(), blocked, List.of());

        assertFalse(recs.isEmpty());
        for (Recommendation r : recs) {
            assertFalse(r.track().artistIds().stream().anyMatch(blocked::isBlocked), r.track().toString());
        }
        assertTrue(e.lastTrace().constraintsSatisfied().stream().anyMatch(c -> c.contains("blocked")));
    }

    @Test
    void explicitTracksAreRemovedWhenNotAllowedAndKeptWhenAllowed() {
        FakeMusic m = richMusic();
        List<Track> withExplicit = new ArrayList<>(m.byGenre.get("rock"));
        withExplicit.add(0, explicit("aexp", "stranger-x"));
        m.byGenre.put("rock", withExplicit);
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        PlaylistSpec clean = new PlaylistSpec(40, false, 0.0, List.of(), "x");
        assertFalse(ids(e.recommend(profile(), clean, List.of())).contains("aexp"));
        assertTrue(e.lastTrace().constraintsSatisfied().stream().anyMatch(c -> c.contains("explicit")));

        assertTrue(ids(e.recommend(profile(), spec(0.0), List.of())).contains("aexp"),
                "an explicit track with an unknown artist is a fine discovery when allowed");
    }

    @Test
    void feedbackRaisesAcceptedAndLowersRejectedScores() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new BalancedStrategy());
        Track liked = track("r3", "stranger-r3");
        Track disliked = track("r4", "stranger-r4");
        Track neutral = track("r5", "stranger-r5");
        PlaylistSpec s = spec(0.5);

        e.recommend(profile(), s, List.of());
        double base = e.score(neutral, s);
        assertEquals(base, e.score(liked, s), 1e-9, "same footing before feedback");

        e.recommend(profile(), s, List.of(
                new Feedback("r3", Verdict.ACCEPT, Instant.EPOCH), new Feedback("r4", Verdict.REJECT, Instant.EPOCH)));

        assertEquals(base + config.discoverAcceptBoost(), e.score(liked, s), 1e-9);
        assertEquals(base - config.discoverRejectPenalty(), e.score(disliked, s), 1e-9);
        assertEquals(base, e.score(neutral, s), 1e-9);
    }

    @Test
    void theLatestVerdictOnATrackWins() {
        RecommendationEngine e = engine(richMusic());
        PlaylistSpec s = spec(0.5);
        Track t = track("r3", "stranger-r3");
        e.recommend(profile(), s, List.of());
        double base = e.score(t, s);

        e.recommend(profile(), s, List.of(new Feedback("r3", Verdict.REJECT, Instant.ofEpochSecond(1)),
                new Feedback("r3", Verdict.ACCEPT, Instant.ofEpochSecond(2))));

        assertEquals(base + config.discoverAcceptBoost(), e.score(t, s), 1e-9);
    }

    @Test
    void rejectedTracksRankLastAndAcceptedFirst() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new DiscoveryStrategy());
        List<Feedback> fb = List.of(new Feedback("r7", Verdict.ACCEPT, Instant.EPOCH),
                new Feedback("r0", Verdict.REJECT, Instant.EPOCH));

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), fb);

        assertEquals("r7", recs.get(0).track().id());
        assertFalse(ids(recs).contains("r0"), "a rejected track is outranked by plenty of better candidates");
        assertTrue(recs.get(0).reason().contains("accepted"));
    }

    @Test
    void scoreRewardsTheRequestedFamiliarity() {
        RecommendationEngine e = engine(richMusic());
        e.recommend(profile(), spec(0.9), List.of());
        Track known = track("k", "a1");
        Track stranger = track("s", "nobody");

        assertTrue(e.score(known, spec(0.9)) > e.score(stranger, spec(0.9)));
        assertTrue(e.score(stranger, spec(0.1)) > e.score(known, spec(0.1)));
    }

    @Test
    void tooFewNewCandidatesWidensTheGenresAndLowersNovelty() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.saved = List.of(track("saved0", "a2"));
        m.recent = List.of(track("rec0", "a1"), track("rec1", "a1"), track("rec2", "a1"));
        m.byGenre.put("rock", unknownTracks("g0-", 2));
        m.byGenre.put("indie", unknownTracks("g1-", 2));
        m.byGenre.put("jazz", unknownTracks("g2-", 2));
        m.byGenre.put("blues", unknownTracks("g3-", 2));
        m.byGenre.put("folk", unknownTracks("g4-", 2));
        m.byGenre.put("soul", unknownTracks("g5-", 2));
        RecommendationEngine e = engine(m);
        e.setStrategy(new BalancedStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.3), List.of());

        assertEquals(10, recs.size());
        assertEquals(6, m.searches.size(), "initial 3 genres, then 3 more");
        assertEquals(1, e.lastNotices().size());
        String notice = e.lastNotices().get(0);
        assertTrue(notice.contains("6 genres"), notice);
        assertTrue(notice.contains("70%") && notice.contains("50%"), notice);
    }

    @Test
    void noWideningNoticeWhenTheFirstPassIsEnough() {
        FakeMusic m = richMusic();
        RecommendationEngine e = engine(m);

        e.recommend(profile(), spec(0.5), List.of());

        assertTrue(e.lastNotices().isEmpty(), e.lastNotices().toString());
        assertEquals(3, m.searches.size(), "only the initial genres were searched");
    }

    @Test
    void wideningStopsAfterTheConfiguredStepsAndSaysHowFewWereFound() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", unknownTracks("g0-", 1));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of());

        assertEquals(2, recs.size());
        assertEquals(2, e.lastNotices().size());
        assertTrue(e.lastNotices().get(1).contains("Only 2"), e.lastNotices().toString());
        assertEquals(6, m.searches.size(), "6 genres in all: 3 initial plus two steps capped by what the profile has");
    }

    @Test
    void everyCandidateBlockedReportsItAndAsksToAdjust() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", List.of(track("n0", "a1"), track("n1", "a1")));
        RecommendationEngine e = engine(m);
        PlaylistSpec blocked = new PlaylistSpec(40, true, 0.5, List.of("a1"), "x");

        List<Recommendation> recs = e.recommend(profile(), blocked, List.of());

        assertTrue(recs.isEmpty());
        String notice = e.lastNotices().get(e.lastNotices().size() - 1);
        assertTrue(notice.contains("blocked artists") && notice.contains("Adjust"), notice);
    }

    @Test
    void nothingFoundAtAllSaysSoWithoutBlamingFilters() {
        RecommendationEngine e = engine(new FakeMusic());

        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of());

        assertTrue(recs.isEmpty());
        assertEquals(config.wording("discoverNoResults"), e.lastNotices().get(e.lastNotices().size() - 1));
    }

    @Test
    void aFailedReadIsReportedAndTheRestStillWorks() {
        FakeMusic m = richMusic();
        m.searchFailure = new MusicServiceException("boom");
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(1.0), List.of());

        assertEquals(5, recs.size(), "the library alone still answers");
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("boom")), e.lastNotices().toString());
    }

    @Test
    void ifEverySpotifyReadFailsItThrows() {
        FakeMusic m = richMusic();
        m.libraryFailure = new MusicServiceException("offline");
        m.searchFailure = new MusicServiceException("offline");
        RecommendationEngine e = engine(m);

        MusicServiceException ex = assertThrows(MusicServiceException.class,
                () -> e.recommend(profile(), spec(0.5), List.of()));
        assertTrue(ex.getMessage().contains("offline"));
    }

    @Test
    void everyRecommendationAndTheTraceRecordAReason() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new BalancedStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of());

        DecisionTrace trace = e.lastTrace();
        assertEquals("BalancedStrategy", trace.strategyName());
        assertEquals(recs.size(), trace.reasons().size());
        for (Recommendation r : recs) {
            assertFalse(r.reason().isBlank());
            assertEquals(r.reason(), trace.reasons().get(r.track().id()));
        }
        Recommendation fresh = recs.stream().filter(r -> r.familiarity() == 0.0).findFirst().orElseThrow();
        assertTrue(fresh.reason().contains("new to you") && fresh.reason().contains("rock"), fresh.reason());
        Recommendation known = recs.stream().filter(r -> r.familiarity() == 1.0).findFirst().orElseThrow();
        assertTrue(known.reason().contains("already listen") && known.reason().contains("listening history"), known.reason());
        assertTrue(trace.constraintsSatisfied().stream().anyMatch(c -> c.contains("library")));
    }

    @Test
    void recommendationsAreOrderedByScoreThenTrackId() {
        RecommendationEngine e = engine(richMusic());
        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of());

        for (int i = 1; i < recs.size(); i++) {
            Recommendation a = recs.get(i - 1);
            Recommendation b = recs.get(i);
            assertTrue(a.score() > b.score()
                    || (a.score() == b.score() && a.track().id().compareTo(b.track().id()) < 0));
        }
    }

    @Test
    void theResultDoesNotDependOnTheOrderSpotifyReturnsThings() {
        FakeMusic forward = richMusic();
        FakeMusic backward = richMusic();
        backward.top = reversed(backward.top);
        backward.byGenre.put("rock", reversed(backward.byGenre.get("rock")));
        backward.recent = reversed(backward.recent);

        List<Recommendation> a = engine(forward).recommend(profile(), spec(0.5), List.of());
        List<Recommendation> b = engine(backward).recommend(profile(), spec(0.5), List.of());
        List<Recommendation> again = engine(forward).recommend(profile(), spec(0.5), List.of());

        assertEquals(ids(a), ids(b));
        assertEquals(ids(a), ids(again));
    }

    private static List<Track> reversed(List<Track> tracks) {
        List<Track> copy = new ArrayList<>(tracks);
        Collections.reverse(copy);
        return copy;
    }
}
