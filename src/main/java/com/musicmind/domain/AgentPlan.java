package com.musicmind.domain;

import java.time.Instant;
import java.util.List;

/**
 * An ordered plan the agent will execute for a request. {@code seedGenres} are the genres the
 * request asked for (from its mood and the stored preferred styles) that steer track selection.
 */
public record AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt, List<String> seedGenres) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        seedGenres = seedGenres == null ? List.of() : List.copyOf(seedGenres);
    }

    public AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt) {
        this(spec, steps, createdAt, List.of());
    }
}
