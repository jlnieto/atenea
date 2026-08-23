package com.atenea.persistence.developmentchange;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevelopmentChangeRepository
        extends JpaRepository<DevelopmentChangeEntity, Long> {

    @EntityGraph(attributePaths = "project")
    Optional<DevelopmentChangeEntity> findByChangeKey(UUID changeKey);

    @EntityGraph(attributePaths = "project")
    @Query("select change from DevelopmentChangeEntity change "
            + "where change.changeKey = :changeKey and change.project.id = :projectId")
    Optional<DevelopmentChangeEntity> findByProjectIdAndChangeKey(
            @Param("projectId") Long projectId,
            @Param("changeKey") UUID changeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select change from DevelopmentChangeEntity change "
            + "join fetch change.project where change.changeKey = :changeKey")
    Optional<DevelopmentChangeEntity> findByChangeKeyForUpdate(
            @Param("changeKey") UUID changeKey);

    @EntityGraph(attributePaths = "project")
    List<DevelopmentChangeEntity> findAllByProjectIdOrderByUpdatedAtDescIdDesc(Long projectId);

    Optional<DevelopmentChangeEntity> findByWorkspaceIdentity(String workspaceIdentity);
}
