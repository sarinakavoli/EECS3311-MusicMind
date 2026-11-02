package com.musicmind.memory.repository;

import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CandidateRepositoryTest extends PostgresTestBase {

    private CandidateRepository repo() {
        return new CandidateRepository(db, currentUser::get);
    }

    @Test
    void addingATrackTwiceKeepsOneRowInTheOrderAdded() {
        repo().save("b");
        repo().save("a");
        repo().save("b");

        assertEquals(List.of("b", "a"), repo().findByUser(currentUser.get()));
    }

    @Test
    void oneTrackCanBeRemovedOrAllCleared() {
        repo().save("a");
        repo().save("b");
        repo().save("c");

        repo().delete("b");
        assertEquals(List.of("a", "c"), repo().findByUser(currentUser.get()));
        assertEquals("a", repo().findById("a"));
        assertNull(repo().findById("b"));

        repo().clear();
        assertEquals(List.of(), repo().findByUser(currentUser.get()));
    }

    @Test
    void anotherUsersCandidatesStaySeparate() {
        repo().save("mine");

        assertEquals(List.of(), repo().findByUser("someone-else"));
    }
}
