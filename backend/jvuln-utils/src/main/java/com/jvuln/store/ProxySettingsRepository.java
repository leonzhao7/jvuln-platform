package com.jvuln.store;

import com.jvuln.store.entity.ProxySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProxySettingsRepository extends JpaRepository<ProxySettings, Long> {
}
