package com.musicmind.domain;

import java.time.Instant;

/** An accept/reject verdict the user gave on a track. */
public record Feedback(String trackId, Verdict verdict, Instant createdAt) {
}
