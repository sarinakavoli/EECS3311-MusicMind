package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Artist;
import com.musicmind.domain.TrendReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic half of F2: set differences between the time ranges. The newest
 * and the oldest populated range are compared: what is in the newest but not the
 * oldest was added, the reverse was dropped. Empty ranges and ranges that failed
 * to load are marked as gaps and left out of the comparison; a range with fewer
 * artists than the configured threshold is compared but noted as incomplete.
 */
public class TrendComparator {

    private final ProfileBuilder profileBuilder;
    private final int minItems;
    private final Config config;

    public TrendComparator(ProfileBuilder profileBuilder, Config config) {
        this.profileBuilder = profileBuilder;
        this.minItems = config.profileMinItems();
        this.config = config;
    }

    /**
     * @param artistsByRange top artists per range, ordered newest first; a range with no
     *                       data (or that failed to load) maps to an empty list
     * @param fetchErrors    ranges whose read failed (the values are not shown: the gap uses the
     *                       configured short wording, never the raw error)
     * @return a report without a narrative
     */
    public TrendReport compare(Map<String, List<Artist>> artistsByRange, Map<String, String> fetchErrors) {
        List<String> gaps = new ArrayList<>();
        List<String> populated = new ArrayList<>();
        Map<String, Map<String, Double>> genresByRange = new LinkedHashMap<>();

        artistsByRange.forEach((range, artists) -> {
            genresByRange.put(range, profileBuilder.computeGenreHistogram(artists));
            if (fetchErrors.containsKey(range)) {
                gaps.add(String.format(config.wording("trendRangeFailed"), range));
            } else if (artists.isEmpty()) {
                gaps.add("No top artists for the " + range + " range.");
            } else {
                populated.add(range);
                if (artists.size() < minItems) {
                    gaps.add("The " + range + " range is incomplete: only " + artists.size()
                            + " top artists (at least " + minItems + " expected).");
                }
            }
        });

        List<String> addedGenres = List.of();
        List<String> droppedGenres = List.of();
        List<String> addedArtists = List.of();
        List<String> droppedArtists = List.of();
        if (populated.size() < 2) {
            gaps.add("Not enough ranges with data to compare.");
        } else {
            String newest = populated.get(0);
            String oldest = populated.get(populated.size() - 1);
            addedGenres = difference(genresByRange.get(newest).keySet(), genresByRange.get(oldest).keySet());
            droppedGenres = difference(genresByRange.get(oldest).keySet(), genresByRange.get(newest).keySet());
            addedArtists = difference(ids(artistsByRange.get(newest)), ids(artistsByRange.get(oldest)));
            droppedArtists = difference(ids(artistsByRange.get(oldest)), ids(artistsByRange.get(newest)));
        }
        if (TrendReport.genreCoveragePartial(artistsByRange, config.trendMinGenreCoverage())) {
            gaps.add(String.format(config.wording("trendCoveragePartial"),
                    TrendReport.coverageSummary(artistsByRange, config.profileRanges())));
        }
        return new TrendReport(artistsByRange, genresByRange, addedGenres, droppedGenres,
                addedArtists, droppedArtists, gaps, null);
    }

    private static Set<String> ids(List<Artist> artists) {
        Set<String> ids = new LinkedHashSet<>();
        artists.forEach(a -> ids.add(a.id()));
        return ids;
    }

    private static List<String> difference(Set<String> from, Set<String> minus) {
        return from.stream().filter(x -> !minus.contains(x)).toList();
    }
}
