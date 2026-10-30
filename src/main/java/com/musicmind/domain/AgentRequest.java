package com.musicmind.domain;

/** The raw natural-language request plus its classified kind. */
public record AgentRequest(String rawText, RequestKind kind) {
}
