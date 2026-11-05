package com.musicmind.domain;

import java.time.Instant;

/** One play from Spotify's recently-played list: the track and when it was played (never stored). */
public record Play(Track track, Instant playedAt) {
}
