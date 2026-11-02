package com.musicmind.domain;

/**
 * One way the user can loosen a playlist request that cannot be met as stated (F11). {@code value}
 * is the new length in minutes, the new familiar share, or (APPLY_PARTIAL) how many of an edit's steps to run; {@code description} is the plain-language
 * option shown to the user.
 */
public record Relaxation(Kind kind, double value, String description) {

    public enum Kind { SHORTEN_DURATION, ALLOW_EXPLICIT, CHANGE_FAMILIAR_RATIO, APPLY_PARTIAL }

    /** A copy of the spec with this relaxation applied; the original is unchanged. */
    public PlaylistSpec applyTo(PlaylistSpec spec) {
        return switch (kind) {
            case SHORTEN_DURATION -> spec.withTargetMinutes((int) value);
            case ALLOW_EXPLICIT -> spec.withAllowExplicit(true);
            case APPLY_PARTIAL -> spec; // changes the edit (how many steps run), not a playlist spec
            case CHANGE_FAMILIAR_RATIO -> spec.withFamiliarRatio(value);
        };
    }
}
