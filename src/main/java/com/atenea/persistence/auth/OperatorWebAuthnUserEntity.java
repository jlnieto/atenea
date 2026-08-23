package com.atenea.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "operator_webauthn_user")
public class OperatorWebAuthnUserEntity {

    @Id
    @Column(name = "operator_id")
    private Long operatorId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", insertable = false, updatable = false)
    private OperatorEntity operator;

    @Column(name = "user_handle", nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] userHandle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long value) { operatorId = value; }
    public OperatorEntity getOperator() { return operator; }
    public byte[] getUserHandle() { return userHandle == null ? null : userHandle.clone(); }
    public void setUserHandle(byte[] value) { userHandle = value == null ? null : value.clone(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
