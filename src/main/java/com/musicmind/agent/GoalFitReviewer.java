package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks retrieved tracks against the playlist goal with the LLM, because Spotify gives this app no tempo
 * or energy data to judge fit. The model is shown, per track, only its Spotify id and the genres MusicMind
 * already knows for it (the genre it was searched under, the genres of the user's own artists, and cached
 * MusicBrainz genres; no request is made for the review). It is never shown a title, artist name or album,
 * because Spotify Content must not be ingested into an AI model (Spotify Developer Policy, section III item 14).
 * It may only answer with the ids to take out: it never names or adds a track, and the final selection stays
 * deterministic.
 *
 * <p>A track with no known genre for any of its artists is not sent, since it cannot be judged; it is kept.
 *
 * <p>The review is best effort. With no LLM, nothing to send, a failed call, a reply that is not the expected JSON, or a
 * reply that would remove so many tracks that fewer than {@code goal.minKeepShare} of the list remain, nothing is
 * removed and the result says it was not reviewed.
 */
public class GoalFitReviewer {

    /**
     * @param removedIds ids from the reviewed tracks that do not suit the goal
     * @param reviewed   false when the review could not be used, so the tracks are unchecked
     */
    public record Result(Set<String> removedIds, boolean reviewed) {
        public Result {
            removedIds = Set.copyOf(removedIds);
        }
    }

    private final LLMProvider llm; // null when no key is configured
    private final PromptBuilder prompts;
    private final ResponseParser parser;
    private final Config config;
    private final ArtistGenreCache cache;

    public GoalFitReviewer(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config) {
        this(llm, prompts, parser, config, ArtistGenreCache.NONE);
    }

    /** @param cache read-only cache of MusicBrainz artist genres, consulted for artists the profile has no genres for */
    public GoalFitReviewer(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config,
                           ArtistGenreCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** The genres known for the tracks of {@code retrieval}, read from its search genres, the profile and the cache. */
    public TrackGenres genresOf(Retrieval retrieval) {
        Map<String, Optional<List<String>>> seen = new HashMap<>(); // one cache read per artist per review
        ArtistGenreCache memo = id -> seen.computeIfAbsent(id, cache::cached);
        return track -> retrieval.knownGenres(track, memo);
    }

    /**
     * The ids to remove among the first {@code goal.reviewMaxTracks} of the {@code tracks} that have a known genre,
     * ignored altogether if they would leave fewer than {@code goal.minKeepShare} of the whole list.
     */
    public Result review(String goal, List<Track> tracks, TrackGenres genres) {
        return ask(goal, tracks, genres, true);
    }

    /**
     * As {@link #review}, for the tracks a selection has already picked: with no keep-share guard, because
     * the picks are a short list the selection can refill, and rejecting most of it is a finding, not a glitch.
     * Every other failure rule is the same: ids not among the picks are ignored, and no LLM, a failed call or
     * a reply that is not the expected JSON removes nothing and says it was not reviewed.
     */
    public Result reviewPicks(String goal, List<Track> picks, TrackGenres genres) {
        return ask(goal, picks, genres, false);
    }

    private Result ask(String goal, List<Track> tracks, TrackGenres genres, boolean guardKeepShare) {
        if (tracks.isEmpty()) {
            return new Result(Set.of(), true);
        }
        if (llm == null) {
            return notReviewed();
        }
        // A track with no known genre cannot be judged from genres alone: it is not sent, and it is kept.
        List<Track> sent = tracks.stream().filter(t -> !genres.of(t).isEmpty())
                .limit(config.goalReviewMaxTracks()).toList();
        if (sent.isEmpty()) {
            return notReviewed();
        }
        Set<String> sentIds = new HashSet<>();
        sent.forEach(t -> sentIds.add(t.id()));
        Set<String> removed = new LinkedHashSet<>();
        try {
            for (String id : parser.parseRemovedIds(llm.complete(prompts.buildGoalReviewPrompt(goal, sent, genres)))) {
                if (sentIds.contains(id)) {
                    removed.add(id);
                }
            }
        } catch (RuntimeException e) {
            return notReviewed(); // LLMException, ParseException or anything else: the tracks stay unchecked
        }
        // The share is over the whole list passed in, not only what was sent: a large pool keeps its removals,
        // while a small pool (where the model saw everything) stays protected from a reply that removes most of it.
        double kept = (tracks.size() - removed.size()) / (double) tracks.size();
        return guardKeepShare && kept < config.goalMinKeepShare() ? notReviewed() : new Result(removed, true);
    }

    private static Result notReviewed() {
        return new Result(Set.of(), false);
    }
}
