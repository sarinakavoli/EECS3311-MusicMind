package com.musicmind.domain;

/** A Spotify album (immutable value object). */
public record Album(String id, String name, String releaseDate) {
}
