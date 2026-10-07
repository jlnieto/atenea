package com.atenea.persistence.mobile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MobileDiagnosticReportRepository extends JpaRepository<MobileDiagnosticReportEntity, UUID> {
    interface Receipt {
        UUID getId();
        Instant getReceivedAt();
        Instant getGeneratedAt();
        String getAppVersionName();
        int getAppVersionCode();
        String getDeviceModel();
        int getSizeBytes();
        String getSha256();
    }

    Optional<Receipt> findReceiptByIdAndOperatorId(UUID id, Long operatorId);
    Optional<MobileDiagnosticReportEntity> findByIdAndOperatorId(UUID id, Long operatorId);
    List<Receipt> findByOperatorIdOrderByReceivedAtDescIdDesc(Long operatorId, Pageable pageable);

    @Modifying
    @Query(value = """
            INSERT INTO mobile_diagnostic_report
                (id, operator_id, received_at, generated_at, app_version_name,
                 app_version_code, device_model, size_bytes, sha256, report_bytes)
            VALUES (:id, :operatorId, :receivedAt, :generatedAt, :versionName,
                    :versionCode, :deviceModel, :sizeBytes, :sha256, :bytes)
            ON CONFLICT (operator_id, sha256) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("operatorId") Long operatorId,
            @Param("receivedAt") Instant receivedAt, @Param("generatedAt") Instant generatedAt,
            @Param("versionName") String versionName, @Param("versionCode") int versionCode,
            @Param("deviceModel") String deviceModel, @Param("sizeBytes") int sizeBytes,
            @Param("sha256") String sha256, @Param("bytes") byte[] bytes);
}
