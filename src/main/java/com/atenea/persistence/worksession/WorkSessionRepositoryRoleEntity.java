package com.atenea.persistence.worksession;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_session_repository_role")
public class WorkSessionRepositoryRoleEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_session_id", nullable = false)
    private WorkSessionEntity workSession;
    @Column(name = "change_identity", nullable = false) private UUID changeIdentity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private RepositoryRoleKind role;
    @Column(nullable = false, length = 16) private String authority;
    @Column(name = "repository_url", nullable = false, length = 300) private String repositoryUrl;
    @Column(nullable = false, length = 160) private String branch;
    @Column(nullable = false, length = 40) private String commit;
    @Column(name = "mirror_identity_sha256", nullable = false, length = 64) private String mirrorIdentitySha256;
    @Column(name = "worktree_identity_sha256", nullable = false, length = 64) private String worktreeIdentitySha256;
    @Column(name = "validation_profile", nullable = false, length = 80) private String validationProfile;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24) private RepositoryRoleReadiness readiness;
    @Column(name = "source_fingerprint_sha256", length = 64) private String sourceFingerprintSha256;
    @Column(name = "validation_projection_sha256", length = 64) private String validationProjectionSha256;
    @Column(name = "validated_at") private Instant validatedAt;
    @Column(name = "integration_ready_at") private Instant integrationReadyAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public WorkSessionEntity getWorkSession() { return workSession; }
    public void setWorkSession(WorkSessionEntity value) { workSession = value; }
    public UUID getChangeIdentity() { return changeIdentity; }
    public void setChangeIdentity(UUID value) { changeIdentity = value; }
    public RepositoryRoleKind getRole() { return role; }
    public void setRole(RepositoryRoleKind value) { role = value; }
    public String getAuthority() { return authority; }
    public void setAuthority(String value) { authority = value; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String value) { repositoryUrl = value; }
    public String getBranch() { return branch; }
    public void setBranch(String value) { branch = value; }
    public String getCommit() { return commit; }
    public void setCommit(String value) { commit = value; }
    public String getMirrorIdentitySha256() { return mirrorIdentitySha256; }
    public void setMirrorIdentitySha256(String value) { mirrorIdentitySha256 = value; }
    public String getWorktreeIdentitySha256() { return worktreeIdentitySha256; }
    public void setWorktreeIdentitySha256(String value) { worktreeIdentitySha256 = value; }
    public String getValidationProfile() { return validationProfile; }
    public void setValidationProfile(String value) { validationProfile = value; }
    public RepositoryRoleReadiness getReadiness() { return readiness; }
    public void setReadiness(RepositoryRoleReadiness value) { readiness = value; }
    public String getSourceFingerprintSha256() { return sourceFingerprintSha256; }
    public void setSourceFingerprintSha256(String value) { sourceFingerprintSha256 = value; }
    public String getValidationProjectionSha256() { return validationProjectionSha256; }
    public void setValidationProjectionSha256(String value) { validationProjectionSha256 = value; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant value) { validatedAt = value; }
    public Instant getIntegrationReadyAt() { return integrationReadyAt; }
    public void setIntegrationReadyAt(Instant value) { integrationReadyAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
