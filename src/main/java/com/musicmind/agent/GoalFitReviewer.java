package com.musicmind.agent;

import com.musicmind.config.Config;
import com.musicmind.domain.Track;
import com.musicmind.llm.LLMProvider;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks retrieved tracks against the playlist goal with the LLM, because Spotify gives this app no tempo
 * or energy data to judge fit. The model is shown real tracks (id, title, artists) and may only answer with
 * the ids to take out: it never names or adds a track, and the final selection stays deterministic.
 *
 * <p>The review is best effort. With no LLM, a failed call, a reply that is not the expected JSON, or a
 * reply that would remove so many tracks that fewer than {@code goal.minKeepShare} remain, nothing is
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

    public GoalFitReviewer(LLMProvider llm, PromptBuilder prompts, ResponseParser parser, Config config) {
        this.llm = llm;
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** The ids to remove among the first {@code goal.reviewMaxTracks} of {@code tracks}. */
    public Result review(String goal, List<Track> tracks) {
        if (tracks.isEmpty()) {
            return new Result(Set.of(), true);
        }
        if (llm == null) {
            return notReviewed();
        }
        List<Track> sent = tracks.stream().limit(config.goalReviewMaxTracks()).toList();
        Set<String> sentIds = new HashSet<>();
        sent.forEach(t -> sentIds.add(t.id()));
        Set<String> removed = new LinkedHashSet<>();
        try {
            for (String id : parser.parseRemovedIds(llm.complete(prompts.buildGoalReviewPrompt(goal, sent)))) {
                if (sentIds.contains(id)) {
                    removed.add(id);
                }
            }
        } catch (RuntimeException e) {
            return notReviewed(); // LLMException, ParseException or anything else: the tracks stay unchecked
        }
        double kept = (sentIds.size() - removed.size()) / (double) sentIds.size();
        return kept < config.goalMinKeepShare() ? notReviewed() : new Result(removed, true);
    }

    private static Result notReviewed() {
        return new Result(Set.of(), false);
    }
}
