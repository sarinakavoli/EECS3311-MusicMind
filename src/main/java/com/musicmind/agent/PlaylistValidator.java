package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Track;
import com.musicmind.domain.ValidationResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic constraint checker (feature F7). Runs an ordered set of
 * machine-checkable rules over a track list and reports every violation.
 * No LLM is involved; its result drives the adjustment loops in F5 and F6. Rules, in order: explicit content,
 * duplicates, target duration, blocked artists and tracks, minimum and maximum number of tracks.
 */
public class PlaylistValidator {

    /** Rule names used as keys of {@link ValidationResult#offending()}. */
    public static final String RULE_EXPLICIT = "explicit";
    public static final String RULE_DUPLICATES = "duplicates";
    public static final String RULE_BLOCKED_ARTIST = "blockedArtist";
    public static final String RULE_BLOCKED_TRACK = "blockedTrack";
    public static final String RULE_DURATION = "duration";
    public static final String RULE_MIN_LENGTH = "minLength";
    public static final String RULE_MAX_LENGTH = "maxLength";

    private final Config config;
    /** Allowed deviation from the target duration, from {@code playlist.durationTolerance}. */
    private final double durationTolerance;

    public PlaylistValidator() {
        this(Config.defaults());
    }

    public PlaylistValidator(Config config) {
        this.config = config;
        this.durationTolerance = config.playlistDurationTolerance();
    }

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
        Map<String, List<String>> offending = new LinkedHashMap<>();

        // Rule 1: explicit content
        if (spec != null && !spec.allowExplicit()) {
            List<String> explicit = tracks.stream().filter(Track::isExplicit).map(Track::id).toList();
            if (!explicit.isEmpty()) {
                violations.add(explicit.size() + " explicit track(s) present but explicit content is not allowed");
                offending.put(RULE_EXPLICIT, explicit);
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
            offending.put(RULE_DUPLICATES, List.copyOf(duplicates));
        }

        // Rule 3: target duration within tolerance
        if (spec != null && durationStatus(tracks, spec) != DurationStatus.WITHIN) {
            long actualMs = tracks.stream().mapToLong(Track::durationMs).sum();
            violations.add(String.format(
                    "total duration %d min is outside the target of %d min (±%d%%)",
                    actualMs / 60_000, spec.targetMinutes(), (int) (durationTolerance * 100)));
            offending.put(RULE_DURATION, List.of());
        }

        // Rule 4: blocked artists
        if (spec != null) {
            List<String> blocked = new ArrayList<>();
            for (Track t : tracks) {
                for (String artistId : t.artistIds()) {
                    if (spec.isBlocked(artistId)) {
                        violations.add("blocked artist present on track " + t.id() + ": " + artistId);
                        blocked.add(t.id());
                        break;
                    }
                }
            }
            if (!blocked.isEmpty()) {
                offending.put(RULE_BLOCKED_ARTIST, blocked);
            }
        }

        // Rule 4b: blocked tracks
        if (spec != null) {
            List<String> blocked = tracks.stream().map(Track::id).filter(spec::isTrackBlocked).distinct().toList();
            blocked.forEach(id -> violations.add(String.format(config.wording("validateBlockedTrack"), id)));
            if (!blocked.isEmpty()) {
                offending.put(RULE_BLOCKED_TRACK, blocked);
            }
        }

        // Rule 5: minimum and maximum length (number of tracks)
        if (tracks.size() < config.playlistMinTracks()) {
            violations.add(String.format(config.wording("validateTooFew"), tracks.size(), config.playlistMinTracks()));
            offending.put(RULE_MIN_LENGTH, List.of());
        } else if (tracks.size() > config.playlistMaxTracks()) {
            violations.add(String.format(config.wording("validateTooMany"), tracks.size(), config.playlistMaxTracks()));
            offending.put(RULE_MAX_LENGTH, List.of());
        }

        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(violations, offending);
    }

    /**
     * The explicit, duplicate and blocked-artist violations that {@code after} has and {@code before} did not,
     * each as {@code "rule: track ids"}. An edit is judged by what it introduces, so a problem the playlist
     * already had cannot make an unrelated edit fail. Length rules are left to {@link ConstraintChecker}.
     */
    public List<String> introducedViolations(List<Track> before, List<Track> after, PlaylistSpec spec) {
        Map<String, List<String>> old = validate(before, spec).offending();
        List<String> introduced = new ArrayList<>();
        for (Map.Entry<String, List<String>> rule : validate(after, spec).offending().entrySet()) {
            String name = rule.getKey();
            if (!name.equals(RULE_EXPLICIT) && !name.equals(RULE_DUPLICATES) && !name.equals(RULE_BLOCKED_ARTIST)
                    && !name.equals(RULE_BLOCKED_TRACK)) {
                continue;
            }
            Set<String> known = new HashSet<>(old.getOrDefault(name, List.of()));
            List<String> fresh = new ArrayList<>(new LinkedHashSet<>(rule.getValue()));
            fresh.removeAll(known);
            if (!fresh.isEmpty()) {
                introduced.add(name + ": " + String.join(", ", fresh));
            }
        }
        return introduced;
    }
}
