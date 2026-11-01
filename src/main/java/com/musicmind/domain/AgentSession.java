package com.musicmind.domain;

import com.musicmind.agent.state.FailedState;
import com.musicmind.agent.state.RunContext;
import com.musicmind.agent.state.SessionState;
import com.musicmind.music.AuthExpiredException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One run of the agent. Its lifecycle is modelled with the State pattern:
 * the session delegates behaviour to its current {@link SessionState}. It also
 * carries what is persisted about the run: the request, the plan steps, the
 * resulting playlist id, MusicMind's derived data (JSON) and the decision trace. A session built with
 * a {@link RunContext} owns that run: {@link #run()} drives it through its states to the end.
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
    // Not persisted: what the finished run tells the user, and the run itself.
    private RunContext context;
    private RuntimeException failure;
    private final List<String> notices = new ArrayList<>();
    private String explanation;

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

    public RunContext context() { return context; }
    public void setContext(RunContext context) { this.context = context; }

    /**
     * Runs the session to its end: each state does its phase and sets the next one, an error goes to the
     * {@link FailedState}, and an expired Spotify token is renewed once and the phase that hit it is repeated.
     * Every state name is published to the context's progress listener as the state starts.
     * The outcome is {@link #status()}, {@link #failure()} and what the states stored on the session.
     */
    public void run() {
        if (context == null) {
            throw new IllegalStateException("This session has no run context.");
        }
        while (true) {
            SessionState current = state;
            context.publish(current.name());
            try {
                current.handle(this);
            } catch (AuthExpiredException e) {
                if (!resumeAfterReauthentication()) {
                    fail(current, e);
                }
                continue;
            } catch (RuntimeException e) {
                fail(current, e);
            }
            if (current.isTerminal()) {
                return;
            }
        }
    }

    /** Renews the login once per run; false when that is not possible, or it failed (the failure is recorded). */
    private boolean resumeAfterReauthentication() {
        try {
            return context.reauthenticate();
        } catch (RuntimeException e) {
            fail(state, e);
            return true;
        }
    }

    private void fail(SessionState failed, RuntimeException e) {
        if (failed instanceof FailedState) {
            throw e; // error handling itself broke; nothing sensible is left to do
        }
        failure = e;
        setState(new FailedState());
    }

    /** What stopped the run, or null if it did not fail. */
    public RuntimeException failure() { return failure; }

    /** What the user should be told about the run: adjustments made, a database warning. */
    public List<String> notices() { return List.copyOf(notices); }
    public void addNotice(String notice) {
        if (notice != null && !notices.contains(notice)) {
            notices.add(notice);
        }
    }

    /** The rationale shown after a playlist is created; null until the run finished. */
    public String explanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
