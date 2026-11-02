package com.musicmind.domain;

import java.util.List;

/** The structured constraints parsed from a natural-language playlist goal. */
public class PlaylistSpec {
    private int targetMinutes;
    private boolean allowExplicit;
    private double familiarRatio;         // 0.0 .. 1.0 share of familiar tracks
    private List<String> blockedArtists;
    private List<String> blockedTracks;
    private String name;

    public PlaylistSpec(int targetMinutes, boolean allowExplicit, double familiarRatio,
                        List<String> blockedArtists, String name) {
        this(targetMinutes, allowExplicit, familiarRatio, blockedArtists, List.of(), name);
    }

    public PlaylistSpec(int targetMinutes, boolean allowExplicit, double familiarRatio,
                        List<String> blockedArtists, List<String> blockedTracks, String name) {
        this.targetMinutes = targetMinutes;
        this.allowExplicit = allowExplicit;
        this.familiarRatio = familiarRatio;
        this.blockedArtists = blockedArtists == null ? List.of() : List.copyOf(blockedArtists);
        this.blockedTracks = blockedTracks == null ? List.of() : List.copyOf(blockedTracks);
        this.name = name;
    }

    public boolean isBlocked(String artistId) {
        return blockedArtists.contains(artistId);
    }

    public boolean isTrackBlocked(String trackId) {
        return blockedTracks.contains(trackId);
    }

    // Copies with one thing changed, so that no copy can lose the others.
    public PlaylistSpec withTargetMinutes(int minutes) {
        return new PlaylistSpec(minutes, allowExplicit, familiarRatio, blockedArtists, blockedTracks, name);
    }

    public PlaylistSpec withAllowExplicit(boolean allow) {
        return new PlaylistSpec(targetMinutes, allow, familiarRatio, blockedArtists, blockedTracks, name);
    }

    public PlaylistSpec withFamiliarRatio(double ratio) {
        return new PlaylistSpec(targetMinutes, allowExplicit, ratio, blockedArtists, blockedTracks, name);
    }

    public PlaylistSpec withBlockedArtists(List<String> ids) {
        return new PlaylistSpec(targetMinutes, allowExplicit, familiarRatio, ids, blockedTracks, name);
    }

    public PlaylistSpec withBlockedTracks(List<String> ids) {
        return new PlaylistSpec(targetMinutes, allowExplicit, familiarRatio, blockedArtists, ids, name);
    }

    public PlaylistSpec withName(String newName) {
        return new PlaylistSpec(targetMinutes, allowExplicit, familiarRatio, blockedArtists, blockedTracks, newName);
    }

    public int targetMinutes() { return targetMinutes; }
    public boolean allowExplicit() { return allowExplicit; }
    public double familiarRatio() { return familiarRatio; }
    public List<String> blockedArtists() { return blockedArtists; }
    public List<String> blockedTracks() { return blockedTracks; }
    public String name() { return name; }

    public void setTargetMinutes(int m) { this.targetMinutes = m; }
    public void setAllowExplicit(boolean b) { this.allowExplicit = b; }
    public void setFamiliarRatio(double r) { this.familiarRatio = r; }
    public void setBlockedArtists(List<String> ids) {
        this.blockedArtists = ids == null ? List.of() : List.copyOf(ids);
    }
    public void setName(String n) { this.name = n; }
}
