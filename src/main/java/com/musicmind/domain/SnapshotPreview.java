package com.musicmind.domain;

import java.util.List;

/**
 * The songs of a saved version shown before a restore. A song whose title could not be read has a blank title
 * (only its id is known). {@code moreCount} songs past the lookup limit are not listed; {@code stoppedEarly} says
 * the lookups ended on a login, rate-limit or server problem, so the songs after that point are untitled.
 */
public record SnapshotPreview(List<Track> songs, int moreCount, boolean stoppedEarly) {
    public SnapshotPreview {
        songs = List.copyOf(songs);
    }
}
