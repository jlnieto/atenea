package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorSessionFamilyRepository
        extends JpaRepository<OperatorSessionFamilyEntity, UUID> {

    List<OperatorSessionFamilyEntity> findAllByOperatorIdOrderByLastUsedAtDescIdAsc(
            Long operatorId);

    Optional<OperatorSessionFamilyEntity> findByIdAndOperatorId(UUID id, Long operatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select family from OperatorSessionFamilyEntity family where family.id = :familyId")
    Optional<OperatorSessionFamilyEntity> findByIdForUpdate(
            @Param("familyId") UUID familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select family from OperatorSessionFamilyEntity family
            where family.operator.id = :operatorId
            order by family.id
            """)
    List<OperatorSessionFamilyEntity> findAllByOperatorIdForUpdate(
            @Param("operatorId") Long operatorId);
}
