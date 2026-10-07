package com.atenea.persistence.mobile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mobile_diagnostic_report")
public class MobileDiagnosticReportEntity {
    @Id private UUID id;
    @Column(name = "operator_id", nullable = false) private Long operatorId;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "app_version_name", nullable = false, length = 80) private String appVersionName;
    @Column(name = "app_version_code", nullable = false) private int appVersionCode;
    @Column(name = "device_model", nullable = false, length = 150) private String deviceModel;
    @Column(name = "size_bytes", nullable = false) private int sizeBytes;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "report_bytes", nullable = false, columnDefinition = "bytea") private byte[] reportBytes;

    public UUID getId() { return id; }
    public Long getOperatorId() { return operatorId; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getAppVersionName() { return appVersionName; }
    public int getAppVersionCode() { return appVersionCode; }
    public String getDeviceModel() { return deviceModel; }
    public int getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public byte[] getReportBytes() { return reportBytes; }
}
