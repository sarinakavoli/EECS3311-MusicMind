package com.musicmind.music;

import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A list of tracks that also says whether the read stopped at its limit with more tracks left on
 * Spotify. It is an ordinary list to everything that passes it along (the decorators and the tools
 * hand it on unchanged), so the {@link MusicService} signatures stay as they are; the caller that
 * cares (the taste profile) checks {@link #isTruncated()}.
 */
public class TrackList extends ArrayList<Track> {

    private final boolean truncated;

    public TrackList(Collection<Track> tracks, boolean truncated) {
        super(tracks);
        this.truncated = truncated;
    }

    /** True when the read stopped at its limit although Spotify had more tracks. */
    public boolean isTruncated() {
        return truncated;
    }
}
