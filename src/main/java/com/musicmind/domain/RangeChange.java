package com.musicmind.domain;

import java.util.List;

/** What changed between two neighbouring time ranges (F2), {@code from} the older {@code to} the newer. */
public record RangeChange(String from, String to, List<String> addedGenres, List<String> droppedGenres,
                          List<String> addedArtistIds, List<String> droppedArtistIds) {
    public RangeChange {
        addedGenres = List.copyOf(addedGenres);
        droppedGenres = List.copyOf(droppedGenres);
        addedArtistIds = List.copyOf(addedArtistIds);
        droppedArtistIds = List.copyOf(droppedArtistIds);
    }
}
