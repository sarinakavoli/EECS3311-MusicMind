package com.musicmind.tool;

import com.musicmind.music.AuthExpiredException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and dispatcher for the agent's tools. A failing tool never breaks the
 * agent: unknown names and tool exceptions come back as {@link ToolResult#failure}.
 * The one exception is {@link AuthExpiredException}, which propagates so the
 * user can be asked to log in again.
 */
public class ToolManager {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** Adds a tool under its name, replacing any tool already registered with that name. */
    public void register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("A tool must have a name.");
        }
        tools.put(tool.name(), tool);
    }

    public ToolResult execute(String toolName, Map<String, Object> args) {
        Tool tool = toolName == null ? null : tools.get(toolName);
        if (tool == null) {
            return ToolResult.failure("Unknown tool: " + toolName);
        }
        try {
            return tool.execute(args == null ? Map.of() : args);
        } catch (AuthExpiredException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ToolResult.failure(toolName + " failed: " + reason, e);
        }
    }
}
