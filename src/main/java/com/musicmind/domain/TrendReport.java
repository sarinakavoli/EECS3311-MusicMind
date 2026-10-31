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

    public TrendReport withNarrative(String text) {
        return new TrendReport(artistsByRange, genresByRange, addedGenres, droppedGenres,
                addedArtistIds, droppedArtistIds, gaps, text);
    }
}
