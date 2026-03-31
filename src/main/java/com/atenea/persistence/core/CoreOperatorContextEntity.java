package com.atenea.persistence.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "core_operator_context")
public class CoreOperatorContextEntity {

    @Id
    @Column(name = "operator_key", nullable = false, length = 120)
    private String operatorKey;

    @Column(name = "active_project_id")
    private Long activeProjectId;

    @Column(name = "active_work_session_id")
    private Long activeWorkSessionId;

    @Column(name = "active_command_id")
    private Long activeCommandId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getOperatorKey() {
        return operatorKey;
    }

    public void setOperatorKey(String operatorKey) {
        this.operatorKey = operatorKey;
    }

    public Long getActiveProjectId() {
        return activeProjectId;
    }

    public void setActiveProjectId(Long activeProjectId) {
        this.activeProjectId = activeProjectId;
    }

    public Long getActiveWorkSessionId() {
        return activeWorkSessionId;
    }

    public void setActiveWorkSessionId(Long activeWorkSessionId) {
        this.activeWorkSessionId = activeWorkSessionId;
    }

    public Long getActiveCommandId() {
        return activeCommandId;
    }

    public void setActiveCommandId(Long activeCommandId) {
        this.activeCommandId = activeCommandId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
