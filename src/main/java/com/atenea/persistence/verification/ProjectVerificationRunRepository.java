package com.atenea.persistence.verification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectVerificationRunRepository extends JpaRepository<ProjectVerificationRunEntity, Long> {

    List<ProjectVerificationRunEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ProjectVerificationRunEntity> findByWorkSessionIdOrderByCreatedAtDesc(Long workSessionId);
}
