package com.musicmind.domain;

import java.time.Instant;

/** What the history shows about a saved playlist version: ids, MusicMind's name for it, size and time. No track content. */
public record SnapshotInfo(int id, String playlistId, String name, int trackCount, Instant createdAt) {
}
