package com.atenea.persistence.core;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoreCommandEventRepository extends JpaRepository<CoreCommandEventEntity, Long> {

    List<CoreCommandEventEntity> findByCommandIdOrderByCreatedAtDesc(Long commandId);
}
