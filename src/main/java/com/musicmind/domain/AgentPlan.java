package com.musicmind.domain;

import java.time.Instant;
import java.util.List;

/** An ordered plan the agent will execute for a request. */
public record AgentPlan(PlaylistSpec spec, List<String> steps, Instant createdAt) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
