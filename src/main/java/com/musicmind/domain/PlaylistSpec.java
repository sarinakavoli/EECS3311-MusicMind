package com.musicmind.domain;

import java.util.List;

/** The structured constraints parsed from a natural-language playlist goal. */
public class PlaylistSpec {
    private int targetMinutes;
    private boolean allowExplicit;
    private double familiarRatio;         // 0.0 .. 1.0 share of familiar tracks
    private List<String> blockedArtists;
    private String name;

    public PlaylistSpec(int targetMinutes, boolean allowExplicit, double familiarRatio,
                        List<String> blockedArtists, String name) {
        this.targetMinutes = targetMinutes;
        this.allowExplicit = allowExplicit;
        this.familiarRatio = familiarRatio;
        this.blockedArtists = blockedArtists == null ? List.of() : List.copyOf(blockedArtists);
        this.name = name;
    }

    public boolean isBlocked(String artistId) {
        return blockedArtists.contains(artistId);
    }

    public int targetMinutes() { return targetMinutes; }
    public boolean allowExplicit() { return allowExplicit; }
    public double familiarRatio() { return familiarRatio; }
    public List<String> blockedArtists() { return blockedArtists; }
    public String name() { return name; }

    public void setTargetMinutes(int m) { this.targetMinutes = m; }
    public void setAllowExplicit(boolean b) { this.allowExplicit = b; }
    public void setFamiliarRatio(double r) { this.familiarRatio = r; }
    public void setName(String n) { this.name = n; }
}
