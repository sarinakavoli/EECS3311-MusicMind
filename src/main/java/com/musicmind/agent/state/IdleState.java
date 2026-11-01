package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

/** Before the run starts; moves on to planning. */
public class IdleState implements SessionState {

    @Override
    public String name() {
        return "idle";
    }

    @Override
    public void handle(AgentSession session) {
        session.setState(new PlanningState());
    }
}
