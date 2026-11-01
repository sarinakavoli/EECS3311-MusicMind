package com.musicmind.agent.state;

import com.musicmind.agent.NegotiationHandler;
import com.musicmind.agent.Retrieval;
import com.musicmind.domain.AgentPlan;
import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.Feedback;
import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.Playlist;
import com.musicmind.domain.PlaylistSpec;
import com.musicmind.domain.Recommendation;
import com.musicmind.domain.UserPreference;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything one playlist-creation run carries from state to state: the request, what each phase
 * produced, and the counters that bound the retry and negotiation loops. The {@link com.musicmind.domain.AgentSession}
 * owns it, and the states read and write it.
 */
public final class RunContext {

    final RunServices services;
    final AgentRequest request;
    final NegotiationHandler handler;
    private final Consumer<String> progress;
    private boolean reauthenticated;

    List<UserPreference> preferences = List.of();
    List<Feedback> feedback = List.of();
    AgentPlan plan;
    ListeningProfile profile;
    Retrieval retrieval;
    List<Recommendation> recommendations = List.of();
    DecisionTrace trace;
    /** The length (minutes) the recommender is asked for; validation moves it when the result misses the target. */
    int engineMinutes;
    /** Notices the latest selection added; the next selection replaces them so a retry does not show both. */
    List<String> selectionNotices = List.of();
    int retries;
    int negotiationRounds;
    Playlist playlist;
    /** A playlist created by an attempt whose undo failed; null when none is left on Spotify. */
    String leftoverPlaylistId;

    /**
     * @param handler  answers an infeasible request; null when nobody can be asked
     * @param progress told the name of each state as it starts; may be null
     */
    public RunContext(RunServices services, AgentRequest request, NegotiationHandler handler, Consumer<String> progress) {
        this.services = services;
        this.request = request;
        this.handler = handler;
        this.progress = progress;
    }

    /** The playlist created by a finished run; null before that. */
    public Playlist playlist() {
        return playlist;
    }

    /** The spec in force: the planned one, with any relaxation the user accepted. */
    PlaylistSpec spec() {
        return plan.spec();
    }

    public void publish(String stateName) {
        if (progress == null) {
            return;
        }
        try {
            progress.accept(stateName);
        } catch (RuntimeException ignored) {
            // progress is only display; a failing listener must not fail the run
        }
    }

    /**
     * Signs in to Spotify again, once per run.
     *
     * @return false if that is not possible or was already done in this run
     */
    public boolean reauthenticate() {
        Runnable reauthenticator = services.reauthenticator();
        if (reauthenticator == null || reauthenticated) {
            return false;
        }
        reauthenticated = true;
        reauthenticator.run();
        return true;
    }
}
