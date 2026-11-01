package com.musicmind.agent;

import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/**
 * What an edit instruction is applied to: the playlist id, its tracks as the user saw them
 * (position 1 is {@code tracks.get(0)}), and the service used to find tracks to add.
 */
public record PlaylistContext(String playlistId, List<Track> tracks, MusicService music) {
    public PlaylistContext {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }
}
