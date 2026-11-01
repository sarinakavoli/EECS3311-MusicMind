package com.musicmind.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of validating a track list against a PlaylistSpec: one readable line per violated rule
 * and, for the rules that single out tracks, the offending track ids keyed by rule name.
 */
public record ValidationResult(boolean passed, List<String> violations, Map<String, List<String>> offending) {
    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (offending != null) {
            offending.forEach((rule, ids) -> copy.put(rule, List.copyOf(ids)));
        }
        offending = Map.copyOf(copy);
    }

    public ValidationResult(boolean passed, List<String> violations) {
        this(passed, violations, Map.of());
    }

    public boolean isPassed() { return passed; }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of(), Map.of());
    }

    public static ValidationResult failed(List<String> violations) {
        return new ValidationResult(false, violations, Map.of());
    }

    public static ValidationResult failed(List<String> violations, Map<String, List<String>> offending) {
        return new ValidationResult(false, violations, offending);
    }
}
