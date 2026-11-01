package com.musicmind.memory.repository;

import com.musicmind.domain.UserPreference;
import com.musicmind.memory.PostgresTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreferenceRepositoryTest extends PostgresTestBase {

    private PreferenceRepository repo() {
        return new PreferenceRepository(db, currentUser::get);
    }

    @Test
    void roundTripsAndOverwritesByKey() {
        PreferenceRepository repo = repo();
        repo.save(new UserPreference("explicit", "false", false, 0.7));
        repo.save(new UserPreference("explicit", "true", true, 0.2));

        List<UserPreference> all = repo.findByUser("user-1");
        assertEquals(1, all.size());
        UserPreference p = repo.findById("explicit");
        assertEquals("true", p.value());
        assertTrue(p.explicitAllowed());
        assertEquals(0.2, p.familiarBias(), 1e-9);
    }

    @Test
    void usersAreIsolated() {
        PreferenceRepository repo = repo();
        repo.save(new UserPreference("style", "jazz", true, 0.5));
        currentUser.set("user-2");
        repo.save(new UserPreference("style", "metal", true, 0.5));

        assertEquals("metal", repo.findById("style").value());
        assertEquals("jazz", repo.findByUser("user-1").get(0).value());
        assertTrue(repo.findByUser("nobody").isEmpty());

        repo.delete("style");
        assertNull(repo.findById("style"));
        assertEquals(1, repo.findByUser("user-1").size());
    }

    @Test
    void missingIdIsNull() {
        assertNull(repo().findById("absent"));
    }
}
