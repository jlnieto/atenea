package com.atenea.persistence.core;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CoreCommandRepository extends JpaRepository<CoreCommandEntity, Long> {
}
