package com.musicmind.domain;

import java.util.List;

/**
 * The Last.fm tags known for one playlist song, and whose they are: the song's own, its artist's, or none.
 * Kept in memory for one Modify request only.
 */
public record SongTags(List<String> tags, Kind kind) {

    public enum Kind { SONG, ARTIST, NONE }

    public SongTags {
        tags = tags == null ? List.of() : List.copyOf(tags);
        kind = kind == null ? Kind.NONE : kind;
    }
}
