package com.musicmind.tool;

import com.musicmind.domain.Feedback;
import com.musicmind.domain.UserPreference;
import com.musicmind.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolTest {

    private static final UserPreference PREF = new UserPreference("k", "v", true, 0.3);

    private final MemoryManager memory = new MemoryManager(null, null, null, null, null) {
        @Override public List<UserPreference> loadPreferences(String user) { return List.of(PREF); }
        @Override public List<Feedback> loadFeedback(String user) { return List.of(); }
    };
    private final MemoryTool tool = new MemoryTool(memory);

    @Test
    void isNamedMemory() {
        assertEquals("memory", tool.name());
    }

    @Test
    void loadPreferencesRoutesToTheManager() {
        ToolResult r = tool.execute(Map.of("action", "loadPreferences", "user", "u1"));
        assertTrue(r.ok());
        assertEquals(List.of(PREF), r.payload());
    }

    @Test
    void loadFeedbackRoutesToTheManager() {
        ToolResult r = tool.execute(Map.of("action", "loadFeedback", "user", "u1"));
        assertTrue(r.ok());
        assertEquals(List.of(), r.payload());
    }

    @Test
    void missingOrUnknownActionFails() {
        assertFalse(tool.execute(Map.of("user", "u1")).ok());
        ToolResult r = tool.execute(Map.of("action", "dropTables", "user", "u1"));
        assertFalse(r.ok());
        assertTrue(r.error().contains("dropTables"));
    }

    @Test
    void missingUserFails() {
        assertFalse(tool.execute(Map.of("action", "loadPreferences")).ok());
        assertFalse(tool.execute(Map.of("action", "loadFeedback", "user", " ")).ok());
    }
}
