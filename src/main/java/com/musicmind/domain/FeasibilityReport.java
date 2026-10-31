package com.musicmind.domain;

import java.util.List;

/**
 * Whether a playlist spec can be met from the candidate pool. When it cannot,
 * {@code conflicts} says why and {@code relaxationOptions} lists what the user
 * could loosen (F11).
 */
public record FeasibilityReport(boolean feasible, List<String> conflicts, List<String> relaxationOptions) {
    public FeasibilityReport {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        relaxationOptions = relaxationOptions == null ? List.of() : List.copyOf(relaxationOptions);
    }
}
