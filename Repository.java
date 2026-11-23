package kz.taxi.repository;

import java.util.List;
import java.util.Optional;

public interface Repository<T> {
    Optional<T> findById(long id);
    List<T> findAll();
    void save(T entity);
    boolean delete(long id);
}
