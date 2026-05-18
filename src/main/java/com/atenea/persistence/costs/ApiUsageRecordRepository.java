package com.atenea.persistence.costs;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiUsageRecordRepository extends JpaRepository<ApiUsageRecordEntity, Long> {

    List<ApiUsageRecordEntity> findByProviderAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            String provider,
            Instant startAt,
            Instant endAt
    );
}
