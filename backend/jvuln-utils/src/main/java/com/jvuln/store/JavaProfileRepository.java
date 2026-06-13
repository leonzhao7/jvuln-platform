package com.jvuln.store;

import com.jvuln.store.entity.JavaProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface JavaProfileRepository extends JpaRepository<JavaProfile, Long> {

    Optional<JavaProfile> findByIsDefaultTrue();

    @Modifying
    @Transactional
    @Query("UPDATE JavaProfile p SET p.isDefault = false")
    void clearAllDefaults();
}
