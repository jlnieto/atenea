package com.atenea.persistence.worksession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "agent_run_progress_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_run_progress_event_sequence",
                columnNames = {"agent_run_id", "sequence"}))
public class AgentRunProgressEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false)
    private AgentRunEntity agentRun;

    @Column(nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunProgressCategory category;

    @Column(name = "operator_message", nullable = false, length = 160)
    private String operatorMessage;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AgentRunEntity getAgentRun() { return agentRun; }
    public void setAgentRun(AgentRunEntity agentRun) { this.agentRun = agentRun; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public AgentRunProgressCategory getCategory() { return category; }
    public void setCategory(AgentRunProgressCategory category) { this.category = category; }
    public String getOperatorMessage() { return operatorMessage; }
    public void setOperatorMessage(String operatorMessage) { this.operatorMessage = operatorMessage; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
