package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.TrendReport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendComparatorTest {

    private final Config config = Config.of(Map.of());
    private final TrendComparator comparator = new TrendComparator(new ProfileBuilder(config), config);

    private static Artist artist(String id, String... genres) {
        return new Artist(id, "Name " + id, List.of(genres));
    }

    /** Pads a list to the configured threshold so a range counts as complete. */
    private List<Artist> full(Artist... first) {
        List<Artist> all = new java.util.ArrayList<>(List.of(first));
        for (int i = all.size(); i < config.profileMinItems(); i++) {
            all.add(artist("filler" + first[0].id() + i, "pop"));
        }
        return all;
    }

    private static Map<String, List<Artist>> ranges(List<Artist> s, List<Artist> m, List<Artist> l) {
        Map<String, List<Artist>> map = new LinkedHashMap<>();
        map.put("short", s);
        map.put("medium", m);
        map.put("long", l);
        return map;
    }

    @Test
    void addedAndDroppedAreSetDifferencesBetweenNewestAndOldestPopulatedRanges() {
        List<Artist> shortR = full(artist("new1", "synthwave"), artist("both", "rock"));
        List<Artist> longR = full(artist("old1", "jazz"), artist("both", "rock"));

        TrendReport r = comparator.compare(ranges(shortR, shortR, longR), Map.of());

        assertTrue(r.addedArtistIds().contains("new1"));
        assertTrue(r.droppedArtistIds().contains("old1"));
        assertTrue(!r.addedArtistIds().contains("both") && !r.droppedArtistIds().contains("both"));
        assertEquals(List.of("synthwave"), r.addedGenres());
        assertEquals(List.of("jazz"), r.droppedGenres());
        assertTrue(r.gaps().isEmpty());
        assertEquals(3, r.artistsByRange().size());
        assertEquals(3, r.genresByRange().size());
    }

    @Test
    void anEmptyRangeIsMarkedAndTheRemainingRangesAreCompared() {
        List<Artist> shortR = full(artist("new1", "synthwave"));
        List<Artist> mediumR = List.of();
        List<Artist> longR = full(artist("old1", "jazz"));

        TrendReport r = comparator.compare(ranges(shortR, mediumR, longR), Map.of());

        assertEquals(1, r.gaps().size());
        assertTrue(r.gaps().get(0).contains("medium"), r.gaps().get(0));
        assertTrue(r.addedArtistIds().contains("new1"));
        assertTrue(r.droppedArtistIds().contains("old1"));
    }

    @Test
    void withFewerThanTwoPopulatedRangesNothingIsComparedAndTheGapSaysSo() {
        TrendReport r = comparator.compare(ranges(full(artist("x", "pop")), List.of(), List.of()), Map.of());

        assertTrue(r.addedArtistIds().isEmpty());
        assertTrue(r.droppedGenres().isEmpty());
        assertTrue(r.gaps().stream().anyMatch(g -> g.contains("medium")));
        assertTrue(r.gaps().stream().anyMatch(g -> g.contains("long")));
        assertTrue(r.gaps().stream().anyMatch(g -> g.toLowerCase().contains("not enough")));
    }

    @Test
    void aThinRangeIsStillComparedButNotedAsIncomplete() {
        List<Artist> thin = List.of(artist("new1", "synthwave"));
        TrendReport r = comparator.compare(ranges(thin, full(artist("m", "pop")), full(artist("old1", "jazz"))),
                Map.of());

        assertTrue(r.addedArtistIds().contains("new1"));
        assertTrue(r.gaps().stream().anyMatch(g -> g.contains("short") && g.toLowerCase().contains("incomplete")));
    }

    @Test
    void aRangeThatFailedToLoadIsReportedAsFailedInsteadOfEmptyWithoutTheRawReason() {
        TrendReport r = comparator.compare(
                ranges(full(artist("a", "pop")), List.of(), full(artist("b", "rock"))),
                Map.of("medium", "Spotify API error (HTTP 500) on GET /me/top/artists: {boom}"));
        assertEquals(List.of("The medium range could not be read from Spotify."), r.gaps());
    }

    private static List<Artist> noGenres(String prefix, int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> artist(prefix + i)).toList();
    }

    @Test
    void genreCoverageCountsTheArtistsThatHaveGenreData() {
        List<Artist> mixed = new java.util.ArrayList<>(noGenres("p", 6));
        mixed.addAll(List.of(artist("g1", "rock"), artist("g2", "rock")));

        TrendReport r = comparator.compare(ranges(full(artist("s1", "pop")), mixed, full(artist("l1", "pop"))), Map.of());

        assertEquals(2, r.artistsWithGenres("medium"));
        assertEquals(8, r.artistCount("medium"));
        assertTrue(r.genreCoveragePartial(config.trendMinGenreCoverage()));
    }

    @Test
    void lowCoverageInAnyRangeIsReportedAsAGapWithTheCounts() {
        TrendReport r = comparator.compare(ranges(full(artist("s1", "pop")), full(artist("m1", "pop")),
                noGenres("p", 8)), Map.of());

        String gap = r.gaps().stream().filter(g -> g.contains("long: 0 of 8")).findFirst().orElse("");
        assertTrue(!gap.isEmpty(), r.gaps().toString());
        assertTrue(gap.contains(config.wording("trendCoveragePartial").substring(0, 20)), gap);
    }

    @Test
    void fullCoverageAddsNoCoverageGap() {
        List<Artist> a = full(artist("s1", "pop"));

        TrendReport r = comparator.compare(ranges(a, a, a), Map.of());

        assertTrue(!r.genreCoveragePartial(config.trendMinGenreCoverage()));
        assertTrue(r.gaps().stream().noneMatch(g -> g.contains("of ")), r.gaps().toString());
    }

    @Test
    void theCoverageSummaryFollowsTheConfiguredRangeOrder() {
        TrendReport r = comparator.compare(ranges(noGenres("s", 8), full(artist("m1", "pop")), noGenres("l", 8)), Map.of());

        String summary = r.coverageSummary(config.profileRanges());

        assertEquals("short: 0 of 8 artists, medium: " + config.profileMinItems() + " of " + config.profileMinItems()
                + " artists, long: 0 of 8 artists", summary);
    }
}
