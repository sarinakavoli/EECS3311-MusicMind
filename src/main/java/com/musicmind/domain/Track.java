package com.musicmind.domain;

import java.util.List;

/** A Spotify track (immutable value object). */
public record Track(String id, String uri, String title, int durationMs,
                    boolean explicit, List<String> artistIds) {
    public Track {
        artistIds = artistIds == null ? List.of() : List.copyOf(artistIds);
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
