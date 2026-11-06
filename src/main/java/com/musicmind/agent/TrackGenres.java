package com.musicmind.agent;

import com.musicmind.domain.Track;

import java.util.List;

/**
 * The genres MusicMind already knows for a track, from its own data and Last.fm, never from Spotify's
 * title or artist fields. An empty list means no genre is known.
 */
@FunctionalInterface
public interface TrackGenres {

    List<String> of(Track track);
}
