package com.musicmind.music;

import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A list of tracks that also says whether the read stopped at its limit with more tracks left on
 * Spotify. It is an ordinary list to everything that passes it along (the decorators and the tools
 * hand it on unchanged), so the {@link MusicService} signatures stay as they are; the caller that
 * cares (the taste profile) checks {@link #isTruncated()}. It also keeps each track's real position,
 * because a read leaves out entries such as local files that still count in Spotify's own positions.
 */
public class TrackList extends ArrayList<Track> {

    private final boolean truncated;
    private final List<Integer> positions;

    public TrackList(Collection<Track> tracks, boolean truncated) {
        this(tracks, truncated, List.of());
    }

    /** @param positions each track's position on Spotify, in order; empty when positions match the list's own */
    public TrackList(Collection<Track> tracks, boolean truncated, List<Integer> positions) {
        super(tracks);
        this.truncated = truncated;
        this.positions = List.copyOf(positions);
    }

    /** True when the read stopped at its limit although Spotify had more tracks. */
    public boolean isTruncated() {
        return truncated;
    }

    /** True if the list is a {@link TrackList} that stopped at its limit; any other list is taken as complete. */
    public static boolean isTruncated(List<Track> tracks) {
        return tracks instanceof TrackList list && list.isTruncated();
    }

    /** The position on Spotify of the track at {@code index}; the same index for any other list. */
    public static int position(List<Track> tracks, int index) {
        return tracks instanceof TrackList list && index < list.positions.size() ? list.positions.get(index) : index;
    }
}
