package com.musicmind.domain;

import java.util.List;
import java.util.Map;

/**
 * Why the agent chose what it chose: the strategy used, a reason per selected
 * track id, and the constraints the final selection satisfied. Holds only
 * MusicMind-derived data (ids and reasons), never Spotify content.
 */
public record DecisionTrace(String strategyName, Map<String, String> reasons,
                            List<String> constraintsSatisfied) {
    public DecisionTrace {
        reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        constraintsSatisfied = constraintsSatisfied == null ? List.of() : List.copyOf(constraintsSatisfied);
    }
}
