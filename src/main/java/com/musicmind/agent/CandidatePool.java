package com.musicmind.agent;

import com.musicmind.domain.Track;

import java.util.List;

/**
 * The candidate tracks read for a playlist, split the way the recommender will use them:
 * {@code familiar} are tracks the user already has, {@code novel} are the rest. Each list is
 * distinct by track id and not yet filtered by the playlist's constraints, so the
 * {@link ConstraintChecker} can tell what the constraints would remove.
 */
public record CandidatePool(List<Track> familiar, List<Track> novel) {
    public CandidatePool {
        familiar = familiar == null ? List.of() : List.copyOf(familiar);
        novel = novel == null ? List.of() : List.copyOf(novel);
    }
}
