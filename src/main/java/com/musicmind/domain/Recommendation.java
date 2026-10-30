package com.musicmind.domain;

/** A recommended track together with the reason it was selected. */
public record Recommendation(Track track, String reason,
                             double familiarity, double score) {
}
