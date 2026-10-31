package com.musicmind.memory.repository;

import java.util.List;

/**
 * Repository (DAO) pattern: a persistence boundary over PostgreSQL. Concrete
 * repositories take a current-user supplier and stamp it on every save and
 * on id-based lookups, because the entities do not embed their owner;
 * {@link #findByUser(String)} filters explicitly.
 *
 * @param <T> entity type
 */
public interface Repository<T> {

    /** Insert, or update, the entity for the current user. */
    void save(T entity);

    /** The current user's entity with this id, or {@code null} if there is none. */
    T findById(String id);

    /** All entities of this type owned by {@code userId} (never null; 0..n). */
    List<T> findByUser(String userId);

    /** Remove the current user's entity with this id (no-op if absent). */
    void delete(String id);
}
