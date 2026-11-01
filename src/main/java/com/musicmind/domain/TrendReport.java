package com.musicmind.domain;

import java.util.List;
import java.util.Map;

/**
 * Taste over time (F2): top artists and genre weights per time range, what was
 * added and dropped between the compared ranges, notices for ranges that had
 * no data, and a narrative. Held in memory for display only.
 */
public record TrendReport(Map<String, List<Artist>> artistsByRange,
                          Map<String, Map<String, Double>> genresByRange,
                          List<String> addedGenres, List<String> droppedGenres,
                          List<String> addedArtistIds, List<String> droppedArtistIds,
                          List<String> gaps, String narrative) {
    public TrendReport {
        artistsByRange = artistsByRange == null ? Map.of() : Map.copyOf(artistsByRange);
        genresByRange = genresByRange == null ? Map.of() : Map.copyOf(genresByRange);
        addedGenres = addedGenres == null ? List.of() : List.copyOf(addedGenres);
        droppedGenres = droppedGenres == null ? List.of() : List.copyOf(droppedGenres);
        addedArtistIds = addedArtistIds == null ? List.of() : List.copyOf(addedArtistIds);
        droppedArtistIds = droppedArtistIds == null ? List.of() : List.copyOf(droppedArtistIds);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
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
        return artistsByRange.keySet().stream()
                .anyMatch(r -> artistCount(r) > 0 && artistsWithGenres(r) < minShare * artistCount(r));
    }

    /** Per range, as "short: 12 of 20 artists, long: 3 of 20 artists", skipping empty ranges. */
    public String coverageSummary() {
        return artistsByRange.keySet().stream().filter(r -> artistCount(r) > 0)
                .map(r -> r + ": " + artistsWithGenres(r) + " of " + artistCount(r) + " artists")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public TrendReport withNarrative(String text) {
        return new TrendReport(artistsByRange, genresByRange, addedGenres, droppedGenres,
                addedArtistIds, droppedArtistIds, gaps, text);
    }
}
