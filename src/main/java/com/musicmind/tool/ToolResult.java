package com.musicmind.tool;

/**
 * The outcome of a tool invocation returned by {@link Tool#execute}. A failure carries the
 * original exception as {@code cause} when there was one; {@code error} is for logs and
 * developers, and callers must not show it to users.
 */
public record ToolResult(boolean ok, Object payload, String error, Throwable cause) {
    public ToolResult(boolean ok, Object payload, String error) {
        this(ok, payload, error, null);
    }

    public static ToolResult of(Object payload) { return new ToolResult(true, payload, null, null); }
    public static ToolResult failure(String error) { return new ToolResult(false, null, error, null); }
    public static ToolResult failure(String error, Throwable cause) { return new ToolResult(false, null, error, cause); }
}
