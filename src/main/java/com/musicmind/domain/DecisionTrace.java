package com.musicmind.domain;

import java.util.List;
import java.util.Map;

/**
 * Why the agent chose what it chose: the strategy used, a reason per selected
 * track id, the constraints the final selection satisfied and the genres the goal
 * asked for. Holds only MusicMind-derived data (ids, reasons and the user's own genre
 * words), never Spotify content.
 *
 * <p>{@code requestedGenres} are genres that came from the goal (a mood, a preferred style), not from
 * the user's listening: an explanation must not call them the user's taste. A trace stored before the
 * field existed loads with none.
 */
public record DecisionTrace(String strategyName, Map<String, String> reasons,
                            List<String> constraintsSatisfied, List<String> requestedGenres) {
    public DecisionTrace {
        reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        constraintsSatisfied = constraintsSatisfied == null ? List.of() : List.copyOf(constraintsSatisfied);
        requestedGenres = requestedGenres == null ? List.of() : List.copyOf(requestedGenres);
    }

    /** A trace with no requested genres. */
    public DecisionTrace(String strategyName, Map<String, String> reasons, List<String> constraintsSatisfied) {
        this(strategyName, reasons, constraintsSatisfied, List.of());
    }
}
