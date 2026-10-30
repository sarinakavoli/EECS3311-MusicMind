package com.musicmind.domain;

import java.util.List;

/** The outcome of validating a track list against a PlaylistSpec. */
public record ValidationResult(boolean passed, List<String> violations) {
    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean isPassed() { return passed; }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failed(List<String> violations) {
        return new ValidationResult(false, violations);
    }
}
