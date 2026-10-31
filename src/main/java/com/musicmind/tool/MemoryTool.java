package com.musicmind.tool;

import com.musicmind.memory.MemoryManager;

import java.util.Map;

/**
 * Read access to the user's remembered data. Args: {@code action}
 * ({@code loadPreferences} or {@code loadFeedback}) and {@code user}.
 */
public class MemoryTool implements Tool {

    private final MemoryManager memory;

    public MemoryTool(MemoryManager memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object action = args.get("action");
        Object user = args.get("user");
        if (action == null || action.toString().isBlank()) {
            return ToolResult.failure("memory: missing required argument 'action'");
        }
        if (user == null || user.toString().isBlank()) {
            return ToolResult.failure("memory: missing required argument 'user'");
        }
        String userId = user.toString().trim();
        return switch (action.toString()) {
            case "loadPreferences" -> ToolResult.of(memory.loadPreferences(userId));
            case "loadFeedback" -> ToolResult.of(memory.loadFeedback(userId));
            default -> ToolResult.failure("memory: unknown action '" + action
                    + "' (expected loadPreferences or loadFeedback)");
        };
    }
}
