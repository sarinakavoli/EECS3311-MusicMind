package com.musicmind.domain;

/**
 * The raw natural-language request plus its classified kind. {@code constraints}
 * holds explicit values (for example CLI flags) that override what the planner
 * parses from the text; it is null when none were given. Inside it, a value that was not given is
 * marked by {@code targetMinutes <= 0}, a familiar ratio that is NaN or negative, or a blank name;
 * blocked artists are added to the stored ones, and {@code allowExplicit == false} forbids explicit
 * tracks whatever the text said (explicit content can only be made stricter, never looser, by a request).
 */
public record AgentRequest(String rawText, RequestKind kind, PlaylistSpec constraints) {

    /** A request with no explicit constraints. */
    public AgentRequest(String rawText, RequestKind kind) {
        this(rawText, kind, null);
    }
}
