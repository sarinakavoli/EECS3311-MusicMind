package com.musicmind.agent;

import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/**
 * What an edit instruction is applied to: the playlist id, its tracks as the user saw them
 * (position 1 is {@code tracks.get(0)}), the service used to find tracks to add, and the playlist's
 * rules, so that a search passes over what they block ({@code rules} may be null).
 */
public record PlaylistContext(String playlistId, List<Track> tracks, MusicService music, PlaylistSpec rules) {
    public PlaylistContext {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    public PlaylistContext(String playlistId, List<Track> tracks, MusicService music) {
        this(playlistId, tracks, music, null);
    }

    /** Whether an edit may add the track. */
    public boolean allows(Track track) {
        return rules == null || !rules.blocks(track);
    }
}
