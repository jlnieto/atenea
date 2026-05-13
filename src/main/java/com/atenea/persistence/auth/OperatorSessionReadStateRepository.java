package com.atenea.persistence.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorSessionReadStateRepository extends JpaRepository<OperatorSessionReadStateEntity, Long> {

    List<OperatorSessionReadStateEntity> findByOperatorIdOrderByUpdatedAtDesc(Long operatorId);

    Optional<OperatorSessionReadStateEntity> findByOperatorIdAndWorkSessionId(Long operatorId, Long workSessionId);
}
