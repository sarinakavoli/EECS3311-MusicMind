package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

import java.util.HashMap;
import java.util.Map;

/** Final state of a successful run: saves the session (with its trace) and produces the explanation. */
public class DoneState implements SessionState {

    @Override
    public String name() {
        return "done";
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    /** Checks each removal at once: the save that follows clears any database warning. */
    private static void removeUsedCandidates(AgentSession session, RunContext ctx) {
        if (ctx.usedCandidates.isEmpty()) {
            return;
        }
        session.addNotice(String.format(ctx.services.config().wording("candidatesUsed"), ctx.usedCandidates.size()));
        for (String id : ctx.usedCandidates) {
            ctx.services.memory().removeCandidate(id);
            if (ctx.services.memory().warning() != null) {
                session.addNotice(ctx.services.config().wording("candidatesNotRemoved"));
            }
        }
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        session.setTrace(ctx.trace);
        removeUsedCandidates(session, ctx);
        session.setDerivedData(SessionRecord.derivedData(ctx));
        ctx.services.memory().saveSession(session);
        session.addNotice(ctx.services.memory().warning());
        try {
            // Titles are shown in this run's explanation only; the stored session keeps ids and reasons.
            Map<String, String> titles = new HashMap<>();
            ctx.recommendations.forEach(r -> titles.put(r.track().id(), r.track().title()));
            session.setExplanation(ctx.services.explainer().explain(session, titles));
        } catch (RuntimeException e) {
            // The playlist exists; failing to explain it must not turn the run into a failure.
            session.setExplanation(ctx.services.config().wording("explainNoTrace"));
        }
    }
}
