package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;

import java.util.List;

/**
 * Position helpers shared by the commands. Spotify's reorder call is "insert before", so moving
 * an item to a final index needs one extra step when it moves later in the list.
 */
final class PlaylistOps {

    private PlaylistOps() {
    }

    /** Index of the track in the playlist as it is now, or throws the "no longer in the playlist" message. */
    static int indexOf(Config config, MusicService music, String playlistId, Track track) {
        List<Track> items = music.getPlaylistItems(playlistId);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(track.id())) {
                return i;
            }
        }
        throw new PlaylistChangeException(String.format(config.wording("cmdTrackMissing"), label(track)));
    }

    /** Moves the item at {@code from} so that it ends up at index {@code to}. */
    static void move(MusicService music, String playlistId, int from, int to) {
        if (from != to) {
            music.reorderItems(playlistId, from, to > from ? to + 1 : to);
        }
    }

    static String uri(Track t) {
        return t.uri() != null && !t.uri().isBlank() ? t.uri() : "spotify:track:" + t.id();
    }

    /** Title if known, else the id (tracks read back from a snapshot have no title). */
    static String label(Track t) {
        return t.title() == null || t.title().isBlank() ? t.id() : t.title();
    }
}
