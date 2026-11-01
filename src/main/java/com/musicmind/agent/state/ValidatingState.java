package com.musicmind.agent.state;

import com.musicmind.agent.PlaylistValidator;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;
import com.musicmind.domain.ValidationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the selection against the spec. Any violation (length, explicit, duplicate, blocked artist) sends the run back to selection
 * (a length miss with a corrected length), at most {@code plan.maxValidationRetries} times; a selection that stays too short
 * is a shortfall and goes to negotiation (F11). Whatever still fails after that ends the run.
 */
public class ValidatingState implements SessionState {

    private static final double MS_PER_MINUTE = 60_000.0;

    @Override
    public String name() {
        return "validating";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        Config config = services.config();
        PlaylistSpec spec = ctx.spec();
        List<Track> tracks = ctx.recommendations.stream().map(Recommendation::track).toList();

        ValidationResult result = services.validator().validate(tracks, spec);
        if (result.passed()) {
            recordSatisfied(ctx, spec, config);
            session.setState(new ExecutingState());
            return;
        }

        PlaylistValidator.DurationStatus length = services.validator().durationStatus(tracks, spec);
        long actualMs = tracks.stream().mapToLong(Track::durationMs).sum();
        double targetMs = spec.targetMinutes() * MS_PER_MINUTE;
        boolean tooShort = length == PlaylistValidator.DurationStatus.TOO_SHORT;
        // UC06 7a: any violation goes back to selection within the bound, whatever rule it broke.
        if (ctx.retries < config.planMaxValidationRetries()) {
            ctx.retries++;
            if (length != PlaylistValidator.DurationStatus.WITHIN) {
                ctx.engineMinutes = correctedMinutes(ctx.engineMinutes, targetMs, actualMs, tooShort, config);
            }
            session.setState(new SelectingState());
            return;
        }
        if (tooShort) {
            int achieved = (int) (actualMs / MS_PER_MINUTE);
            FeasibilityReport report = services.checker().describeShortfall(spec, ctx.retrieval.pool(), achieved);
            report.conflicts().forEach(session::addNotice);
            Negotiator.negotiate(session, report);
            session.setState(new SelectingState());
            return;
        }
        throw new IllegalStateException(String.format(config.wording("validationFailed"),
                String.join("; ", result.violations())));
    }

    /** Scales the asked length by how far the result missed, and always moves it by at least one track. */
    private static int correctedMinutes(int asked, double targetMs, long actualMs, boolean tooShort, Config config) {
        int scaled = actualMs > 0 ? (int) Math.round(asked * targetMs / actualMs) : asked + config.avgTrackMinutes();
        int corrected = tooShort ? Math.max(scaled, asked + config.avgTrackMinutes())
                : Math.min(scaled, asked - config.avgTrackMinutes());
        return Math.max(1, corrected);
    }

    private static void recordSatisfied(RunContext ctx, PlaylistSpec spec, Config config) {
        DecisionTrace trace = ctx.trace;
        if (trace == null) {
            return;
        }
        List<String> satisfied = new ArrayList<>(trace.constraintsSatisfied());
        if (spec.targetMinutes() > 0) {
            satisfied.add(String.format(config.wording("constraintDurationMet"),
                    (int) Math.round(config.playlistDurationTolerance() * 100), spec.targetMinutes()));
        }
        satisfied.add(config.wording("constraintNoDuplicates"));
        ctx.trace = new DecisionTrace(trace.strategyName(), trace.reasons(), satisfied, trace.requestedGenres());
    }
}
