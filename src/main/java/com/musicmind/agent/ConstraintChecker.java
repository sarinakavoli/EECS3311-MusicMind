package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Relaxation;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic feasibility check for F5 and F11: can the spec be met from the candidate pool? It
 * counts the minutes of eligible tracks (not blocked, and not explicit unless allowed) on the
 * familiar and the new side, and compares them with the target length and the requested familiar
 * share. Every problem it finds comes with concrete relaxations the user can accept. No LLM is
 * involved; the LLM only phrases the options afterwards.
 */
public class ConstraintChecker {

    private static final double MS_PER_MINUTE = 60_000.0;

    private final Config config;

    public ConstraintChecker(Config config) {
        this.config = config;
    }

    /** A spec with no target length cannot be short of tracks, so it is feasible. */
    public FeasibilityReport checkFeasibility(PlaylistSpec spec, CandidatePool pool) {
        int target = spec.targetMinutes();
        if (target <= 0) {
            return FeasibilityReport.ok();
        }
        double familiar = minutes(pool.familiar(), spec);
        double fresh = minutes(pool.novel(), spec);
        double available = familiar + fresh;
        double tolerance = config.playlistDurationTolerance();
        double ratio = spec.familiarRatio();
        double explicitMinutes = spec.allowExplicit() ? 0 : explicitMinutes(pool, spec);

        List<String> conflicts = new ArrayList<>();
        List<Relaxation> relaxations = new ArrayList<>();
        if (available <= 0) {
            conflicts.add(config.wording("conflictNoTracks"));
        } else if (available < target * (1 - tolerance)) {
            conflicts.add(String.format(config.wording("conflictShort"), target, (int) available));
            addShorten(relaxations, maxMinutesAtRatio(familiar, fresh, ratio), target);
        } else {
            if (familiar < target * ratio * (1 - tolerance)) {
                conflicts.add(String.format(config.wording("conflictFamiliar"), percent(ratio),
                        (int) familiar, target));
            }
            if (fresh < target * (1 - ratio) * (1 - tolerance)) {
                conflicts.add(String.format(config.wording("conflictNovel"), percent(1 - ratio),
                        (int) fresh, target));
            }
            if (!conflicts.isEmpty()) {
                addShorten(relaxations, maxMinutesAtRatio(familiar, fresh, ratio), target);
                addRatio(relaxations, ratio, familiar, fresh, target);
            }
        }
        if (!conflicts.isEmpty() && explicitMinutes > 0) {
            relaxations.add(new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, config.wording("relaxExplicit")));
        }
        return conflicts.isEmpty() ? FeasibilityReport.ok() : FeasibilityReport.infeasible(conflicts, relaxations);
    }

    /**
     * The report for a selection that came out shorter than the target even though the pool looked
     * sufficient: it offers the minutes that were reached, and explicit tracks if they would add some.
     */
    public FeasibilityReport describeShortfall(PlaylistSpec spec, CandidatePool pool, int achievedMinutes) {
        List<Relaxation> relaxations = new ArrayList<>();
        addShorten(relaxations, achievedMinutes, spec.targetMinutes());
        if (!spec.allowExplicit() && explicitMinutes(pool, spec) > 0) {
            relaxations.add(new Relaxation(Relaxation.Kind.ALLOW_EXPLICIT, 1, config.wording("relaxExplicit")));
        }
        return FeasibilityReport.infeasible(
                List.of(String.format(config.wording("conflictShortfall"), achievedMinutes, spec.targetMinutes())),
                relaxations);
    }

    /** Length limits only; see {@link #checkEdit(List, List, PlaylistSpec)}. */
    public FeasibilityReport checkEdit(List<Track> before, List<com.musicmind.agent.command.PlaylistCommand> commands) {
        return checkEdit(before, commands, null);
    }

    /**
     * Whether an edit can be applied: the playlist it would leave must hold between {@code playlist.minTracks}
     * and {@code playlist.maxTracks} tracks (an edit that empties it is the usual case) and, when a spec is
     * given, must not introduce an explicit, duplicate or blocked-artist violation (F7). The duration target is
     * not part of this check: an edit states its own length. When the edit fails, the relaxation is to apply
     * only the first steps, the most that still passes; none is offered if even the first step fails.
     * Nothing is sent to Spotify.
     */
    public FeasibilityReport checkEdit(List<Track> before, List<com.musicmind.agent.command.PlaylistCommand> commands,
                                       PlaylistSpec spec) {
        List<Track> all = afterFirst(before, commands, commands.size());
        List<String> introduced = introduced(before, all, spec);
        if (withinLimits(all) && introduced.isEmpty()) {
            return FeasibilityReport.ok();
        }
        int resulting = all.size();
        List<String> conflicts = new ArrayList<>();
        if (resulting < config.playlistMinTracks()) {
            conflicts.add(String.format(config.wording("conflictEditEmpty"), resulting, config.playlistMinTracks()));
        } else if (resulting > config.playlistMaxTracks()) {
            conflicts.add(String.format(config.wording("conflictEditFull"), resulting, config.playlistMaxTracks()));
        }
        if (!introduced.isEmpty()) {
            conflicts.add(String.format(config.wording("conflictEditViolates"), String.join("; ", introduced)));
        }
        List<Relaxation> relaxations = new ArrayList<>();
        for (int steps = commands.size() - 1; steps >= 1; steps--) {
            List<Track> left = afterFirst(before, commands, steps);
            if (withinLimits(left) && introduced(before, left, spec).isEmpty()) {
                relaxations.add(new Relaxation(Relaxation.Kind.APPLY_PARTIAL, steps,
                        String.format(config.wording("relaxPartial"), steps, commands.size(), left.size())));
                break;
            }
        }
        return FeasibilityReport.infeasible(conflicts, relaxations);
    }

    /** What the edit introduces against the spec; empty without a spec. */
    public List<String> introduced(List<Track> before, List<Track> after, PlaylistSpec spec) {
        return spec == null ? List.of() : new PlaylistValidator(config).introducedViolations(before, after, spec);
    }

    private boolean withinLimits(List<Track> tracks) {
        return tracks.size() >= config.playlistMinTracks() && tracks.size() <= config.playlistMaxTracks();
    }

    private static List<Track> afterFirst(List<Track> before, List<com.musicmind.agent.command.PlaylistCommand> commands,
                                          int steps) {
        List<Track> tracks = before;
        for (int i = 0; i < steps; i++) {
            tracks = commands.get(i).simulate(tracks);
        }
        return tracks;
    }

    /** Longest playlist that fills both sides at the requested share from the minutes available. */
    private static int maxMinutesAtRatio(double familiar, double fresh, double ratio) {
        double byFamiliar = ratio > 0 ? familiar / ratio : Double.MAX_VALUE;
        double byNovel = ratio < 1 ? fresh / (1 - ratio) : Double.MAX_VALUE;
        return (int) Math.floor(Math.min(byFamiliar, byNovel));
    }

    private void addShorten(List<Relaxation> out, int minutes, int target) {
        if (minutes >= config.parserMinTargetMinutes() && minutes < target) {
            out.add(new Relaxation(Relaxation.Kind.SHORTEN_DURATION, minutes,
                    String.format(config.wording("relaxShorten"), minutes)));
        }
    }

    /** The share closest to the requested one that both sides can fill for the full length. */
    private void addRatio(List<Relaxation> out, double ratio, double familiar, double fresh, int target) {
        double low = Math.max(0.0, 1 - fresh / target);
        double high = Math.min(1.0, familiar / target);
        double nearest = low > high ? (low + high) / 2 : Math.max(low, Math.min(high, ratio));
        double rounded = Math.floor(nearest * 100) / 100.0;
        if (Math.abs(rounded - ratio) > 1e-9) {
            out.add(new Relaxation(Relaxation.Kind.CHANGE_FAMILIAR_RATIO, rounded,
                    String.format(config.wording("relaxFamiliar"), percent(rounded))));
        }
    }

    /** Whether the spec lets a track into the playlist: not blocked, and not explicit unless allowed. */
    public static boolean eligible(Track t, PlaylistSpec spec) {
        return !spec.isTrackBlocked(t.id()) && t.artistIds().stream().noneMatch(spec::isBlocked) && (spec.allowExplicit() || !t.isExplicit());
    }

    private static double minutes(List<Track> tracks, PlaylistSpec spec) {
        return tracks.stream().filter(t -> eligible(t, spec)).mapToLong(Track::durationMs).sum() / MS_PER_MINUTE;
    }

    /** Minutes of explicit tracks (by artists that are not blocked) that the spec currently keeps out. */
    private static double explicitMinutes(CandidatePool pool, PlaylistSpec spec) {
        PlaylistSpec allowing = spec.withAllowExplicit(true);
        return minutes(pool.familiar(), allowing) + minutes(pool.novel(), allowing)
                - minutes(pool.familiar(), spec) - minutes(pool.novel(), spec);
    }

    private static int percent(double share) {
        return (int) Math.round(share * 100);
    }
}
