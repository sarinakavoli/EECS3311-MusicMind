package com.musicmind.domain;

/** The authenticated MusicMind user, mapped to a Spotify account. */
public class User {
    private final String id;
    private final String displayName;
    private final String spotifyUserId;

    public User(String id, String displayName, String spotifyUserId) {
        this.id = id;
        this.displayName = displayName;
        this.spotifyUserId = spotifyUserId;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String spotifyUserId() { return spotifyUserId; }
}
