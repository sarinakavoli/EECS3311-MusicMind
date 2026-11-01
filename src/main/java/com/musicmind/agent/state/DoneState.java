package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

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

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        session.setTrace(ctx.trace);
        session.setDerivedData(SessionRecord.derivedData(ctx));
        ctx.services.memory().saveSession(session);
        session.addNotice(ctx.services.memory().warning());
        try {
            session.setExplanation(ctx.services.explainer().explain(session));
        } catch (RuntimeException e) {
            // The playlist exists; failing to explain it must not turn the run into a failure.
            session.setExplanation(ctx.services.config().wording("explainNoTrace"));
        }
    }
}
