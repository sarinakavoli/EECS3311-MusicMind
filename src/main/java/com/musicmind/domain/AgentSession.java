package com.musicmind.domain;

import com.musicmind.agent.state.SessionState;

import java.time.Instant;
import java.util.List;

/**
 * One run of the agent. Its lifecycle is modelled with the State pattern:
 * the session delegates behaviour to its current {@link SessionState}. It also
 * carries what is persisted about the run: the request, the plan steps, the
 * resulting playlist id, MusicMind's derived data (JSON) and the decision trace.
 */
public class AgentSession {
    private final String id;
    private final Instant startedAt;
    private String status;
    private SessionState state;
    private AgentRequest request;
    private List<String> planSteps = List.of();
    private String playlistId;
    private String derivedData;
    private DecisionTrace trace;

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
    /** Restores a persisted status label (a loaded session has no live state object). */
    public void setStatus(String status) { this.status = status; }

    public AgentRequest request() { return request; }
    public void setRequest(AgentRequest request) { this.request = request; }

    public List<String> planSteps() { return planSteps; }
    public void setPlanSteps(List<String> steps) { this.planSteps = steps == null ? List.of() : List.copyOf(steps); }

    public String playlistId() { return playlistId; }
    public void setPlaylistId(String playlistId) { this.playlistId = playlistId; }

    /** MusicMind-derived data as JSON (e.g. genre histogram, summary); never Spotify content. */
    public String derivedData() { return derivedData; }
    public void setDerivedData(String derivedData) { this.derivedData = derivedData; }

    public DecisionTrace trace() { return trace; }
    public void setTrace(DecisionTrace trace) { this.trace = trace; }
}
