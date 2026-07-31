package com.atenea.persistence.worksession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validation_operation")
public class ValidationOperationEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_session_id", nullable = false)
    private WorkSessionEntity workSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ValidationOperationKind operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ValidationOperationStatus status;

    @Column(name = "source_tree_fingerprint_sha256", nullable = false, length = 64)
    private String sourceTreeFingerprintSha256;

    @Column(name = "definition_revision", nullable = false, length = 80)
    private String definitionRevision;

    @Column(name = "identity_sha256", nullable = false, unique = true, length = 64)
    private String identitySha256;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "artifact_manifest_sha256", length = 64)
    private String artifactManifestSha256;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public WorkSessionEntity getWorkSession() { return workSession; }
    public void setWorkSession(WorkSessionEntity workSession) { this.workSession = workSession; }
    public ValidationOperationKind getOperation() { return operation; }
    public void setOperation(ValidationOperationKind operation) { this.operation = operation; }
    public ValidationOperationStatus getStatus() { return status; }
    public void setStatus(ValidationOperationStatus status) { this.status = status; }
    public String getSourceTreeFingerprintSha256() { return sourceTreeFingerprintSha256; }
    public void setSourceTreeFingerprintSha256(String value) { sourceTreeFingerprintSha256 = value; }
    public String getDefinitionRevision() { return definitionRevision; }
    public void setDefinitionRevision(String definitionRevision) { this.definitionRevision = definitionRevision; }
    public String getIdentitySha256() { return identitySha256; }
    public void setIdentitySha256(String identitySha256) { this.identitySha256 = identitySha256; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(Long durationMillis) { this.durationMillis = durationMillis; }
    public String getArtifactManifestSha256() { return artifactManifestSha256; }
    public void setArtifactManifestSha256(String value) { artifactManifestSha256 = value; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
