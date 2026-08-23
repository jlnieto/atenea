package com.atenea.persistence.auth;

import com.atenea.auth.recovery.AuthAttemptScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operator_auth_attempt_window")
public class OperatorAuthAttemptEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AuthAttemptScope scope;
    @Column(name = "window_started_at", nullable = false) private Instant windowStartedAt;
    @Column(name = "failed_count", nullable = false) private int failedCount;
    @Column(name = "blocked_until") private Instant blockedUntil;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public AuthAttemptScope getScope() { return scope; }
    public void setScope(AuthAttemptScope value) { scope = value; }
    public Instant getWindowStartedAt() { return windowStartedAt; }
    public void setWindowStartedAt(Instant value) { windowStartedAt = value; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int value) { failedCount = value; }
    public Instant getBlockedUntil() { return blockedUntil; }
    public void setBlockedUntil(Instant value) { blockedUntil = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public long getRowVersion() { return rowVersion; }
}
