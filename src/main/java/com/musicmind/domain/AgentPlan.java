package com.musicmind.domain;

import java.time.Instant;
import java.util.List;

/**
 * An ordered plan the agent will execute for a request. {@code seedGenres} are the genres the
 * request asked for (from its mood and the stored preferred styles) that steer track selection;
 * {@code searchTerms} are short phrases for the activity the goal names (for example "gym workout"),
 * searched on Spotify next to the genres.
 */
public record AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt, List<String> seedGenres,
                        List<String> searchTerms) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        seedGenres = seedGenres == null ? List.of() : List.copyOf(seedGenres);
        searchTerms = searchTerms == null ? List.of() : List.copyOf(searchTerms);
    }

    public AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt, List<String> seedGenres) {
        this(spec, steps, createdAt, seedGenres, List.of());
    }

    public AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt) {
        this(spec, steps, createdAt, List.of(), List.of());
    }
}
