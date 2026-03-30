package com.atenea.persistence.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorRepository extends JpaRepository<OperatorEntity, Long> {

    Optional<OperatorEntity> findByEmailIgnoreCase(String email);
}
