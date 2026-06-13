package com.jvuln.store;

import com.jvuln.store.entity.CveTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CveTaskRepository extends JpaRepository<CveTask, Long> {
    Optional<CveTask> findByCveId(String cveId);
    boolean existsByCveId(String cveId);
}
