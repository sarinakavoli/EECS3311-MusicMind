package com.musicmind.agent.state;

import com.musicmind.agent.CandidatePool;
import com.musicmind.agent.GoalFitReviewer;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.FeasibilityReport;
import com.musicmind.domain.Track;
import com.musicmind.music.AuthExpiredException;
import com.musicmind.music.MusicServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the candidate pools through the tools, then checks the request can be met from them. An
 * infeasible request is negotiated (F11) until it is feasible, the user cancels or nothing can help.
 */
public class RetrievingState implements SessionState {

    @Override
    public String name() {
        return "retrieving";
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        RunServices services = ctx.services;
        ctx.profile = services.profile().get();
        ctx.retrieval = services.engine().retrieve(ctx.profile, ctx.spec(), true, ctx.plan.seedGenres(),
                ctx.plan.searchTerms());
        if (ctx.retrieval.allReadsFailed()) {
            throw new MusicServiceException(String.format(services.config().wording("discoverAllReadsFailed"),
                    ctx.retrieval.failedReads()));
        }
        reviewAgainstGoal(session, ctx);
        ctx.candidates = readCandidates(ctx);
        FeasibilityReport report = services.checker().checkFeasibility(ctx.spec(), ctx.pool());
        while (!report.feasible()) {
            report.conflicts().forEach(session::addNotice);
            Negotiator.negotiate(session, report);
            report = services.checker().checkFeasibility(ctx.spec(), ctx.pool());
        }
        session.setState(new SelectingState());
    }

    /**
     * Takes out the retrieved tracks the goal-fit review rejects. Only retrieved tracks are reviewed: the user's
     * candidates are never sent or removed, even where one is also in the pool. A review that could not be used leaves the
     * tracks as they are and adds a notice.
     */
    private static void reviewAgainstGoal(AgentSession session, RunContext ctx) {
        CandidatePool pool = ctx.retrieval.pool();
        List<Track> toReview = new ArrayList<>(pool.novel());
        toReview.addAll(pool.familiar());
        toReview.removeIf(t -> ctx.candidateIds.contains(t.id())); // a track the user picked may also be in the pool
        GoalFitReviewer.Result result = ctx.services.reviewer().review(ctx.request.rawText(), toReview,
                ctx.services.reviewer().genresOf(ctx.retrieval));
        if (!result.reviewed()) {
            session.addNotice(ctx.services.config().wording("goalReviewSkipped"));
        } else if (!result.removedIds().isEmpty()) {
            ctx.retrieval = ctx.retrieval.without(result.removedIds());
        }
    }

    /** The candidates Spotify still has; one that cannot be read is left out, and the selection reports it. */
    private static List<Track> readCandidates(RunContext ctx) {
        List<Track> found = new ArrayList<>();
        for (String id : ctx.candidateIds) {
            try {
                found.add(ctx.services.music().getTrack(id));
            } catch (AuthExpiredException e) {
                throw e;
            } catch (MusicServiceException e) {
                // gone from Spotify or not playable here: the run goes on without it
            }
        }
        return found;
    }
}
