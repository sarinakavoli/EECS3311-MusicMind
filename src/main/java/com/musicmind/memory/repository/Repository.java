package com.musicmind.memory.repository;

import java.util.List;

/**
 * Repository (DAO) pattern: a persistence boundary over the SQLite store, scoped
 * per user. MemoryManager uses concrete repositories so the rest of the agent
 * never touches SQL.
 *
 * <p>Entities here are immutable domain value objects that do not embed their
 * owner, so ownership is passed explicitly as {@code userId} rather than read
 * off the entity. (Stage 2 change from the Stage 1 {@code Repository<T, ID>}
 * sketch — see docs/STAGE2_PLAN.md.)
 *
 * @param <T> entity type
 */
public interface Repository<T> {

    /** Insert, or replace, the entity owned by {@code userId}. */
    void save(String userId, T entity);

    /** All entities of this type owned by {@code userId} (never null; 0..n). */
    List<T> findByUser(String userId);

    /** Remove every entity of this type owned by {@code userId}. */
    void deleteForUser(String userId);
}
