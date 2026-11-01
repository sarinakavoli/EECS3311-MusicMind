package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
import com.musicmind.domain.ValidationResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic constraint checker (feature F7). Runs an ordered set of
 * machine-checkable rules over a track list and reports every violation.
 * No LLM is involved; its result drives the adjustment loops in F5 and F6.
 */
public class PlaylistValidator {

    /** Allowed deviation from the target duration, from {@code playlist.durationTolerance}. */
    private final double durationTolerance = Config.defaults().playlistDurationTolerance();

    /** Where the total length falls against the target; a spec with no target is always within. */
    public enum DurationStatus { TOO_SHORT, WITHIN, TOO_LONG }

    /** The single place the length rule is computed; {@link #validate} and the agent's retry loop both use it. */
    public DurationStatus durationStatus(List<Track> tracks, PlaylistSpec spec) {
        if (spec == null || spec.targetMinutes() <= 0) {
            return DurationStatus.WITHIN;
        }
        long targetMs = spec.targetMinutes() * 60_000L;
        long actualMs = tracks.stream().mapToLong(Track::durationMs).sum();
        if (actualMs < Math.round(targetMs * (1 - durationTolerance))) {
            return DurationStatus.TOO_SHORT;
        }
        return actualMs > Math.round(targetMs * (1 + durationTolerance)) ? DurationStatus.TOO_LONG : DurationStatus.WITHIN;
    }

    public ValidationResult validate(List<Track> tracks, PlaylistSpec spec) {
        List<String> violations = new ArrayList<>();

        // Rule 1: explicit content
        if (spec != null && !spec.allowExplicit()) {
            long explicit = tracks.stream().filter(Track::isExplicit).count();
            if (explicit > 0) {
                violations.add(explicit + " explicit track(s) present but explicit content is not allowed");
            }
        }

        // Rule 2: duplicate tracks (by id)
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Track t : tracks) {
            if (!seen.add(t.id())) duplicates.add(t.id());
        }
        if (!duplicates.isEmpty()) {
            violations.add(duplicates.size() + " duplicate track(s): " + duplicates);
        }

        // Rule 3: blocked artists
        if (spec != null) {
            for (Track t : tracks) {
                for (String artistId : t.artistIds()) {
                    if (spec.isBlocked(artistId)) {
                        violations.add("blocked artist present on track " + t.id() + ": " + artistId);
                        break;
                    }
                }
            }
        }

        // Rule 4: target duration within tolerance
        if (spec != null && durationStatus(tracks, spec) != DurationStatus.WITHIN) {
            long actualMs = tracks.stream().mapToLong(Track::durationMs).sum();
            violations.add(String.format(
                    "total duration %d min is outside the target of %d min (±%d%%)",
                    actualMs / 60_000, spec.targetMinutes(), (int) (durationTolerance * 100)));
        }

        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(violations);
    }
}
