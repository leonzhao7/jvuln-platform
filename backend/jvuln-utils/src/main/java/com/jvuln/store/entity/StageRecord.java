package com.jvuln.store.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stage_record", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cve_id", "stage_num"})
})
public class StageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cve_id", nullable = false, length = 20)
    private String cveId;

    @Column(name = "stage_num", nullable = false)
    private int stageNum;

    @Column(name = "stage_name", length = 50)
    private String stageName;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private StageStatus status = StageStatus.PENDING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    public enum StageStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }
    public int getStageNum() { return stageNum; }
    public void setStageNum(int stageNum) { this.stageNum = stageNum; }
    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }
    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    @Override
    public String toString() {
        return "StageRecord{" +
                "id=" + id +
                ", cveId='" + cveId + '\'' +
                ", stageNum=" + stageNum +
                ", stageName='" + stageName + '\'' +
                ", status=" + status +
                ", startedAt=" + startedAt +
                ", finishedAt=" + finishedAt +
                ", errorMsg='" + errorMsg + '\'' +
                '}';
    }
}
