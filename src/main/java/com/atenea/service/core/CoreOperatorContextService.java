package com.atenea.service.core;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreOperatorContextEntity;
import com.atenea.persistence.core.CoreOperatorContextRepository;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoreOperatorContextService {

    public static final String DEFAULT_OPERATOR_KEY = "default";

    private final CoreOperatorContextRepository coreOperatorContextRepository;
    private final WorkSessionRepository workSessionRepository;
    private final ProjectRepository projectRepository;

    public CoreOperatorContextService(
            CoreOperatorContextRepository coreOperatorContextRepository,
            WorkSessionRepository workSessionRepository,
            ProjectRepository projectRepository
    ) {
        this.coreOperatorContextRepository = coreOperatorContextRepository;
        this.workSessionRepository = workSessionRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public CreateCoreCommandRequest applyActiveContext(CreateCoreCommandRequest request) {
        String operatorKey = resolveOperatorKey(request.context());
        CoreOperatorContextEntity operatorContext = coreOperatorContextRepository.findById(operatorKey).orElse(null);

        Long projectId = request.context() == null ? null : request.context().projectId();
        Long workSessionId = request.context() == null ? null : request.context().workSessionId();
        if (projectId == null && operatorContext != null) {
            projectId = operatorContext.getActiveProjectId();
        }
        if (workSessionId == null && operatorContext != null) {
            workSessionId = operatorContext.getActiveWorkSessionId();
        }

        if (workSessionId != null && projectId == null) {
            projectId = workSessionRepository.findWithProjectById(workSessionId)
                    .map(session -> session.getProject().getId())
                    .orElse(null);
        }

        CoreRequestContext context = new CoreRequestContext(projectId, workSessionId, operatorKey);
        return new CreateCoreCommandRequest(
                request.input(),
                request.channel(),
                context,
                request.confirmation());
    }

    @Transactional(readOnly = true)
    public CoreOperatorContextEntity getOrDefault(String operatorKey) {
        return coreOperatorContextRepository.findById(normalizeOperatorKey(operatorKey))
                .orElseGet(() -> {
                    CoreOperatorContextEntity context = new CoreOperatorContextEntity();
                    context.setOperatorKey(normalizeOperatorKey(operatorKey));
                    context.setUpdatedAt(Instant.now());
                    return context;
                });
    }

    @Transactional
    public void updateAfterSuccess(
            String operatorKey,
            Long commandId,
            CoreIntentEnvelope intent,
            CoreCommandExecutionResult result
    ) {
        CoreOperatorContextEntity context = coreOperatorContextRepository.findById(normalizeOperatorKey(operatorKey))
                .orElseGet(() -> {
                    CoreOperatorContextEntity entity = new CoreOperatorContextEntity();
                    entity.setOperatorKey(normalizeOperatorKey(operatorKey));
                    return entity;
                });

        Long projectId = switch (intent.capability()) {
            case "activate_project_context", "get_project_overview", "create_work_session" ->
                    longParameter(intent, "projectId");
            case "continue_work_session", "publish_work_session", "sync_work_session_pull_request",
                    "get_session_summary", "get_session_deliverables", "generate_session_deliverable",
                    "close_work_session" -> resolveProjectIdFromSession(longParameter(intent, "workSessionId"));
            default -> context.getActiveProjectId();
        };

        Long workSessionId = switch (intent.capability()) {
            case "create_work_session" -> result.targetId();
            case "continue_work_session", "publish_work_session", "sync_work_session_pull_request",
                    "get_session_summary", "get_session_deliverables", "generate_session_deliverable",
                    "close_work_session" -> longParameter(intent, "workSessionId");
            default -> context.getActiveWorkSessionId();
        };

        context.setActiveProjectId(projectId);
        context.setActiveWorkSessionId(workSessionId);
        context.setActiveCommandId(commandId);
        context.setUpdatedAt(Instant.now());
        coreOperatorContextRepository.save(context);
    }

    @Transactional
    public void activateProject(String operatorKey, Long projectId, Long commandId) {
        if (projectId == null || !projectRepository.existsById(projectId)) {
            throw new CoreInvalidContextException("Missing or invalid Core parameter: projectId");
        }
        CoreOperatorContextEntity context = coreOperatorContextRepository.findById(normalizeOperatorKey(operatorKey))
                .orElseGet(() -> {
                    CoreOperatorContextEntity entity = new CoreOperatorContextEntity();
                    entity.setOperatorKey(normalizeOperatorKey(operatorKey));
                    return entity;
                });
        context.setActiveProjectId(projectId);
        context.setActiveWorkSessionId(null);
        context.setActiveCommandId(commandId);
        context.setUpdatedAt(Instant.now());
        coreOperatorContextRepository.save(context);
    }

    @Transactional(readOnly = true)
    public Long activeProjectId(String operatorKey) {
        return coreOperatorContextRepository.findById(normalizeOperatorKey(operatorKey))
                .map(CoreOperatorContextEntity::getActiveProjectId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Long activeWorkSessionId(String operatorKey) {
        return coreOperatorContextRepository.findById(normalizeOperatorKey(operatorKey))
                .map(CoreOperatorContextEntity::getActiveWorkSessionId)
                .orElse(null);
    }

    public String resolveOperatorKey(CoreRequestContext context) {
        return normalizeOperatorKey(context == null ? null : context.operatorKey());
    }

    private Long longParameter(CoreIntentEnvelope intent, String key) {
        Object value = intent.parameters().get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private Long resolveProjectIdFromSession(Long workSessionId) {
        if (workSessionId == null) {
            return null;
        }
        return workSessionRepository.findWithProjectById(workSessionId)
                .map(session -> session.getProject().getId())
                .orElse(null);
    }

    private String normalizeOperatorKey(String operatorKey) {
        if (operatorKey == null || operatorKey.isBlank()) {
            return DEFAULT_OPERATOR_KEY;
        }
        return operatorKey.trim();
    }
}
