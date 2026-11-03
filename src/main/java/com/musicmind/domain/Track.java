package com.musicmind.domain;

import java.util.List;

/** A Spotify track (immutable value object). */
public record Track(String id, String uri, String title, int durationMs,
                    boolean explicit, List<String> artistIds,
                    List<String> artistNames) {
    public Track {
        artistIds = artistIds == null ? List.of() : List.copyOf(artistIds);
        artistNames = artistNames == null ? List.of() : List.copyOf(artistNames);
    }

    /** Stage 1 shape: a track without artist names. */
    public Track(String id, String uri, String title, int durationMs,
                 boolean explicit, List<String> artistIds) {
        this(id, uri, title, durationMs, explicit, artistIds, List.of());
    }

    /** Duration rounded to whole seconds. */
    public int durationSec() {
        return Math.round(durationMs / 1000f);
    }

    /** Convenience alias used across the agent code. */
    public boolean isExplicit() {
        return explicit;
    }
}
