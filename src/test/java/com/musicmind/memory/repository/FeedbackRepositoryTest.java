package com.musicmind.memory.repository;

import com.musicmind.domain.Feedback;
import com.musicmind.domain.Verdict;
import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackRepositoryTest extends PostgresTestBase {

    private FeedbackRepository repo() {
        return new FeedbackRepository(db, currentUser::get);
    }

    @Test
    void upsertsByUserAndTrack() {
        FeedbackRepository repo = repo();
        repo.save(new Feedback("t1", Verdict.ACCEPT, Instant.parse("2026-01-01T00:00:00Z")));
        repo.save(new Feedback("t1", Verdict.REJECT, Instant.parse("2026-02-01T00:00:00Z")));
        repo.save(new Feedback("t2", Verdict.ACCEPT, Instant.parse("2026-03-01T00:00:00Z")));

        assertEquals(2, repo.findByUser("user-1").size());
        Feedback f = repo.findById("t1");
        assertEquals(Verdict.REJECT, f.verdict());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), f.createdAt());
    }

    @Test
    void usersAreIsolatedAndDeleteIsScoped() {
        FeedbackRepository repo = repo();
        repo.save(new Feedback("t1", Verdict.ACCEPT, Instant.now()));
        currentUser.set("user-2");
        repo.save(new Feedback("t1", Verdict.REJECT, Instant.now()));

        assertEquals(Verdict.REJECT, repo.findById("t1").verdict());
        repo.delete("t1");
        assertNull(repo.findById("t1"));
        assertEquals(Verdict.ACCEPT, repo.findByUser("user-1").get(0).verdict());
    }
}
