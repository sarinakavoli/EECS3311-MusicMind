package com.musicmind.controller;

import com.musicmind.domain.ListeningProfile;

import java.util.List;
import java.util.Map;

/** Result of F1 "View Taste Profile": the profile plus its one-line LLM summary. */
public record TasteProfileResult(String userId, ListeningProfile profile, String summary) {

    /** Genres ordered most-to-least prominent (the stored histogram is unordered). */
    public List<Map.Entry<String, Double>> rankedGenres(int limit) {
        return profile.genreHistogram().entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .toList();
    }
}
