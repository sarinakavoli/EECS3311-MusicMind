package com.musicmind.agent;

/**
 * One playlist edit the LLM asked for, before it is bound to a real playlist.
 * {@code track} is a reference from the edit prompt (a 1-based position or a track id),
 * {@code query} is search text for ADD/REPLACE (resolved to real tracks via Spotify,
 * never by the LLM), and {@code position} is the 0-based target index for REORDER.
 */
public record CommandSpec(Type type, String track, String query, Integer position) {

    public enum Type { ADD, REMOVE, REPLACE, REORDER }
}
