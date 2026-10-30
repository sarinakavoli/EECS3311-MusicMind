package com.musicmind.domain;

/**
 * A standing, application-specific preference that Spotify does not represent
 * (disliked artists, preferred styles, explicit policy, familiar-vs-explore bias).
 */
public record UserPreference(String key, String value,
                             boolean explicitAllowed, double familiarBias) {
}
