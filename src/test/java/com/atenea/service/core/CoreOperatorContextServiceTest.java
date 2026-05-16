package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreOperatorContextEntity;
import com.atenea.persistence.core.CoreOperatorContextRepository;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreOperatorContextServiceTest {

    @Mock
    private CoreOperatorContextRepository coreOperatorContextRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Test
    void applyActiveContextDoesNotInheritProjectOrSessionForGlobalScope() {
        CoreOperatorContextEntity activeContext = new CoreOperatorContextEntity();
        activeContext.setOperatorKey("operator@atenea.local");
        activeContext.setActiveProjectId(7L);
        activeContext.setActiveWorkSessionId(12L);
        when(coreOperatorContextRepository.findById("operator@atenea.local"))
                .thenReturn(Optional.of(activeContext));
        CoreOperatorContextService service = new CoreOperatorContextService(
                coreOperatorContextRepository,
                workSessionRepository,
                projectRepository);

        CreateCoreCommandRequest result = service.applyActiveContext(new CreateCoreCommandRequest(
                "comprueba apache en el dedicado",
                CoreChannel.TEXT,
                new CoreRequestContext(null, null, "operator@atenea.local", "GLOBAL"),
                null));

        assertNull(result.context().projectId());
        assertNull(result.context().workSessionId());
        assertEquals("GLOBAL", result.context().scope());
        verify(coreOperatorContextRepository).findById("operator@atenea.local");
        verifyNoInteractions(workSessionRepository, projectRepository);
    }
}
