package com.atenea.persistence.voice;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "voice_focus")
public class VoiceFocusEntity {

    @Id
    @Column(name = "operator_id")
    private Long operatorId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VoiceDomain domain;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id")
    private WorkSessionEntity workSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_command_id")
    private CoreCommandEntity activeCommand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "managed_host_id")
    private ManagedHostEntity managedHost;

    @Column(length = 80)
    private String activity;

    @Column(name = "playback_source_type", length = 80)
    private String playbackSourceType;

    @Column(name = "playback_source_id", length = 120)
    private String playbackSourceId;

    @Column(name = "playback_segment_index")
    private Integer playbackSegmentIndex;

    @Column(name = "playback_segment_count")
    private Integer playbackSegmentCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getOperatorId() {
        return operatorId;
    }

    public OperatorEntity getOperator() {
        return operator;
    }

    public void setOperator(OperatorEntity operator) {
        this.operator = operator;
    }

    public VoiceDomain getDomain() {
        return domain;
    }

    public void setDomain(VoiceDomain domain) {
        this.domain = domain;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public WorkSessionEntity getWorkSession() {
        return workSession;
    }

    public void setWorkSession(WorkSessionEntity workSession) {
        this.workSession = workSession;
    }

    public CoreCommandEntity getActiveCommand() {
        return activeCommand;
    }

    public void setActiveCommand(CoreCommandEntity activeCommand) {
        this.activeCommand = activeCommand;
    }

    public ManagedHostEntity getManagedHost() {
        return managedHost;
    }

    public void setManagedHost(ManagedHostEntity managedHost) {
        this.managedHost = managedHost;
    }

    public String getActivity() {
        return activity;
    }

    public void setActivity(String activity) {
        this.activity = activity;
    }

    public String getPlaybackSourceType() {
        return playbackSourceType;
    }

    public void setPlaybackSourceType(String playbackSourceType) {
        this.playbackSourceType = playbackSourceType;
    }

    public String getPlaybackSourceId() {
        return playbackSourceId;
    }

    public void setPlaybackSourceId(String playbackSourceId) {
        this.playbackSourceId = playbackSourceId;
    }

    public Integer getPlaybackSegmentIndex() {
        return playbackSegmentIndex;
    }

    public void setPlaybackSegmentIndex(Integer playbackSegmentIndex) {
        this.playbackSegmentIndex = playbackSegmentIndex;
    }

    public Integer getPlaybackSegmentCount() {
        return playbackSegmentCount;
    }

    public void setPlaybackSegmentCount(Integer playbackSegmentCount) {
        this.playbackSegmentCount = playbackSegmentCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
