package com.musicmind.agent.state;

import com.musicmind.domain.AgentSession;

/**
 * State pattern: each concrete state represents one phase of an agent run
 * (idle, planning, retrieving, selecting, validating, executing, done, failed).
 */
public interface SessionState {

    /** Short machine-readable name of this phase (shown as progress in the GUI). */
    String name();

    /** Perform this phase's behaviour for the given session. */
    void handle(AgentSession session);
}
