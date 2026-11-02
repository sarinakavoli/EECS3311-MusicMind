package com.musicmind.agent.state;

import com.musicmind.agent.CandidatePool;
import com.musicmind.agent.ConstraintChecker;
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
import com.musicmind.domain.Track;
import com.musicmind.domain.UserPreference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything one playlist-creation run carries from state to state: the request, what each phase
 * produced, and the counters that bound the retry and negotiation loops. The {@link com.musicmind.domain.AgentSession}
 * owns it, and the states read and write it.
 */
public final class RunContext {

    private static final double MS_PER_MINUTE = 60_000.0;

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
    /** The user's candidate track ids (F3), from memory. */
    List<String> candidateIds = List.of();
    /** Those candidates that Spotify could still return. */
    List<Track> candidates = List.of();
    /** The candidates the latest selection put in the playlist. */
    List<String> usedCandidates = List.of();
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

    /** The candidates the spec allows, in the order added, as far as they fit in the target length. */
    List<Track> fittingCandidates() {
        PlaylistSpec spec = spec();
        double maxMs = spec.targetMinutes() * MS_PER_MINUTE * (1 + services.config().playlistDurationTolerance());
        List<Track> fitting = new ArrayList<>();
        long total = 0;
        for (Track t : candidates) {
            if (ConstraintChecker.eligible(t, spec) && total + t.durationMs() <= maxMs) {
                fitting.add(t);
                total += t.durationMs();
            }
        }
        return fitting;
    }

    /** What the run can choose from: the tracks retrieved, plus the candidates it can use. */
    CandidatePool pool() {
        CandidatePool read = retrieval.pool();
        List<Track> familiar = new ArrayList<>(read.familiar());
        List<Track> novel = new ArrayList<>(read.novel());
        Set<String> known = new HashSet<>();
        familiar.forEach(t -> known.add(t.id()));
        novel.forEach(t -> known.add(t.id()));
        for (Track t : fittingCandidates()) {
            if (known.add(t.id())) {
                (profile.familiarity(t) >= 1.0 ? familiar : novel).add(t);
            }
        }
        return new CandidatePool(familiar, novel);
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
