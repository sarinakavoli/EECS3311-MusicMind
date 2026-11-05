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
import com.musicmind.domain.SearchQuery;
import com.musicmind.domain.TagTrack;
import com.musicmind.domain.Track;
import com.musicmind.domain.Verdict;
import com.musicmind.music.MusicServiceException;
import com.musicmind.music.StubMusicService;
import com.musicmind.music.TagService;
import com.musicmind.tool.SpotifyTool;
import com.musicmind.tool.ToolManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                if (query.equals(e.getKey())) {
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

    private static ToolManager toolsFor(FakeMusic music) {
        ToolManager tools = new ToolManager();
        SpotifyTool.allFor(music).forEach(tools::register);
        return tools;
    }

    private RecommendationEngine engine(FakeMusic music) {
        return new RecommendationEngine(toolsFor(music), config);
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
    void genreSearchesAreSentAsPlainKeywordsNeverAsAGenreFilter() {
        FakeMusic music = richMusic();
        RecommendationEngine e = engine(music);
        e.setStrategy(new FamiliarStrategy());

        e.recommend(profile(), spec(1.0), List.of());

        assertFalse(music.searches.isEmpty());
        assertTrue(music.searches.stream().noneMatch(q -> q.contains("genre:")), music.searches.toString());
        assertTrue(music.searches.contains("rock"), music.searches.toString());
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

        List<Recommendation> recs = e.recommend(profile(), spec(0.3), List.of(), false);

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

        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of(), false);

        assertEquals(1, recs.size());
        assertEquals(2, e.lastNotices().size());
        assertTrue(e.lastNotices().get(1).contains("Only 1"), e.lastNotices().toString());
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
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("6 ")), e.lastNotices().toString());
    }

    @Test
    void failedReadsAreReportedAsACountWithNoTechnicalDetail() {
        FakeMusic m = richMusic();
        m.searchFailure = new MusicServiceException(
                "Spotify returned HTTP 400 for https://api.spotify.com/v1/search: {\"error\":{\"message\":\"Invalid limit\"}}");
        RecommendationEngine e = engine(m);

        e.recommend(profile(), spec(1.0), List.of());

        String notice = e.lastNotices().stream().filter(n -> n.contains("Spotify")).findFirst().orElseThrow();
        assertTrue(notice.contains("6 "), "three genre searches failed, three more after widening: " + notice);
        for (String technical : List.of("HTTP", "400", "http", "{", "Invalid limit")) {
            assertFalse(notice.contains(technical), notice);
        }
    }

    @Test
    void ifEverySpotifyReadFailsItThrows() {
        FakeMusic m = richMusic();
        m.libraryFailure = new MusicServiceException("offline");
        m.searchFailure = new MusicServiceException("offline");
        RecommendationEngine e = engine(m);

        MusicServiceException ex = assertThrows(MusicServiceException.class,
                () -> e.recommend(profile(), spec(0.5), List.of()));
        for (String technical : List.of("offline", "HTTP", "http", "{")) {
            assertFalse(ex.getMessage().contains(technical), ex.getMessage());
        }
        assertTrue(ex.getMessage().contains("(11 reads"), "3 library + 2 album + 6 search reads: " + ex.getMessage());
    }

    @Test
    void allReadsFailingNeverLeaksStatusUrlOrJson() {
        FakeMusic m = richMusic();
        String raw = "Spotify returned HTTP 400 for https://api.spotify.com/v1/search: {\"error\":{\"message\":\"Invalid limit\"}}";
        m.libraryFailure = new MusicServiceException(raw);
        m.searchFailure = new MusicServiceException(raw);
        RecommendationEngine e = engine(m);

        MusicServiceException ex = assertThrows(MusicServiceException.class,
                () -> e.recommend(profile(), spec(0.5), List.of()));
        for (String technical : List.of("HTTP", "400", "https", "{", "Invalid limit")) {
            assertFalse(ex.getMessage().contains(technical), ex.getMessage());
        }
    }

    @Test
    void aDiscoverRunDoesNotChangeALaterThreeArgumentRecommend() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new FamiliarStrategy());

        List<Recommendation> discovery = e.recommend(profile(), spec(1.0), List.of(), false);
        List<Recommendation> later = e.recommend(profile(), spec(1.0), List.of());

        assertTrue(discovery.stream().noneMatch(r -> r.familiarity() == 1.0 && r.reason().contains("listening history")));
        assertEquals(5, later.stream().filter(r -> r.reason().contains("listening history")).count());
    }

    @Test
    void knownTrackIdsAreExcludedEvenWhenTheLibraryReadFailed() {
        FakeMusic m = new FakeMusic();
        m.libraryFailure = new MusicServiceException("offline");
        m.byGenre.put("rock", List.of(track("top0", "a1"), track("saved0", "a2"), track("fresh", "stranger")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        assertEquals(List.of("fresh"), ids(e.recommend(profile(), spec(0.0), List.of(), false)));
    }

    @Test
    void theLibraryConstraintIsOnlyListedWhenLibraryTracksAreExcluded() {
        RecommendationEngine e = engine(richMusic());

        e.recommend(profile(), spec(0.5), List.of(), false);
        assertTrue(e.lastTrace().constraintsSatisfied().stream().anyMatch(c -> c.contains("library")));
        e.recommend(profile(), spec(0.5), List.of(), true);
        assertTrue(e.lastTrace().constraintsSatisfied().stream().noneMatch(c -> c.contains("library")));
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
        assertTrue(trace.constraintsSatisfied().stream().noneMatch(c -> c.contains("library")));
    }

    @Test
    void recommendationsAreOrderedByScore() {
        RecommendationEngine e = engine(richMusic());
        List<Recommendation> recs = e.recommend(profile(), spec(0.5), List.of());

        for (int i = 1; i < recs.size(); i++) {
            Recommendation a = recs.get(i - 1);
            Recommendation b = recs.get(i);
            assertTrue(a.score() >= b.score());
        }
    }

    @Test
    void theSameInputGivesTheSameResult() {
        List<Recommendation> a = engine(richMusic()).recommend(profile(), spec(0.5), List.of());
        List<Recommendation> b = engine(richMusic()).recommend(profile(), spec(0.5), List.of());

        assertEquals(ids(a), ids(b));
    }

    @Test
    void equalScoresKeepSourceOrderNotTrackIdOrder() {
        FakeMusic m = new FakeMusic();
        m.byGenre.put("rock", List.of(track("z", "s1"), track("m", "s2"), track("a", "s3")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of());

        assertEquals(List.of("z", "m", "a"), ids(recs));
    }

    @Test
    void whenLibraryTracksAreNotAllowedNoStrategyRecommendsOne() {
        for (var strategy : List.of(new FamiliarStrategy(), new DiscoveryStrategy(), new BalancedStrategy())) {
            FakeMusic m = richMusic();
            List<Track> mixed = new ArrayList<>(m.byGenre.get("rock"));
            mixed.addAll(List.of(track("top0", "a1"), track("saved0", "a2"), track("rec0", "a1")));
            m.byGenre.put("rock", mixed);
            m.albumsByArtist.put("a1", List.of(new Album("alb1", "Album", "2020")));
            m.tracksByAlbum.put("alb1", List.of(track("top1", "a1"), track("deep1", "a1"), track("deep2", "a1")));
            RecommendationEngine e = engine(m);
            e.setStrategy(strategy);

            for (double ratio : List.of(0.0, 0.5, 1.0)) {
                List<Recommendation> recs = e.recommend(profile(), spec(ratio), List.of(), false);
                String label = strategy.getClass().getSimpleName() + " at " + ratio + ": " + ids(recs);
                assertFalse(recs.isEmpty(), label);
                assertTrue(ids(recs).stream().noneMatch(id -> List.of("top0", "top1", "top2", "saved0", "rec0").contains(id)), label);
                assertTrue(recs.stream().noneMatch(r -> r.reason().contains("listening history")), label);
            }
        }
    }

    @Test
    void libraryTracksCanBeSelectedWhenTheSettingAllowsIt() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new FamiliarStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(1.0), List.of(), true);

        assertEquals(5, recs.stream().filter(r -> r.reason().contains("listening history")).count());
    }

    @Test
    void newTracksByKnownArtistsAreFamiliarWithATrueReason() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.albumsByArtist.put("a1", List.of(new Album("alb1", "Album", "2020")));
        m.tracksByAlbum.put("alb1", List.of(track("top0", "a1"), track("deep1", "a1"), track("deep2", "a1")));
        m.byGenre.put("rock", List.of(track("n1", "stranger")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new FamiliarStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(1.0), List.of(), false);

        assertEquals(List.of("deep1", "deep2", "n1"), ids(recs));
        for (Recommendation r : recs.subList(0, 2)) {
            assertEquals(1.0, r.familiarity());
            assertTrue(r.reason().contains("already listen") && r.reason().contains("album"), r.reason());
            assertFalse(r.reason().contains("listening history"), r.reason());
        }
        assertEquals(0.0, recs.get(2).familiarity());
    }

    @Test
    void widenedRunsNeverReplaceTheStrategyTheCallerSet() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", unknownTracks("g0-", 1));
        RecommendationEngine e = engine(m);
        FamiliarStrategy mine = new FamiliarStrategy();
        e.setStrategy(mine);

        e.recommend(profile(), spec(0.5), List.of(), false); // too few: widens, ratio rises
        assertFalse(e.lastNotices().isEmpty(), "widening happened");
        e.recommend(profile(), spec(0.5), List.of(), false);

        assertEquals("FamiliarStrategy", e.lastTrace().strategyName());
        try {
            java.lang.reflect.Field f = RecommendationEngine.class.getDeclaredField("strategy");
            f.setAccessible(true);
            assertTrue(f.get(e) == mine, "the field still holds the caller's strategy");
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void engineAndStrategiesAgreeOnAnOverriddenTunable() {
        Config eight = config.withTunables(Map.of("strategy.avgTrackMinutes", "8"));
        ToolManager tools = new ToolManager();
        SpotifyTool.allFor(richMusic()).forEach(tools::register);
        RecommendationEngine e = new RecommendationEngine(tools, eight);

        List<Recommendation> defaultStrategy = e.recommend(profile(), spec(0.5), List.of());
        e.chooseStrategy(0.0);
        List<Recommendation> chosen = e.recommend(profile(), spec(0.0), List.of());

        assertEquals(5, defaultStrategy.size(), "40 min / 8 = 5 tracks");
        assertEquals(5, chosen.size());
        assertTrue(e.lastNotices().isEmpty(), "5 picks are enough when 5 are wanted: " + e.lastNotices());
    }

    @Test
    void seedGenresAreSearchedFirstAndTheReasonSaysTheUserAskedForThem() {
        FakeMusic m = richMusic();
        m.byGenre.put("lo-fi", unknownTracks("l", 4));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of(), true, List.of("lo-fi"));

        assertEquals("lo-fi", m.searches.get(0));
        Recommendation fromSeed = recs.stream().filter(r -> r.track().id().startsWith("l")).findFirst().orElseThrow();
        assertTrue(fromSeed.reason().contains("lo-fi") && fromSeed.reason().contains("you asked for"), fromSeed.reason());
        assertFalse(fromSeed.reason().contains("your \"lo-fi\""), fromSeed.reason());
        Recommendation own = recs.stream().filter(r -> r.track().id().startsWith("r")).findFirst().orElseThrow();
        assertTrue(own.reason().contains("your \"rock\" genre"), own.reason());
    }

    @Test
    void aRetrievedPoolIsReusedWithoutReadingSpotifyAgain() {
        FakeMusic m = richMusic();
        RecommendationEngine e = engine(m);

        Retrieval retrieval = e.retrieve(profile(), spec(0.5), true, List.of());
        int searchesAfterRetrieval = m.searches.size();
        CandidatePool pool = retrieval.pool();
        List<Recommendation> recs = e.recommend(retrieval, spec(0.5), List.of());

        assertEquals(5, pool.familiar().size());
        assertTrue(pool.novel().size() >= 20);
        assertFalse(recs.isEmpty());
        assertEquals(searchesAfterRetrieval, m.searches.size());
        assertFalse(retrieval.allReadsFailed());
    }

    @Test
    void aRetrievalWhoseReadsAllFailedSaysSo() {
        FakeMusic m = new FakeMusic();
        m.libraryFailure = new MusicServiceException("offline");
        m.searchFailure = new MusicServiceException("offline");
        assertTrue(engine(m).retrieve(profile(), spec(0.5), true, List.of()).allReadsFailed());
    }

    // ---- F5: the familiar share means known artists, the rest must be new artists (Task 22) ----

    /** Library of 5, five more tracks by known artists on an album, and 20 rock tracks by strangers. */
    private static FakeMusic knownArtistAlbumMusic() {
        FakeMusic m = richMusic();
        m.albumsByArtist.put("a1", List.of(new Album("alb1", "Album", "2020")));
        m.tracksByAlbum.put("alb1", List.of(track("deep1", "a1"), track("deep2", "a1"), track("deep3", "a1"),
                track("deep4", "a1"), track("deep5", "a1")));
        return m;
    }

    private static long unknownArtistCount(List<Recommendation> recs) {
        return recs.stream().filter(r -> r.familiarity() < 1.0).count();
    }

    @Test
    void aSeventyPercentFamiliarPlaylistHasAboutThirtyPercentTracksByNewArtists() {
        RecommendationEngine e = engine(knownArtistAlbumMusic());

        List<Recommendation> recs = e.recommend(profile(), spec(0.7), List.of(), true);

        assertEquals(10, recs.size());
        assertEquals(3, unknownArtistCount(recs), ids(recs).toString());
        assertTrue(e.lastNotices().isEmpty(), e.lastNotices().toString());
    }

    @Test
    void aKnownArtistsUnheardTrackIsFamiliarNotNovelWhenLibraryTracksAreAllowed() {
        RecommendationEngine e = engine(knownArtistAlbumMusic());

        CandidatePool pool = e.retrieve(profile(), spec(0.7), true, List.of()).pool();

        List<String> familiar = pool.familiar().stream().map(Track::id).toList();
        List<String> novel = pool.novel().stream().map(Track::id).toList();
        assertTrue(familiar.containsAll(List.of("deep1", "deep5", "top0")), familiar.toString());
        assertTrue(novel.stream().noneMatch(id -> id.startsWith("deep")), novel.toString());
        assertEquals(20, novel.size());
    }

    @Test
    void seedGenreMatchesRankAboveNonMatchesInsideTheFamiliarPool() {
        FakeMusic m = knownArtistAlbumMusic();
        m.albumsByArtist.put("a2", List.of(new Album("alb2", "Jazz", "2021")));
        m.tracksByAlbum.put("alb2", List.of(track("j1", "a2"), track("j2", "a2")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new FamiliarStrategy());
        PlaylistSpec five = new PlaylistSpec(20, true, 1.0, List.of(), "x");

        List<Recommendation> recs = e.recommend(profile(), five, List.of(), true, List.of("jazz"));

        // The jazz artist a2 has top2, saved0, j1 and j2; a1 (rock) has more tracks and a higher taste affinity.
        assertTrue(ids(recs).containsAll(List.of("top2", "saved0", "j1", "j2")), ids(recs).toString());
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("4 of 5") && n.contains("jazz")),
                e.lastNotices().toString());
    }

    @Test
    void seedGenreTracksByNewArtistsAreTheOnesChosenForTheNewShare() {
        FakeMusic m = knownArtistAlbumMusic();
        m.byGenre.clear();
        m.byGenre.put("rock", unknownTracks("r", 20));
        m.byGenre.put("lo-fi", unknownTracks("l", 4));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(0.7), List.of(), true, List.of("lo-fi"));

        List<String> fresh = recs.stream().filter(r -> r.familiarity() < 1.0).map(r -> r.track().id()).toList();
        // The rock library and album songs do not fit "lo-fi", so all 10 are new: the 4 lo-fi tracks first.
        assertEquals(10, fresh.size());
        assertTrue(fresh.containsAll(List.of("l0", "l1", "l2", "l3")), fresh.toString());
    }

    @Test
    void tooFewNewArtistsKeepsTheOneThatExistsAndSaysSoInsteadOfFillingSilently() {
        FakeMusic m = knownArtistAlbumMusic();
        m.byGenre.clear();
        m.byGenre.put("rock", unknownTracks("r", 1));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(0.7), List.of(), true);

        assertEquals(10, recs.size());
        assertEquals(1, unknownArtistCount(recs), ids(recs) + " " + e.lastNotices());
        assertTrue(ids(recs).contains("r0"));
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("1 of 3") && n.contains("new to you")),
                e.lastNotices().toString());
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("novelty target")), e.lastNotices().toString());
    }

    @Test
    void f5ReadsMoreGenresBeforeGivingUpOnNewArtists() {
        FakeMusic m = knownArtistAlbumMusic();
        m.byGenre.clear();
        m.byGenre.put("rock", unknownTracks("r", 1));
        m.byGenre.put("soul", unknownTracks("s", 4)); // the sixth genre, read only after widening
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(profile(), spec(0.7), List.of(), true);

        assertEquals(6, m.searches.size(), m.searches.toString());
        assertEquals(3, unknownArtistCount(recs), ids(recs).toString());
        assertTrue(e.lastNotices().isEmpty(), e.lastNotices().toString());
    }

    @Test
    void f4WideningStillLowersTheNoveltyTarget() {
        FakeMusic m = new FakeMusic();
        m.byGenre.put("rock", unknownTracks("g0-", 1));
        m.byGenre.put("indie", unknownTracks("g1-", 1));
        m.byGenre.put("jazz", unknownTracks("g2-", 1));
        m.byGenre.put("blues", unknownTracks("g3-", 1));
        m.byGenre.put("folk", unknownTracks("g4-", 1));
        m.byGenre.put("soul", unknownTracks("g5-", 1));
        m.albumsByArtist.put("a1", List.of(new Album("alb1", "Album", "2020")));
        m.tracksByAlbum.put("alb1", IntStream.range(0, 8).mapToObj(i -> track("deep" + i, "a1")).toList());
        RecommendationEngine e = engine(m);

        e.recommend(profile(), spec(0.3), List.of(), false);

        assertEquals(6, m.searches.size());
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("novelty target")), e.lastNotices().toString());
    }

    @Test
    void aShortfallOfNewArtistsIsReportedWhenNothingCouldBeWidened() {
        FakeMusic m = knownArtistAlbumMusic();
        m.byGenre.clear();
        m.byGenre.put("rock", unknownTracks("r", 1));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config.withTunables(Map.of("discover.widenSteps", "0")));

        List<Recommendation> recs = e.recommend(profile(), spec(0.7), List.of(), true);

        assertEquals(1, unknownArtistCount(recs), ids(recs) + " " + e.lastNotices());
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("1 of 3") && n.contains("new to you")),
                e.lastNotices().toString());
    }

    @Test
    void tooFewMoodMatchingTracksSaysSo() {
        FakeMusic m = knownArtistAlbumMusic();
        RecommendationEngine e = engine(m);

        e.recommend(profile(), spec(0.7), List.of(), true, List.of("lo-fi"));

        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("lo-fi") && n.contains("match")),
                e.lastNotices().toString());
    }

    @Test
    void discoveryStillExcludesTheLibraryAndTheNoveltyMeaningIsUnchanged() {
        RecommendationEngine e = engine(knownArtistAlbumMusic());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of(), false);

        assertTrue(ids(recs).stream().noneMatch(id -> List.of("top0", "top1", "top2", "saved0", "rec0").contains(id)));
        assertEquals(10, unknownArtistCount(recs));
    }

    // ---- Task 24: requested genres in the trace, the leading-genre match, the bare-genre-title penalty ----

    @Test
    void theTraceRecordsTheGenresTheGoalRequestedAndNoneWhenThereAreNone() {
        FakeMusic m = richMusic();
        m.byGenre.put("lo-fi", unknownTracks("l", 4));
        RecommendationEngine e = engine(m);

        e.recommend(profile(), spec(0.0), List.of(), true, List.of("lo-fi", "dance"));
        assertEquals(List.of("lo-fi", "dance"), e.lastTrace().requestedGenres());

        e.recommend(profile(), spec(0.0), List.of(), true);
        assertEquals(List.of(), e.lastTrace().requestedGenres());
    }

    @Test
    void anArtistMatchesARequestedGenreOnlyThroughItsLeadingGenre() {
        // a1's genres are [rock, indie]: asking for indie must not count a1's tracks as matches.
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new FamiliarStrategy());

        e.recommend(profile(), spec(1.0), List.of(), true, List.of("indie"));
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("Only 0 of")), e.lastNotices().toString());

        // a2's only genre is jazz and top2 and saved0 are a2's: exactly those two match.
        e.recommend(profile(), spec(1.0), List.of(), true, List.of("jazz"));
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("Only 2 of")), e.lastNotices().toString());
    }

    private static Track titled(String id, String title) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of("stranger-" + id));
    }

    @Test
    void aGenreResultTitledOnlyWithTheGenreRanksBelowTheOthersFromThatSearchButStaysListed() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", List.of(titled("bare", "Rock"), titled("n1", "Night Drive"), titled("n2", "Open Road")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of(), false);

        assertEquals(List.of("n1", "n2", "bare"), ids(recs));
        double bare = recs.get(2).score();
        assertTrue(bare < recs.get(1).score(), "a small penalty, not a filter");
    }

    @Test
    void theBareTitlePenaltyIgnoresCaseAndPunctuationAndOnlyAppliesToTheSearchedGenre() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", List.of(titled("bare", "ROCK!"), titled("blues", "Blues"), titled("n1", "Night Drive")));
        RecommendationEngine e = engine(m);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(profile(), spec(0.0), List.of(), false);

        // "Blues" is not the genre it was found for, so only the "ROCK!" track is penalised.
        assertEquals(List.of("blues", "n1", "bare"), ids(recs));
    }

    @Test
    void theBareTitlePenaltyIsATunable() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("rock", List.of(titled("bare", "Rock"), titled("n1", "Night Drive")));
        Config off = config.withTunables(Map.of("discover.weight.genreTitlePenalty", "0"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), off);
        e.setStrategy(new DiscoveryStrategy());

        assertEquals(List.of("bare", "n1"), ids(e.recommend(profile(), spec(0.0), List.of(), false)));
        assertTrue(config.discoverGenreTitlePenalty() > 0);
    }

    @Test
    void aBlockedTrackIsNeverRecommended() {
        RecommendationEngine e = engine(richMusic());
        e.setStrategy(new BalancedStrategy());
        String picked = e.recommend(profile(), spec(0.3), List.of()).get(0).track().id();

        List<Recommendation> recs = e.recommend(profile(), spec(0.3).withBlockedTracks(List.of(picked)), List.of());

        assertTrue(recs.stream().noneMatch(r -> r.track().id().equals(picked)));
    }

    /** Seed genre "folk" (0.467); a2 (jazz, 0.733) and a3 (soul, 0.333) are both known artists, so fit matches. */
    private RecommendationEngine seededEngine(Config cfg) {
        ListeningProfile p = new ListeningProfile(profile().genreHistogram(),
                List.of(new Artist("a2", "A2", List.of("jazz")), new Artist("a3", "A3", List.of("soul"))),
                List.of(track("top0", "a2")), List.of("saved0"), null, Instant.EPOCH);
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a2"));
        m.byGenre.put("folk", List.of(track("liked", "a2"), track("plain", "a3")));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), cfg);
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(p, spec(0.0), List.of(), false, List.of("folk"));
        return e;
    }

    @Test
    void score_seedTracksDifferByTaste() {
        RecommendationEngine e = seededEngine(config);
        double plain = e.score(track("plain", "a3"), spec(0.0));
        double liked = e.score(track("liked", "a2"), spec(0.0));
        assertTrue(liked > plain, "the better-liked artist scores higher within one seed genre");
    }

    @Test
    void score_seedGenreStillBeatsNonSeed() {
        // folk and blues weigh the same in the taste profile; only folk was asked for.
        Map<String, Double> hist = new LinkedHashMap<>();
        hist.put("rock", 0.30);
        hist.put("folk", 0.15);
        hist.put("blues", 0.15);
        ListeningProfile p = new ListeningProfile(hist, List.of(), List.of(track("top0", "a1")),
                List.of("saved0"), null, Instant.EPOCH);
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("folk", List.of(track("seed", "stranger-seed")));
        m.byGenre.put("blues", List.of(track("other", "stranger-other")));
        m.byGenre.put("rock", List.of(track("rk", "stranger-rk")));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config.withTunables(Map.of("discover.initialGenres", "4")));
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(p, spec(0.0), List.of(), false, List.of("folk"));

        assertTrue(e.score(track("seed", "stranger-seed"), spec(0.0))
                > e.score(track("other", "stranger-other"), spec(0.0)));
    }

    private static ListeningProfile rockBluesProfile() {
        Map<String, Double> hist = new LinkedHashMap<>();
        hist.put("rock", 0.30);
        hist.put("blues", 0.18); // affinity 0.6
        return new ListeningProfile(hist, List.of(), List.of(track("top0", "a1")),
                List.of("saved0"), null, Instant.EPOCH);
    }

    @Test
    void score_zeroTasteGoalTrackBeatsModerateTasteNonGoalTrack() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("polka", List.of(track("goal", "stranger-goal"))); // asked for, no taste at all
        m.byGenre.put("blues", List.of(track("other", "stranger-other"))); // not asked for, affinity 0.6
        m.byGenre.put("rock", List.of(track("rk", "stranger-rk")));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config);
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(rockBluesProfile(), spec(0.0), List.of(), false, List.of("polka"));

        assertTrue(e.score(track("goal", "stranger-goal"), spec(0.0))
                > e.score(track("other", "stranger-other"), spec(0.0)));
    }

    @Test
    void score_keywordTrackGetsTheSameFloorAsSeed() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("polka", List.of(track("seed", "stranger-seed")));
        m.byGenre.put("blues", List.of(track("other", "stranger-other")));
        m.byGenre.put("rock", List.of(track("rk", "stranger-rk")));
        // keyword search: the fake matches the query text exactly, as byKeywords builds it
        String q = new SearchQuery("zumba", null, Map.of(), 1.0).toSpotifyQuery();
        m.byGenre.put(q, List.of(track("kw", "stranger-kw")));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config);
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(e.retrieve(rockBluesProfile(), spec(0.0), false, List.of("polka"), List.of("zumba")),
                spec(0.0), List.of());

        double seed = e.score(track("seed", "stranger-seed"), spec(0.0));
        double kw = e.score(track("kw", "stranger-kw"), spec(0.0));
        assertEquals(seed, kw, 1e-9, "no taste either way, so the floor alone decides");
        assertTrue(kw > e.score(track("other", "stranger-other"), spec(0.0)));
    }

    @Test
    void score_keywordStockTitleRanksBelowOtherKeywordTrack() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        String q = new SearchQuery("gym workout", null, Map.of(), 1.0).toSpotifyQuery();
        Track stock = new Track("stock", "spotify:track:stock", "Workout Music", 1000, false, List.of("stranger-stock"));
        Track real = new Track("real", "spotify:track:real", "Eye of the Tiger", 1000, false, List.of("stranger-real"));
        m.byGenre.put(q, List.of(stock, real));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config);
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(e.retrieve(rockBluesProfile(), spec(0.0), false, List.of(), List.of("gym workout")),
                spec(0.0), List.of());

        double penalty = config.discoverKeywordTitlePenalty();
        assertEquals(0.3, penalty, 1e-9);
        assertEquals(penalty, e.score(real, spec(0.0)) - e.score(stock, spec(0.0)), 1e-9);
        assertTrue(e.score(stock, spec(0.0)) > 0, "a nudge, not a filter");
    }

    @Test
    void score_acceptedStillBeatsRejected() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("top0", "a1"));
        m.byGenre.put("folk", List.of(track("good", "stranger-good"), track("bad", "stranger-bad")));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config);
        e.setStrategy(new DiscoveryStrategy());
        e.recommend(profile(), spec(0.0), List.of(
                new Feedback("good", Verdict.ACCEPT, Instant.EPOCH), new Feedback("bad", Verdict.REJECT, Instant.EPOCH)),
                false, List.of("folk"));
        assertTrue(e.score(track("good", "stranger-good"), spec(0.0)) > e.score(track("bad", "stranger-bad"), spec(0.0)));
    }

    // ---- Create: library songs count as familiar only when they fit the goal ----

    private static ListeningProfile goalProfile() {
        return new ListeningProfile(new LinkedHashMap<>(Map.of("pop", 0.5, "soul", 0.4)),
                List.of(new Artist("popA", "PopA", List.of("pop", "dance pop")),
                        new Artist("soulA", "SoulA", List.of("soul", "quiet storm"))),
                List.of(track("p0", "popA")), List.of(), null, Instant.EPOCH);
    }

    private static FakeMusic slowDanceMusic(int popLibrary, int soulLibrary) {
        FakeMusic m = new FakeMusic();
        List<Track> top = new ArrayList<>();
        for (int i = 0; i < popLibrary; i++) top.add(track("p" + i, "popA"));
        for (int i = 0; i < soulLibrary; i++) top.add(track("s" + i, "soulA"));
        m.top = top;
        m.byGenre.put("soul", unknownTracks("n", 20));
        return m;
    }

    private static final List<String> SLOW_DANCE = List.of("soul", "r&b", "ballad");

    @Test
    void create_libraryTrackOutsideGoalGenres_notPicked() {
        RecommendationEngine e = engine(slowDanceMusic(6, 0));

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.4), List.of(), true, SLOW_DANCE);

        assertEquals(10, recs.size(), "new goal tracks fill the length");
        assertTrue(ids(recs).stream().noneMatch(id -> id.startsWith("p")), ids(recs).toString());
    }

    @Test
    void create_goalFitLibraryTrack_picked() {
        RecommendationEngine e = engine(slowDanceMusic(6, 4));

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.4), List.of(), true, SLOW_DANCE);

        assertEquals(10, recs.size());
        assertEquals(4, ids(recs).stream().filter(id -> id.startsWith("s")).count(), ids(recs).toString());
        assertTrue(ids(recs).stream().noneMatch(id -> id.startsWith("p")), ids(recs).toString());
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("match your goal")), e.lastNotices().toString());
    }

    @Test
    void create_tooFewFitLibraryTracks_fillsWithNewAndNotices() {
        RecommendationEngine e = engine(slowDanceMusic(6, 2));

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.4), List.of(), true, SLOW_DANCE);

        assertEquals(10, recs.size());
        assertEquals(2, ids(recs).stream().filter(id -> id.startsWith("s")).count(), ids(recs).toString());
        assertEquals(8, ids(recs).stream().filter(id -> id.startsWith("n")).count());
        assertTrue(e.lastNotices().stream().anyMatch(n -> n.contains("2 of 4") && n.contains("match your goal")),
                e.lastNotices().toString());
    }

    @Test
    void create_libraryTrackFoundByGoalKeywordSearch_isFit() {
        FakeMusic m = slowDanceMusic(6, 0);
        String q = new SearchQuery("slow dance", null, Map.of(), 1.0).toSpotifyQuery();
        m.byGenre.put(q, List.of(track("p1", "popA")));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE,
                List.of("slow dance")), spec(0.4), List.of());

        assertTrue(ids(recs).contains("p1"), ids(recs).toString());
        assertEquals(1, ids(recs).stream().filter(id -> id.startsWith("p")).count(), ids(recs).toString());
    }

    @Test
    void create_cachedMusicBrainzGenresMakeAnUnknownArtistsLibraryTrackFit() {
        FakeMusic m = slowDanceMusic(0, 0);
        m.top = List.of(track("c0", "cachedA"), track("c1", "cachedA"), track("c2", "cachedA"), track("c3", "cachedA"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config,
                id -> id.equals("cachedA") ? Optional.of(List.of("Soul")) : Optional.empty());

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.4), List.of(), true, SLOW_DANCE);

        assertEquals(4, ids(recs).stream().filter(id -> id.startsWith("c")).count(), ids(recs).toString());
    }

    @Test
    void discover_unchanged_libraryRules() {
        // No seed genres and no search terms: the library's genres are not checked against any goal.
        RecommendationEngine e = engine(slowDanceMusic(6, 0));
        e.setStrategy(new FamiliarStrategy());

        List<Recommendation> recs = e.recommend(goalProfile(), spec(1.0), List.of(), true, List.of());

        assertEquals(6, ids(recs).stream().filter(id -> id.startsWith("p")).count(), ids(recs).toString());
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("match your goal")));
    }

    @Test
    void create_topArtistsAlbumTrackOutsideGoalGenres_notPicked_matchingOneIs() {
        FakeMusic m = slowDanceMusic(0, 0);
        m.top = List.of();
        m.albumsByArtist.put("popA", List.of(new Album("albP", "P", "2020")));
        m.albumsByArtist.put("soulA", List.of(new Album("albS", "S", "2020")));
        m.tracksByAlbum.put("albP", List.of(track("pa0", "popA"), track("pa1", "popA"), track("pa2", "popA")));
        m.tracksByAlbum.put("albS", List.of(track("sa0", "soulA"), track("sa1", "soulA")));
        RecommendationEngine e = engine(m);

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.4), List.of(), true, SLOW_DANCE);

        assertTrue(ids(recs).stream().noneMatch(id -> id.startsWith("pa")), ids(recs).toString());
        assertEquals(2, ids(recs).stream().filter(id -> id.startsWith("sa")).count(), ids(recs).toString());
        assertEquals(10, recs.size());
    }

    // ---- Live finding: a long artist tag list must not make an artist fit every goal ----

    private static final List<String> STUDYING = List.of("ambient", "classical", "instrumental", "lo-fi");
    // MusicBrainz-style tag list of a grunge band: the seed words are far down the list.
    private static final List<String> GRUNGE_TAGS = List.of("grunge", "alternative rock", "rock", "noise rock",
            "acoustic rock", "sludge metal", "punk", "ambient", "experimental", "instrumental");

    private static FakeMusic grungeLibrary() {
        FakeMusic m = new FakeMusic();
        m.top = List.of(track("g0", "grungeA"), track("g1", "grungeA"), track("g2", "grungeA"), track("g3", "grungeA"));
        m.byGenre.put("ambient", unknownTracks("n", 20));
        return m;
    }

    @Test
    void create_profileGenresWithASeedWordFarDownTheList_doNotMakeALibraryTrackFit() {
        ListeningProfile p = new ListeningProfile(new LinkedHashMap<>(Map.of("grunge", 0.9)),
                List.of(new Artist("grungeA", "GrungeA", GRUNGE_TAGS)), List.of(), List.of(), null, Instant.EPOCH);
        RecommendationEngine e = engine(grungeLibrary());

        List<Recommendation> recs = e.recommend(p, spec(0.5), List.of(), true, STUDYING);

        assertTrue(ids(recs).stream().noneMatch(id -> id.startsWith("g")), ids(recs).toString());
    }

    @Test
    void create_cachedGenresWithASeedWordFarDownTheList_doNotMakeALibraryTrackFit() {
        RecommendationEngine e = new RecommendationEngine(toolsFor(grungeLibrary()), config,
                id -> id.equals("grungeA") ? Optional.of(GRUNGE_TAGS) : Optional.empty());

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.5), List.of(), true, STUDYING);

        assertTrue(ids(recs).stream().noneMatch(id -> id.startsWith("g")), ids(recs).toString());
    }

    @Test
    void create_aSeedGenreAmongTheArtistsLeadingGenres_stillFits() {
        FakeMusic m = grungeLibrary();
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config,
                id -> id.equals("grungeA") ? Optional.of(List.of("rock", "lo-fi", "grunge")) : Optional.empty());

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.5), List.of(), true, STUDYING);

        assertEquals(4, ids(recs).stream().filter(id -> id.startsWith("g")).count(), ids(recs).toString());
    }

    // ---- Last.fm tag songs ----

    private final Config withKey = Config.of(Map.of("LASTFM_API_KEY", "test-key"));

    private static Track named(String id, String title, String artistId, String artistName) {
        return new Track(id, "spotify:track:" + id, title, 1000, false, List.of(artistId), List.of(artistName));
    }

    private static String tagQuery(String title, String artist) {
        return "track:\"" + title + "\" artist:\"" + artist + "\"";
    }

    /** Spotify with a soul genre of strangers, a library, and exact answers for four Last.fm songs. */
    private static FakeMusic tagMusic() {
        FakeMusic m = slowDanceMusic(6, 0);
        m.byGenre.put(tagQuery("Tag One", "Singer One"), List.of(named("tg1", "Tag One", "singer1", "Singer One")));
        m.byGenre.put(tagQuery("Tag Two", "Singer Two"), List.of(named("tg2", "Tag Two", "singer2", "Singer Two")));
        m.byGenre.put(tagQuery("Tag Blocked", "Singer Blocked"), List.of(named("tg3", "Tag Blocked", "blockedA", "Singer Blocked")));
        m.byGenre.put(tagQuery("Tag Muted", "Singer Muted"), List.of(named("tg4", "Tag Muted", "singer4", "Singer Muted")));
        return m;
    }

    private static final TagService FOUR = (tag, limit) -> List.of(new TagTrack("Tag One", "Singer One"),
            new TagTrack("Tag Two", "Singer Two"), new TagTrack("Tag Blocked", "Singer Blocked"),
            new TagTrack("Tag Muted", "Singer Muted"));

    @Test
    void create_tagTracksRankFirstAndRespectBlocks() {
        FakeMusic m = tagMusic();
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), withKey, ArtistGenreCache.NONE, FOUR);
        PlaylistSpec spec = new PlaylistSpec(40, true, 0.4, List.of("blockedA"), List.of("tg4"), "x");

        List<Recommendation> recs = e.recommend(e.retrieve(goalProfile(), spec, true, SLOW_DANCE, List.of(),
                List.of("slow dance")), spec, List.of());

        List<String> ids = ids(recs);
        assertEquals(10, ids.size(), ids.toString());
        assertEquals(java.util.Set.of("tg1", "tg2"), new java.util.HashSet<>(ids.subList(0, 2)), ids.toString());
        assertFalse(ids.contains("tg3"), "a blocked artist's Last.fm song is dropped");
        assertFalse(ids.contains("tg4"), "a blocked song is dropped");
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("Last.fm")), e.lastNotices().toString());
        assertTrue(recs.get(0).reason().toLowerCase().contains("last.fm"), recs.get(0).reason());
    }

    @Test
    void create_tagTrackThatIsAlsoInTheLibrary_countsAsGoalFit() {
        FakeMusic m = tagMusic();
        m.top = new ArrayList<>(m.top);
        m.top.add(named("p9", "Pop Hit", "popA", "PopA")); // popA is not a soul/r&b/ballad artist
        m.byGenre.put(tagQuery("Pop Hit", "PopA"), List.of(named("p9", "Pop Hit", "popA", "PopA")));
        TagService lastFm = (tag, limit) -> List.of(new TagTrack("Pop Hit", "PopA"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), withKey, ArtistGenreCache.NONE, lastFm);

        List<Recommendation> recs = e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(),
                List.of("slow dance")), spec(0.4), List.of());

        assertTrue(ids(recs).contains("p9"), ids(recs).toString());
        assertEquals("p9", ids(recs).get(0));
    }

    @Test
    void create_lastfmDown_stillCreatesWithNotice() {
        FakeMusic m = tagMusic();
        TagService down = (tag, limit) -> { throw new MusicServiceException("Last.fm returned HTTP 500."); };
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), withKey, ArtistGenreCache.NONE, down);

        List<Recommendation> recs = e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(),
                List.of("slow dance")), spec(0.4), List.of());

        assertEquals(10, recs.size(), "genre songs still fill the length");
        assertEquals(1, e.lastNotices().stream().filter(n -> n.contains("1 Spotify reads failed")).count(),
                e.lastNotices().toString());
    }

    @Test
    void create_noLastfmKey_oneOffNoticeAndNoLastfmCall() {
        FakeMusic m = tagMusic();
        int[] calls = {0};
        TagService counting = (tag, limit) -> { calls[0]++; return List.of(); };
        RecommendationEngine e = new RecommendationEngine(toolsFor(m), config, ArtistGenreCache.NONE, counting);

        List<Recommendation> recs = e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(),
                List.of("slow dance", "romantic")), spec(0.4), List.of());

        assertEquals(10, recs.size());
        assertEquals(1, e.lastNotices().stream().filter(n -> n.equals(config.lastfmOffNotice())).count(),
                e.lastNotices().toString());
    }

    @Test
    void create_noTags_noLastfmNoticeAndNoCall() {
        int[] calls = {0};
        TagService counting = (tag, limit) -> { calls[0]++; return List.of(); };
        RecommendationEngine e = new RecommendationEngine(toolsFor(tagMusic()), config, ArtistGenreCache.NONE, counting);

        e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(), List.of()), spec(0.4), List.of());

        assertEquals(0, calls[0]);
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("Last.fm")), e.lastNotices().toString());
    }

    // ---- Last.fm tags of the familiar songs themselves ----

    /** Last.fm that lists no tag songs and answers track tags from a title -> tags table. */
    private static class SongTags implements TagService {
        final Map<String, List<String>> byTitle = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();
        RuntimeException failure;

        @Override public List<TagTrack> topTracks(String tag, int limit) { return List.of(); }
        @Override public List<String> trackTags(String artist, String title) {
            asked.add(artist + "|" + title);
            if (failure != null) throw failure;
            return byTitle.getOrDefault(title, List.of());
        }
    }

    private static FakeMusic libraryOf(Track... tracks) {
        FakeMusic m = slowDanceMusic(0, 0);
        m.top = List.of(tracks);
        return m;
    }

    private List<Recommendation> slowDance(RecommendationEngine e) {
        return e.recommend(e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(), List.of("slow dance")),
                spec(0.4), List.of());
    }

    @Test
    void create_songTagsLackingTheGoalTags_excludeATrackWhoseArtistGenreFits() {
        SongTags lastFm = new SongTags();
        lastFm.byTitle.put("Billie Like", List.of("pop", "80s", "dance", "michael jackson"));
        lastFm.byTitle.put("Ballad Like", List.of("Slow Dance", "romantic", "soul"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("bj", "Billie Like", "soulA", "SoulA"), named("bal", "Ballad Like", "soulA", "SoulA"))),
                withKey, ArtistGenreCache.NONE, lastFm);

        List<String> picked = ids(slowDance(e));

        assertFalse(picked.contains("bj"), picked.toString());
        assertTrue(picked.contains("bal"), picked.toString());
        assertEquals(10, picked.size(), "new songs fill the length");
    }

    @Test
    void create_songTagsMakeATrackFitEvenWhenItsArtistGenresDoNot() {
        SongTags lastFm = new SongTags();
        lastFm.byTitle.put("Pop Ballad", List.of("romantic", "pop"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("pb", "Pop Ballad", "popA", "PopA"))), withKey, ArtistGenreCache.NONE, lastFm);

        assertTrue(ids(slowDance(e)).contains("pb"));
    }

    @Test
    void create_aPartialTagOrAGenreAloneIsNotAMatch() {
        SongTags lastFm = new SongTags();
        lastFm.byTitle.put("Slow Song", List.of("slow", "dancehall", "soul", "r&b", "ballad"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("sl", "Slow Song", "soulA", "SoulA"))), withKey, ArtistGenreCache.NONE, lastFm);

        assertFalse(ids(slowDance(e)).contains("sl"));
    }

    @Test
    void create_songTagLookupsAreCappedAndTheRestFallBackToTheArtistGenreRule() {
        SongTags lastFm = new SongTags();
        Config capped = withKey.withTunables(Map.of("lastfm.maxTrackTagLookups", "2"));
        Track[] lib = new Track[5];
        for (int i = 0; i < lib.length; i++) {
            lib[i] = named("c" + i, "Song " + i, "soulA", "SoulA");
            lastFm.byTitle.put("Song " + i, List.of("pop")); // would exclude, if looked up
        }
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(lib)), capped, ArtistGenreCache.NONE, lastFm);

        List<String> picked = ids(slowDance(e));

        assertEquals(2, lastFm.asked.size(), lastFm.asked.toString());
        assertEquals(3, picked.stream().filter(id -> id.startsWith("c")).count(), picked.toString());
    }

    @Test
    void create_aLookupIsMadeOncePerSongForTheWholeRetrieval() {
        SongTags lastFm = new SongTags();
        lastFm.byTitle.put("Ballad Like", List.of("romantic"));
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("bal", "Ballad Like", "soulA", "SoulA"))), withKey, ArtistGenreCache.NONE, lastFm);
        Retrieval r = e.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(), List.of("slow dance"));

        e.recommend(r, spec(0.4), List.of());
        e.recommend(r.without(java.util.Set.of("zzz")), spec(0.4), List.of());
        e.recommend(r, spec(0.4), List.of());

        assertEquals(1, lastFm.asked.size(), lastFm.asked.toString());
    }

    @Test
    void create_songTagFailure_fallsBackToArtistGenresStopsAskingAndSaysSo() {
        SongTags lastFm = new SongTags();
        lastFm.failure = new MusicServiceException("Last.fm returned HTTP 500.");
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("a", "A", "soulA", "SoulA"), named("b", "B", "soulA", "SoulA"), named("p", "P", "popA", "PopA"))),
                withKey, ArtistGenreCache.NONE, lastFm);

        List<String> picked = ids(slowDance(e));

        assertEquals(1, lastFm.asked.size(), "after one failure no more requests this run");
        assertTrue(picked.containsAll(List.of("a", "b")), picked.toString());
        assertFalse(picked.contains("p"), picked.toString());
        assertEquals(1, e.lastNotices().stream().filter(n -> n.contains("1 Spotify reads failed")).count(),
                e.lastNotices().toString());
    }

    @Test
    void create_unknownSongOnLastfm_fallsBackToArtistGenres() {
        SongTags lastFm = new SongTags(); // knows no tags for any song: empty reply
        RecommendationEngine e = new RecommendationEngine(toolsFor(libraryOf(
                named("a", "A", "soulA", "SoulA"), named("p", "P", "popA", "PopA"))), withKey, ArtistGenreCache.NONE, lastFm);

        List<String> picked = ids(slowDance(e));

        assertTrue(picked.contains("a"), picked.toString());
        assertFalse(picked.contains("p"), picked.toString());
        assertTrue(e.lastNotices().stream().noneMatch(n -> n.contains("Spotify reads failed")), e.lastNotices().toString());
    }

    @Test
    void create_noKeyOrNoTags_noSongLookups() {
        SongTags lastFm = new SongTags();
        FakeMusic m = libraryOf(named("a", "A", "soulA", "SoulA"));
        RecommendationEngine noKey = new RecommendationEngine(toolsFor(m), config, ArtistGenreCache.NONE, lastFm);
        noKey.recommend(noKey.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(), List.of("slow dance")),
                spec(0.4), List.of());
        RecommendationEngine noTags = new RecommendationEngine(toolsFor(m), withKey, ArtistGenreCache.NONE, lastFm);
        noTags.recommend(noTags.retrieve(goalProfile(), spec(0.4), true, SLOW_DANCE, List.of(), List.of()),
                spec(0.4), List.of());

        assertTrue(lastFm.asked.isEmpty(), lastFm.asked.toString());
    }

    @Test
    void create_songTagTitlesAreNeverSentToOpenAiOrStored() {
        // The lookup lives in the engine, which has no LLM and no repository; the only sink for a title is Last.fm.
        assertTrue(java.util.Arrays.stream(RecommendationEngine.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().contains("LLM") || f.getType().getSimpleName().contains("Repository")));
    }

    // ---- Genre results titled with the genre word, when Last.fm tag songs can fill the new share ----

    private FakeMusic genreTitleMusic() {
        FakeMusic m = slowDanceMusic(0, 0);
        m.top = List.of();
        m.byGenre.put("soul", List.of(titled("t1", "Soul All Night"), titled("t2", "Open Road"), titled("t3", "Soulful")));
        m.byGenre.put(tagQuery("Tag One", "Singer One"), List.of(named("tg1", "Tag One", "singer1", "Singer One")));
        return m;
    }

    private static final TagService ONE_TAG_SONG = (tag, limit) -> List.of(new TagTrack("Tag One", "Singer One"));

    @Test
    void create_withTagSongs_aGenreResultWhoseTitleHoldsTheGenreWordRanksFarBelowTheRest() {
        RecommendationEngine e = new RecommendationEngine(toolsFor(genreTitleMusic()), withKey, ArtistGenreCache.NONE, ONE_TAG_SONG);
        Retrieval r = e.retrieve(goalProfile(), spec(0.0), true, SLOW_DANCE, List.of(), List.of("slow dance"));

        List<Recommendation> recs = e.recommend(r, spec(0.0), List.of());

        List<String> ids = ids(recs);
        assertEquals(List.of("t1", "t3"), ids.subList(ids.size() - 2, ids.size()).stream().sorted().toList(), ids.toString());
        double open = recs.get(ids.indexOf("t2")).score();
        assertTrue(open - recs.get(ids.indexOf("t1")).score() >= withKey.discoverGenreTitleContainsPenalty() - 1e-9);
        assertTrue(ids.contains("t1") && ids.contains("t3"), "still listed when needed to fill the length");
    }

    @Test
    void create_withoutTagSongs_theGenreWordInATitleIsNotPenalisedBeyondTheBareTitleRule() {
        RecommendationEngine e = new RecommendationEngine(toolsFor(genreTitleMusic()), config, ArtistGenreCache.NONE, TagService.NONE);
        Retrieval r = e.retrieve(goalProfile(), spec(0.0), true, SLOW_DANCE, List.of(), List.of());

        List<Recommendation> recs = e.recommend(r, spec(0.0), List.of());

        List<String> ids = ids(recs);
        assertEquals(recs.get(ids.indexOf("t2")).score(), recs.get(ids.indexOf("t1")).score(), 1e-9);
    }

    @Test
    void discover_genreWordInATitleIsNeverPenalisedBeyondTheBareTitleRule() {
        RecommendationEngine e = new RecommendationEngine(toolsFor(genreTitleMusic()), withKey, ArtistGenreCache.NONE, ONE_TAG_SONG);
        e.setStrategy(new DiscoveryStrategy());

        List<Recommendation> recs = e.recommend(goalProfile(), spec(0.0), List.of(), false, SLOW_DANCE);

        List<String> ids = ids(recs);
        assertEquals(recs.get(ids.indexOf("t2")).score(), recs.get(ids.indexOf("t1")).score(), 1e-9);
    }
}
