package com.musicmind.agent.state;

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
        ctx.retrieval = services.engine().retrieve(ctx.profile, ctx.spec(), true, ctx.plan.seedGenres());
        if (ctx.retrieval.allReadsFailed()) {
            throw new MusicServiceException(String.format(services.config().wording("discoverAllReadsFailed"),
                    ctx.retrieval.failedReads()));
        }
        ctx.candidates = readCandidates(ctx);
        FeasibilityReport report = services.checker().checkFeasibility(ctx.spec(), ctx.retrieval.pool());
        while (!report.feasible()) {
            report.conflicts().forEach(session::addNotice);
            Negotiator.negotiate(session, report);
            report = services.checker().checkFeasibility(ctx.spec(), ctx.retrieval.pool());
        }
        session.setState(new SelectingState());
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
