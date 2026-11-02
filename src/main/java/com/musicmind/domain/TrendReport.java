package com.musicmind.domain;

import java.util.List;
import java.util.Map;

/**
 * Taste over time (F2): top artists and genre weights per time range, what was
 * added and dropped between the newest and oldest ranges, the same for each step
 * between neighbouring ranges, notices for ranges that had no data, and a
 * narrative. Held in memory for display only.
 */
public record TrendReport(Map<String, List<Artist>> artistsByRange,
                          Map<String, Map<String, Double>> genresByRange,
                          List<String> addedGenres, List<String> droppedGenres,
                          List<String> addedArtistIds, List<String> droppedArtistIds,
                          List<String> gaps, String narrative, List<RangeChange> steps) {
    public TrendReport {
        artistsByRange = artistsByRange == null ? Map.of() : Map.copyOf(artistsByRange);
        genresByRange = genresByRange == null ? Map.of() : Map.copyOf(genresByRange);
        addedGenres = addedGenres == null ? List.of() : List.copyOf(addedGenres);
        droppedGenres = droppedGenres == null ? List.of() : List.copyOf(droppedGenres);
        addedArtistIds = addedArtistIds == null ? List.of() : List.copyOf(addedArtistIds);
        droppedArtistIds = droppedArtistIds == null ? List.of() : List.copyOf(droppedArtistIds);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** A report with no step-by-step changes. */
    public TrendReport(Map<String, List<Artist>> artistsByRange, Map<String, Map<String, Double>> genresByRange,
                       List<String> addedGenres, List<String> droppedGenres, List<String> addedArtistIds,
                       List<String> droppedArtistIds, List<String> gaps, String narrative) {
        this(artistsByRange, genresByRange, addedGenres, droppedGenres, addedArtistIds, droppedArtistIds, gaps,
                narrative, List.of());
    }

    /** How many artists in the range there are. */
    public int artistCount(String range) {
        return artistsByRange.getOrDefault(range, List.of()).size();
    }

    /** How many of the range's artists have genre data (MusicBrainz lists many artists without any). */
    public int artistsWithGenres(String range) {
        return (int) artistsByRange.getOrDefault(range, List.of()).stream().filter(a -> !a.genres().isEmpty()).count();
    }

    /** True when some range that has artists has genre data for a smaller share of them than {@code minShare}. */
    public boolean genreCoveragePartial(double minShare) {
        return genreCoveragePartial(artistsByRange, minShare);
    }

    /** As the instance method, for artists not yet in a report. */
    public static boolean genreCoveragePartial(Map<String, List<Artist>> artistsByRange, double minShare) {
        return artistsByRange.values().stream().anyMatch(artists -> !artists.isEmpty()
                && artists.stream().filter(a -> !a.genres().isEmpty()).count() < minShare * artists.size());
    }

    /** Per range, in the given order (the configured ranges, newest first), as "short: 12 of 20 artists, ...". */
    public String coverageSummary(List<String> rangeOrder) {
        return coverageSummary(artistsByRange, rangeOrder);
    }

    /** As the instance method, for artists not yet in a report. */
    public static String coverageSummary(Map<String, List<Artist>> artistsByRange, List<String> rangeOrder) {
        return rangeOrder.stream().filter(r -> !artistsByRange.getOrDefault(r, List.of()).isEmpty())
                .map(r -> {
                    List<Artist> artists = artistsByRange.get(r);
                    return r + ": " + artists.stream().filter(a -> !a.genres().isEmpty()).count()
                            + " of " + artists.size() + " artists";
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public TrendReport withNarrative(String text) {
        return new TrendReport(artistsByRange, genresByRange, addedGenres, droppedGenres,
                addedArtistIds, droppedArtistIds, gaps, text, steps);
    }
}
