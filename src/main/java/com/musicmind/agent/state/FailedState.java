package com.musicmind.agent.state;

import com.musicmind.agent.NegotiationCancelledException;
import com.musicmind.domain.AgentSession;

/**
 * Where every error ends up: {@link AgentSession#run()} routes any failure of any phase here. The
 * session is labelled {@code cancelled} when the user declined to negotiate and {@code failed}
 * otherwise, and is saved so history shows what was asked; the failure stays on the session for the caller.
 */
public class FailedState implements SessionState {

    @Override
    public String name() {
        return "failed";
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    public void handle(AgentSession session) {
        RunContext ctx = session.context();
        if (session.failure() instanceof NegotiationCancelledException) {
            session.setStatus("cancelled");
        }
        session.setDerivedData(SessionRecord.derivedData(ctx));
        ctx.services.memory().saveSession(session);
        session.addNotice(ctx.services.memory().warning());
    }
}
