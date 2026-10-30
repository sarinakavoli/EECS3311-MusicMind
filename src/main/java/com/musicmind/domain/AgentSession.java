package com.musicmind.domain;

import com.musicmind.agent.state.SessionState;

import java.time.Instant;

/**
 * One run of the agent. Its lifecycle is modelled with the State pattern:
 * the session delegates behaviour to its current {@link SessionState}.
 */
public class AgentSession {
    private final String id;
    private final Instant startedAt;
    private String status;
    private SessionState state;

    public AgentSession(String id, Instant startedAt, SessionState initial) {
        this.id = id;
        this.startedAt = startedAt;
        this.state = initial;
        this.status = initial == null ? "idle" : initial.name();
    }

    public void setState(SessionState state) {
        this.state = state;
        this.status = state == null ? status : state.name();
    }

    public SessionState getState() { return state; }

    public String id() { return id; }
    public Instant startedAt() { return startedAt; }
    public String status() { return status; }
}
