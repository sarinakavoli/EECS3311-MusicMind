package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

/**
 * State pattern: each concrete state represents one phase of an agent run
 * (idle, planning, retrieving, selecting, validating, executing, done, failed).
 */
public interface SessionState {

    /** Short machine-readable name of this phase (shown as progress in the GUI). */
    String name();

    /** Perform this phase's behaviour for the given session; it sets the next state, unless this one is final. */
    void handle(AgentSession session);

    /** True for the states a run ends in ({@code done} and {@code failed}); their {@link #handle} is the last step. */
    default boolean isTerminal() {
        return false;
    }
}
