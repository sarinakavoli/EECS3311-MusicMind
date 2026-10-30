package com.musicmind.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An ordered playlist of tracks, backed by a Spotify playlist id once created. */
public class Playlist {
    private String spotifyId;
    private String name;
    private final List<Track> tracks = new ArrayList<>();

    public Playlist(String name) {
        this.name = name;
    }

    public Playlist(String spotifyId, String name, List<Track> tracks) {
        this.spotifyId = spotifyId;
        this.name = name;
        if (tracks != null) this.tracks.addAll(tracks);
    }

    public void addTrack(Track t) { tracks.add(t); }
    public boolean removeTrack(Track t) { return tracks.remove(t); }

    /** Total playing time of all tracks, in milliseconds. */
    public int totalDuration() {
        return tracks.stream().mapToInt(Track::durationMs).sum();
    }

    public String spotifyId() { return spotifyId; }
    public void setSpotifyId(String id) { this.spotifyId = id; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Track> tracks() { return Collections.unmodifiableList(tracks); }
}
