package com.musicmind.domain;

/**
 * The raw natural-language request plus its classified kind. {@code constraints}
 * holds explicit values (for example CLI flags) that override what the planner
 * parses from the text; it is null when none were given.
 */
public record AgentRequest(String rawText, RequestKind kind, PlaylistSpec constraints) {

    /** A request with no explicit constraints. */
    public AgentRequest(String rawText, RequestKind kind) {
        this(rawText, kind, null);
    }
}
