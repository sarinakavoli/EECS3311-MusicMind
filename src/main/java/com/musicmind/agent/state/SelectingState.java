package com.musicmind.agent.state;

import com.musicmind.agent.strategy.BalancedStrategy;
import com.musicmind.config.Config;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.Track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Has the recommender pick the tracks with the {@link BalancedStrategy}, from the pools already read.
 * The user's candidate tracks (F3) go first, as far as the constraints and the length allow, and the
 * recommender fills what is left. The LLM takes no part in this: selection is deterministic and every pick
 * has a recorded reason.
 */
public class SelectingState implements SessionState {

    private static final double MS_PER_MINUTE = 60_000.0;

    @Override
    public String name() {
        return "selecting";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        Config config = services.config();
        PlaylistSpec spec = ctx.spec();
        List<Track> chosen = ctx.fittingCandidates();
        long chosenMs = chosen.stream().mapToLong(Track::durationMs).sum();
        // Validation may have moved the length the recommender aims for; every other constraint is the spec's.
        int minutesLeft = ctx.engineMinutes - (int) Math.round(chosenMs / MS_PER_MINUTE);

        List<Recommendation> picks = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        Set<String> chosenIds = new LinkedHashSet<>();
        for (Track t : chosen) {
            picks.add(new Recommendation(t, config.wording("reasonCandidate"), ctx.profile.familiarity(t), 1.0));
            reasons.put(t.id(), config.wording("reasonCandidate"));
            chosenIds.add(t.id());
        }

        List<String> notices = new ArrayList<>();
        DecisionTrace trace;
        if (chosen.isEmpty() || minutesLeft >= config.avgTrackMinutes()) {
            synchronized (services.engine()) {
                services.engine().setStrategy(new BalancedStrategy(config));
                services.engine().recommend(ctx.retrieval, spec.withTargetMinutes(minutesLeft), ctx.feedback).stream()
                        .filter(r -> !chosenIds.contains(r.track().id())).forEach(picks::add);
                trace = services.engine().lastTrace();
                notices.addAll(services.engine().lastNotices());
            }
            trace.reasons().forEach(reasons::putIfAbsent);
            trace = new DecisionTrace(trace.strategyName(), reasons, trace.constraintsSatisfied(), trace.requestedGenres());
        } else {
            trace = new DecisionTrace(BalancedStrategy.class.getSimpleName(), reasons, List.of(), ctx.plan.seedGenres());
        }
        int leftOut = ctx.candidateIds.size() - chosen.size();
        if (leftOut > 0) {
            notices.add(String.format(config.wording("candidatesSkipped"), leftOut));
        }
        ctx.recommendations = picks;
        ctx.trace = trace;
        ctx.usedCandidates = List.copyOf(chosenIds);
        ctx.selectionNotices.forEach(session::removeNotice);
        ctx.selectionNotices = notices;
        notices.forEach(session::addNotice);
        session.setState(new ValidatingState());
    }
}
