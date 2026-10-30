package com.musicmind.tool;

/** The outcome of a tool invocation returned by {@link Tool#execute}. */
public record ToolResult(boolean ok, Object payload, String error) {
    public static ToolResult of(Object payload) { return new ToolResult(true, payload, null); }
    public static ToolResult failure(String error) { return new ToolResult(false, null, error); }
}
