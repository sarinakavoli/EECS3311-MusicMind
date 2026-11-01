package com.musicmind.domain;

/**
 * One way the user can loosen a playlist request that cannot be met as stated (F11). {@code value}
 * is the new length in minutes or the new familiar share; {@code description} is the plain-language
 * option shown to the user.
 */
public record Relaxation(Kind kind, double value, String description) {

    public enum Kind { SHORTEN_DURATION, ALLOW_EXPLICIT, CHANGE_FAMILIAR_RATIO }

    /** A copy of the spec with this relaxation applied; the original is unchanged. */
    public PlaylistSpec applyTo(PlaylistSpec spec) {
        return switch (kind) {
            case SHORTEN_DURATION -> new PlaylistSpec((int) value, spec.allowExplicit(), spec.familiarRatio(),
                    spec.blockedArtists(), spec.name());
            case ALLOW_EXPLICIT -> new PlaylistSpec(spec.targetMinutes(), true, spec.familiarRatio(),
                    spec.blockedArtists(), spec.name());
            case CHANGE_FAMILIAR_RATIO -> new PlaylistSpec(spec.targetMinutes(), spec.allowExplicit(), value,
                    spec.blockedArtists(), spec.name());
        };
    }
}
