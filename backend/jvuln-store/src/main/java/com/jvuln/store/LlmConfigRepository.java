package com.jvuln.store;

import com.jvuln.store.entity.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {
}
