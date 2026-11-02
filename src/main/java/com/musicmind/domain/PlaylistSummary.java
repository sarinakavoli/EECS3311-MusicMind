package com.musicmind.domain;

/** One of the user's playlists as listed by Spotify: read for display, never stored. */
public record PlaylistSummary(String id, String name, int trackCount) {
}
