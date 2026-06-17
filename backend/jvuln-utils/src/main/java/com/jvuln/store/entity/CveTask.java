package com.jvuln.store.entity;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cve_task")
public class CveTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cve_id", unique = true, nullable = false, length = 20)
    private String cveId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "current_stage")
    private int currentStage = 0;

    @Column(length = 100)
    private String artifact;

    @Column(name = "cvss_score", precision = 3, scale = 1)
    private BigDecimal cvssScore;

    @Column(name = "cwe_id", length = 20)
    private String cweId;

    @Column(name = "workspace_path", length = 500)
    private String workspacePath;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCveId() { return cveId; }
    public void setCveId(String cveId) { this.cveId = cveId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public int getCurrentStage() { return currentStage; }
    public void setCurrentStage(int currentStage) { this.currentStage = currentStage; }
    public String getArtifact() { return artifact; }
    public void setArtifact(String artifact) { this.artifact = artifact; }
    public BigDecimal getCvssScore() { return cvssScore; }
    public void setCvssScore(BigDecimal cvssScore) { this.cvssScore = cvssScore; }
    public String getCweId() { return cweId; }
    public void setCweId(String cweId) { this.cweId = cweId; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "CveTask{" +
                "id=" + id +
                ", cveId='" + cveId + '\'' +
                ", status=" + status +
                ", currentStage=" + currentStage +
                ", artifact='" + artifact + '\'' +
                ", cvssScore=" + cvssScore +
                ", cweId='" + cweId + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
