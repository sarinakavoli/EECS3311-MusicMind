package com.musicmind.domain;

import java.util.List;

/** A Spotify artist (immutable value object). */
public record Artist(String id, String name, List<String> genres) {
    public Artist {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }
}
