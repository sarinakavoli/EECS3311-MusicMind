package com.musicmind.memory.repository;

import java.util.List;

/**
 * Repository (DAO) pattern: a generic persistence boundary over the SQLite
 * store. MemoryManager uses concrete repositories so the rest of the agent
 * never touches SQL.
 *
 * @param <T>  entity type
 * @param <ID> identifier type
 */
public interface Repository<T, ID> {
    void save(T entity);
    T findById(ID id);
    List<T> findByUser(String userId);
    void delete(ID id);
}
