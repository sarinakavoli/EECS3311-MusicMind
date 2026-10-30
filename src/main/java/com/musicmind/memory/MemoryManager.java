package com.musicmind.memory;

import com.musicmind.domain.ListeningProfile;
import com.musicmind.domain.UserPreference;
import com.musicmind.memory.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Façade over the persistence layer. The agent remembers things (taste
 * profiles, preferences) through these high-level calls and never touches a
 * repository or SQL directly.
 */
public class MemoryManager {

    private final Repository<ListeningProfile> profiles;
    private final Repository<UserPreference> preferences;

    public MemoryManager(Repository<ListeningProfile> profiles,
                         Repository<UserPreference> preferences) {
        this.profiles = profiles;
        this.preferences = preferences;
    }

    // ---- taste profile (F1) ----

    public void saveProfile(String userId, ListeningProfile profile) {
        profiles.save(userId, profile);
    }

    /** The most recently saved profile for a user, if any. */
    public Optional<ListeningProfile> loadProfile(String userId) {
        return profiles.findByUser(userId).stream().findFirst();
    }

    // ---- preferences (F8) ----

    public void savePreference(String userId, UserPreference preference) {
        preferences.save(userId, preference);
    }

    public List<UserPreference> preferences(String userId) {
        return preferences.findByUser(userId);
    }
}
