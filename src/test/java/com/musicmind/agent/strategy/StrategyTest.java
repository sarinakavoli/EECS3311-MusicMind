package com.musicmind.agent.strategy;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyTest {

    private static Track track(String id, String artist) {
        return new Track(id, "spotify:track:" + id, "T" + id, 1000, false, List.of(artist));
    }

    /** Knows artist "known" and owns tracks "lib0".."lib2"; saved id "saved". */
    private static ListeningProfile profile() {
        return new ListeningProfile(Map.of("rock", 1.0),
                List.of(new Artist("known", "K", List.of("rock"))),
                List.of(track("lib0", "known"), track("lib1", "known"), track("lib2", "known")),
                List.of("saved"), null, Instant.EPOCH);
    }

    private static List<Track> familiarPool(int n) {
        return IntStream.range(0, n).mapToObj(i -> track("f" + i, "known")).toList();
    }

    /** n novel tracks: even index by an unknown artist, odd by the known artist. */
    private static List<Track> novelPool(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> track("n" + i, i % 2 == 0 ? "stranger" + i : "known")).toList();
    }

    private static PlaylistSpec spec(int minutes, double ratio) {
        return new PlaylistSpec(minutes, true, ratio, List.of(), "x");
    }

    private static List<String> ids(List<Track> tracks) {
        return tracks.stream().map(Track::id).toList();
    }

    private static long fromFamiliar(List<Track> tracks) {
        return tracks.stream().filter(t -> t.id().startsWith("f")).count();
    }

    @Test
    void helpersNeedABoundProfile() {
        FamiliarStrategy s = new FamiliarStrategy();
        assertThrows(IllegalStateException.class,
                () -> s.selectCandidates(Map.of("familiar", familiarPool(2), "novel", novelPool(30)), spec(40, 0.5)));
    }

    @Test
    void familiarStrategyTakesFamiliarFirstThenFillsWithNewTracksTheUserLacks() {
        FamiliarStrategy s = new FamiliarStrategy();
        s.bindProfile(profile());
        List<Track> novel = List.of(track("lib0", "known"), track("saved", "x"), track("n1", "known"));

        List<Track> out = s.selectCandidates(Map.of("familiar", familiarPool(2), "novel", novel), spec(20, 0.9));

        assertEquals(List.of("f0", "f1", "n1"), ids(out), "5 wanted: both familiar, then only the unowned novel track");
    }

    @Test
    void discoveryStrategyPutsUnknownArtistsFirstAndDropsOwnedTracks() {
        DiscoveryStrategy s = new DiscoveryStrategy();
        s.bindProfile(profile());
        List<Track> novel = List.of(track("n1", "known"), track("lib1", "stranger"),
                track("n2", "stranger2"), track("n3", "stranger3"));

        List<Track> out = s.selectCandidates(Map.of("familiar", familiarPool(5), "novel", novel), spec(12, 0.1));

        // 12 min / 4 = 3 tracks: the unknown-artist tracks (lib1 is owned, so dropped), then known-artist novel.
        assertEquals(List.of("n2", "n3", "n1"), ids(out));
    }

    @Test
    void discoveryStrategyFallsBackToFamiliarOnlyWhenNovelRunsOut() {
        DiscoveryStrategy s = new DiscoveryStrategy();
        s.bindProfile(profile());

        List<Track> out = s.selectCandidates(
                Map.of("familiar", familiarPool(5), "novel", List.of(track("n0", "stranger"))), spec(16, 0.0));

        assertEquals(List.of("n0", "f0", "f1", "f2"), ids(out));
    }

    @Test
    void balancedStrategySplitsByTheFamiliarRatio() {
        BalancedStrategy s = new BalancedStrategy();
        s.bindProfile(profile());
        Map<String, List<Track>> pools = Map.of("familiar", familiarPool(20), "novel", novelPool(20));

        List<Track> seventy = s.selectCandidates(pools, spec(40, 0.7));
        List<Track> thirty = s.selectCandidates(pools, spec(40, 0.3));

        assertEquals(10, seventy.size());
        assertEquals(7, fromFamiliar(seventy));
        assertEquals(3, fromFamiliar(thirty));
    }

    @Test
    void balancedStrategyTopsUpFromTheOtherPoolWhenOneIsShort() {
        BalancedStrategy s = new BalancedStrategy();
        s.bindProfile(profile());

        List<Track> out = s.selectCandidates(
                Map.of("familiar", familiarPool(2), "novel", novelPool(30)), spec(40, 0.7));

        assertEquals(10, out.size());
        assertEquals(2, fromFamiliar(out));
        assertTrue(out.stream().noneMatch(t -> profile().knownTrackIds().contains(t.id())));
    }

    @Test
    void emptyPoolsGiveAnEmptySelection() {
        BalancedStrategy s = new BalancedStrategy();
        s.bindProfile(profile());
        assertTrue(s.selectCandidates(Map.of(), spec(40, 0.5)).isEmpty());
    }

    @Test
    void noTargetDurationUsesTheConfiguredDefaultCount() {
        assertEquals(20, AbstractRecommendationStrategy.targetCount(spec(0, 0.5), Config.defaults()));
        assertEquals(10, AbstractRecommendationStrategy.targetCount(spec(40, 0.5), Config.defaults()));
    }

    @Test
    void familiarityIsOneForKnownArtistsAndZeroOtherwise() {
        ListeningProfile p = profile();
        assertEquals(1.0, p.familiarity(track("a", "known")));
        assertEquals(0.0, p.familiarity(track("b", "stranger")));
    }

    @Test
    void strategiesUseTheConfigTheyAreGiven() {
        Config big = Config.of(Map.of()).withTunables(Map.of("strategy.avgTrackMinutes", "8"));
        for (RecommendationStrategy s : List.of(new FamiliarStrategy(big), new DiscoveryStrategy(big), new BalancedStrategy(big))) {
            s.bindProfile(profile());
            List<Track> out = s.selectCandidates(
                    Map.of("familiar", familiarPool(20), "novel", novelPool(20)), spec(40, 0.5));
            assertEquals(5, out.size(), s.getClass().getSimpleName());
        }
    }
}
