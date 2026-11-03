package com.musicmind.domain;

/**
 * The raw natural-language request plus its classified kind. {@code constraints}
 * holds explicit values (for example CLI flags) that override what the planner
 * parses from the text; it is null when none were given. Inside it, a value that was not given is
 * marked by {@code targetMinutes <= 0}, a familiar ratio that is NaN or negative, or a blank name;
 * blocked artists are added to the stored ones, and {@code allowExplicit == false} forbids explicit
 * tracks whatever the text said (explicit content can only be made stricter, never looser, by a request).
 * {@code useCandidates} says whether the tracks the user set aside (F3) start the playlist; when false the
 * run uses none of them and leaves the stored list as it is.
 */
public record AgentRequest(String rawText, RequestKind kind, PlaylistSpec constraints, boolean useCandidates) {

    /** A request with no explicit constraints. */
    public AgentRequest(String rawText, RequestKind kind) {
        this(rawText, kind, null, true);
    }

    /** A request with constraints that starts with the user's candidates, as Stage 1 says. */
    public AgentRequest(String rawText, RequestKind kind, PlaylistSpec constraints) {
        this(rawText, kind, constraints, true);
    }
}
