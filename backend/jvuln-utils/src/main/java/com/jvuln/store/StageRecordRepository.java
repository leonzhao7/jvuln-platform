package com.jvuln.store;

import com.jvuln.store.entity.StageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageRecordRepository extends JpaRepository<StageRecord, Long> {
    List<StageRecord> findByCveIdOrderByStageNum(String cveId);
    Optional<StageRecord> findByCveIdAndStageNum(String cveId, int stageNum);
}
