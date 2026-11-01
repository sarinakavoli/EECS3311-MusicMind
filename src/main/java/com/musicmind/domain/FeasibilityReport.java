package com.musicmind.domain;

import java.util.List;

/**
 * Whether a playlist spec can be met from the candidate pool. When it cannot,
 * {@code conflicts} says why and {@code relaxationOptions} lists, in words, what the user
 * could loosen (F11); {@code relaxations} are the same options in structured form, in the same
 * order, so the chosen one can be applied.
 */
public record FeasibilityReport(boolean feasible, List<String> conflicts, List<String> relaxationOptions,
                                List<Relaxation> relaxations) {
    public FeasibilityReport {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        relaxationOptions = relaxationOptions == null ? List.of() : List.copyOf(relaxationOptions);
        relaxations = relaxations == null ? List.of() : List.copyOf(relaxations);
    }

    /** A report with options in words only. */
    public FeasibilityReport(boolean feasible, List<String> conflicts, List<String> relaxationOptions) {
        this(feasible, conflicts, relaxationOptions, List.of());
    }

    public static FeasibilityReport ok() {
        return new FeasibilityReport(true, List.of(), List.of(), List.of());
    }

    /** An infeasible report whose option wording is taken from the relaxations. */
    public static FeasibilityReport infeasible(List<String> conflicts, List<Relaxation> relaxations) {
        return new FeasibilityReport(false, conflicts, relaxations.stream().map(Relaxation::description).toList(),
                relaxations);
    }
}
