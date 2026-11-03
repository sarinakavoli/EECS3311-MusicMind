package com.musicmind.agent.state;

import com.musicmind.agent.GoalFitReviewer;
import com.musicmind.agent.PlaylistValidator;
import com.musicmind.agent.Retrieval;
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
 * recommender fills what is left. Selection is deterministic and every pick has a recorded reason. The only
 * part the LLM has is a review of the picks that are not the user's own: it may name picks to take out, and
 * selection then runs again without them (at most {@code goal.pickReviewRounds} times per run). It never adds
 * a track, and if the rest cannot fill the length the previous picks are kept with a notice.
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
        List<Track> chosen = ctx.fittingCandidates();
        Retrieval retrieval = ctx.retrieval;
        Selection selection = select(ctx, retrieval, chosen);
        // The pool was reviewed when it was read, but only part of it. Check what was actually picked as well,
        // and pick again without the tracks that do not suit the goal, a bounded number of times per run.
        while (ctx.pickReviewRounds < config.goalPickReviewRounds()) {
            List<Track> toReview = selection.picks().stream().map(Recommendation::track)
                    .filter(t -> !ctx.candidateIds.contains(t.id())).toList();
            if (toReview.isEmpty()) {
                break;
            }
            ctx.pickReviewRounds++;
            GoalFitReviewer.Result result = services.reviewer().reviewPicks(ctx.request.rawText(), toReview,
                    services.reviewer().genresOf(retrieval));
            if (!result.reviewed()) {
                session.addNotice(config.wording("goalReviewSkipped"));
                break;
            }
            if (result.removedIds().isEmpty()) {
                break;
            }
            Retrieval narrowed = retrieval.without(result.removedIds());
            Selection again = select(ctx, narrowed, chosen);
            List<Track> againTracks = again.picks().stream().map(Recommendation::track).toList();
            if (services.validator().durationStatus(againTracks, ctx.spec()) == PlaylistValidator.DurationStatus.TOO_SHORT) {
                session.addNotice(config.wording("goalReviewPartial"));
                break; // too few other tracks to fill the hour: keep the previous picks and retrieval
            }
            retrieval = narrowed;
            selection = again;
        }
        ctx.retrieval = retrieval;
        ctx.recommendations = selection.picks();
        ctx.trace = selection.trace();
        ctx.usedCandidates = List.copyOf(selection.chosenIds());
        ctx.selectionNotices.forEach(session::removeNotice);
        ctx.selectionNotices = selection.notices();
        selection.notices().forEach(session::addNotice);
        session.setState(new ValidatingState());
    }

    /** One selection: the candidates the spec allows first, then the recommender's picks from the retrieval. */
    private static Selection select(RunContext ctx, Retrieval retrieval, List<Track> chosen) {
        RunServices services = ctx.services;
        Config config = services.config();
        PlaylistSpec spec = ctx.spec();
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
                services.engine().recommend(retrieval, spec.withTargetMinutes(minutesLeft), ctx.feedback).stream()
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
        return new Selection(picks, trace, notices, chosenIds);
    }

    private record Selection(List<Recommendation> picks, DecisionTrace trace, List<String> notices, Set<String> chosenIds) {
    }
}
