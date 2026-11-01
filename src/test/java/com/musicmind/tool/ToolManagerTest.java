package com.musicmind.tool;

import com.musicmind.music.AuthExpiredException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManagerTest {

    private static Tool tool(String name, java.util.function.Function<Map<String, Object>, ToolResult> body) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public ToolResult execute(Map<String, Object> args) { return body.apply(args); }
        };
    }

    @Test
    void routesACallToTheToolWithThatName() {
        ToolManager tm = new ToolManager();
        tm.register(tool("a", args -> ToolResult.of("from a " + args.get("x"))));
        tm.register(tool("b", args -> ToolResult.of("from b")));
        ToolResult r = tm.execute("a", Map.of("x", 1));
        assertTrue(r.ok());
        assertEquals("from a 1", r.payload());
    }

    @Test
    void unknownToolIsAFailureNotAnException() {
        ToolResult r = new ToolManager().execute("nope", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("nope"));
    }

    @Test
    void aNullArgsMapIsTreatedAsEmpty() {
        ToolManager tm = new ToolManager();
        tm.register(tool("a", args -> ToolResult.of(args.size())));
        assertEquals(0, tm.execute("a", null).payload());
    }

    @Test
    void toolExceptionsBecomeFailures() {
        ToolManager tm = new ToolManager();
        tm.register(tool("boom", args -> { throw new IllegalStateException("kaput"); }));
        ToolResult r = tm.execute("boom", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("kaput"));
    }

    @Test
    void exceptionWithoutMessageReportsItsClassName() {
        ToolManager tm = new ToolManager();
        tm.register(tool("npe", args -> { throw new NullPointerException(); }));
        ToolResult r = tm.execute("npe", Map.of());
        assertFalse(r.ok());
        assertEquals("npe failed: NullPointerException", r.error());
    }

    @Test
    void authExpiryPropagates() {
        ToolManager tm = new ToolManager();
        tm.register(tool("auth", args -> { throw new AuthExpiredException("log in again"); }));
        assertThrows(AuthExpiredException.class, () -> tm.execute("auth", Map.of()));
    }

    @Test
    void registeringTwiceReplacesTheTool() {
        ToolManager tm = new ToolManager();
        tm.register(tool("a", args -> ToolResult.of(1)));
        tm.register(tool("a", args -> ToolResult.of(2)));
        assertEquals(2, tm.execute("a", Map.of()).payload());
    }

    @Test
    void registeringANullToolIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ToolManager().register(null));
    }
}
