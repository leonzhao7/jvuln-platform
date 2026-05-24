package com.jvuln.store;

import com.jvuln.store.entity.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {

    Optional<LlmConfig> findByActiveTrue();

    @Modifying
    @Transactional
    @Query("UPDATE LlmConfig c SET c.active = false")
    void deactivateAll();
}
