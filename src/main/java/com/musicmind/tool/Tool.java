package com.musicmind.tool;

import java.util.Map;

/**
 * A capability the agent can invoke by name through the {@code ToolManager}
 * (e.g. SpotifyTool, MemoryTool). Keeps the agent decoupled from concrete
 * services.
 */
public interface Tool {
    String name();
    ToolResult execute(Map<String, Object> args);
}
