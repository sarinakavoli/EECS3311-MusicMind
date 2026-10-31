package com.musicmind.memory.repository;

import com.musicmind.domain.AgentRequest;
import com.musicmind.domain.AgentSession;
import com.musicmind.domain.DecisionTrace;
import com.musicmind.domain.RequestKind;
import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryTest extends PostgresTestBase {

    private SessionRepository repo() {
        return new SessionRepository(db, currentUser::get);
    }

    private AgentSession session(String id, Instant at) {
        AgentSession s = new AgentSession(id, at, null);
        s.setStatus("done");
        s.setRequest(new AgentRequest("40 minute run mix", RequestKind.CREATE_PLAYLIST));
        s.setPlanSteps(List.of("parse goal", "retrieve", "select"));
        s.setPlaylistId("pl-9");
        s.setDerivedData("{\"summary\":\"hi\"}");
        s.setTrace(new DecisionTrace("Balanced", Map.of("t1", "matches mood"), List.of("duration")));
        return s;
    }

    @Test
    void roundTripsAllFields() {
        SessionRepository repo = repo();
        Instant at = Instant.parse("2026-05-05T10:15:30Z");
        repo.save(session("s1", at));

        AgentSession loaded = repo.findById("s1");
        assertEquals("s1", loaded.id());
        assertEquals(at, loaded.startedAt());
        assertEquals("done", loaded.status());
        assertEquals("40 minute run mix", loaded.request().rawText());
        assertEquals(RequestKind.CREATE_PLAYLIST, loaded.request().kind());
        assertEquals(List.of("parse goal", "retrieve", "select"), loaded.planSteps());
        assertEquals("pl-9", loaded.playlistId());
        assertEquals("{\"summary\":\"hi\"}", loaded.derivedData());
        assertEquals("Balanced", loaded.trace().strategyName());
        assertEquals("matches mood", loaded.trace().reasons().get("t1"));
        assertEquals(List.of("duration"), loaded.trace().constraintsSatisfied());
    }

    @Test
    void savingAgainUpdatesAndNullsSurvive() {
        SessionRepository repo = repo();
        AgentSession bare = new AgentSession("s2", Instant.parse("2026-05-05T10:15:30Z"), null);
        repo.save(bare);
        AgentSession loaded = repo.findById("s2");
        assertNull(loaded.request());
        assertNull(loaded.playlistId());
        assertNull(loaded.trace());
        assertTrue(loaded.planSteps().isEmpty());

        bare.setPlaylistId("pl-1");
        repo.save(bare);
        assertEquals("pl-1", repo.findById("s2").playlistId());
        assertEquals(1, repo.findByUser("user-1").size());
    }

    @Test
    void findByUserIsNewestFirstAndIsolated() {
        SessionRepository repo = repo();
        repo.save(session("old", Instant.parse("2026-01-01T00:00:00Z")));
        repo.save(session("new", Instant.parse("2026-06-01T00:00:00Z")));
        currentUser.set("user-2");
        repo.save(session("other", Instant.parse("2026-07-01T00:00:00Z")));

        List<AgentSession> mine = repo.findByUser("user-1");
        assertEquals(List.of("new", "old"), mine.stream().map(AgentSession::id).toList());
        assertEquals("other", repo.findByUser("user-2").get(0).id());
    }

    @Test
    void findByPlaylistAndDelete() {
        SessionRepository repo = repo();
        repo.save(session("s1", Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals("s1", repo.findByPlaylist("pl-9").id());
        assertNull(repo.findByPlaylist("nope"));

        repo.delete("s1");
        assertNull(repo.findById("s1"));
    }
}
