package com.musicmind.agent.command;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.music.MusicService;
import com.musicmind.music.TrackList;

import java.util.List;

/**
 * Position helpers shared by the commands. Spotify's reorder call is "insert before", so moving
 * an item to a final index needs one extra step when it moves later in the list.
 */
final class PlaylistOps {

    private PlaylistOps() {
    }

    /** Index of the track in the items just read, or throws the "no longer in the playlist" message. */
    static int indexOf(Config config, List<Track> items, Track track) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(track.id())) {
                return i;
            }
        }
        throw new PlaylistChangeException(String.format(config.wording("cmdTrackMissing"), label(track)));
    }

    /** Every index holding the track (a playlist may contain copies), ascending; throws if there are none. */
    static List<Integer> indexesOf(Config config, List<Track> items, Track track) {
        List<Integer> found = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(track.id())) {
                found.add(i);
            }
        }
        if (found.isEmpty()) {
            throw new PlaylistChangeException(String.format(config.wording("cmdTrackMissing"), label(track)));
        }
        return found;
    }

    /**
     * Re-adds one copy of the track per index (they land at the end of a playlist of {@code size}
     * items) and moves each to its original index, earliest first.
     */
    static void restoreCopies(MusicService music, String playlistId, Track track, List<Integer> indexes, int size) {
        music.addItems(playlistId, java.util.Collections.nCopies(indexes.size(), uri(track)));
        for (int j = 0; j < indexes.size(); j++) {
            move(music, playlistId, size + j, indexes.get(j));
        }
    }

    /**
     * Moves the track at {@code from} so that it ends up at index {@code to}. Both index the list a read
     * returns; Spotify is given the real positions, which differ when the read left entries out.
     */
    static void move(MusicService music, String playlistId, int from, int to) {
        if (from != to) {
            move(music, playlistId, music.getPlaylistItems(playlistId), from, to);
        }
    }

    /** As {@link #move(MusicService, String, int, int)}, with the playlist as it was just read. */
    static void move(MusicService music, String playlistId, List<Track> items, int from, int to) {
        if (from != to) {
            int target = TrackList.position(items, to);
            music.reorderItems(playlistId, TrackList.position(items, from), to > from ? target + 1 : target);
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
