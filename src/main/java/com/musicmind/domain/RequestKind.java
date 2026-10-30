package com.musicmind.domain;

/** The kind of high-level request a user made to the agent. */
public enum RequestKind {
    CREATE_PLAYLIST,
    MODIFY_PLAYLIST,
    DISCOVER,
    ANALYZE_TASTE,
    SEARCH
}
