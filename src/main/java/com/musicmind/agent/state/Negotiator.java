package com.musicmind.agent.state;

import com.musicmind.agent.InfeasibleRequestException;
import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Relaxation;

import java.util.List;
import java.util.Optional;

/**
 * One round of F11 for the states that find a request cannot be met: word the options, ask the
 * handler, and regenerate the plan with the relaxation the user chose. Everything else ends the run
 * with an explanation, before anything has been written to Spotify.
 */
final class Negotiator {

    private Negotiator() {
    }

    /**
     * @throws InfeasibleRequestException      if no relaxation helps, nobody can be asked or the rounds are used up
     * @throws NegotiationCancelledException if the user declines every option
     */
    static void negotiate(AgentSession session, FeasibilityReport report) {
        RunContext ctx = session.context();
        Config config = ctx.services.config();
        String conflicts = String.join(" ", report.conflicts());
        if (report.relaxations().isEmpty()) {
            throw new InfeasibleRequestException(conflicts + " " + config.wording("negotiationNone"));
        }
        if (ctx.handler == null) {
            throw new InfeasibleRequestException(conflicts + " " + String.format(
                    config.wording("negotiationNoHandler"), String.join("; ", report.relaxationOptions())));
        }
        if (ctx.negotiationRounds >= config.planMaxNegotiationRounds()) {
            throw new InfeasibleRequestException(conflicts + " " + String.format(
                    config.wording("negotiationExhausted"), ctx.negotiationRounds));
        }
        List<String> phrased = ctx.services.planner().phraseOptions(report);
        Optional<Relaxation> choice = ctx.handler.choose(report, phrased);
        if (choice.isEmpty()) {
            throw new NegotiationCancelledException(config.wording("negotiationCancelled"));
        }
        Relaxation chosen = choice.get();
        if (!report.relaxations().contains(chosen)) {
            throw new IllegalArgumentException(config.wording("negotiationBadChoice"));
        }
        ctx.negotiationRounds++;
        ctx.plan = ctx.services.planner().regenerate(ctx.plan, chosen);
        ctx.engineMinutes = ctx.spec().targetMinutes();
        ctx.retries = 0;
        session.setPlanSteps(ctx.plan.steps());
        session.addNotice(String.format(config.wording("negotiationAdjusted"), chosen.description()));
    }
}
