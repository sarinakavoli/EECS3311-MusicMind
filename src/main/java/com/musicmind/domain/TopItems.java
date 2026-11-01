package com.musicmind.domain;

import java.util.List;

/** The user's top artists and top tracks, the input to {@code ProfileBuilder.build}. */
public record TopItems(List<Artist> artists, List<Track> tracks) {
    public TopItems {
        artists = artists == null ? List.of() : List.copyOf(artists);
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }
}
