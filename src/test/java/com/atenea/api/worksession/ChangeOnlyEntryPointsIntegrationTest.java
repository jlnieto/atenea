package com.atenea.api.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.service.core.CoreCapabilityDisabledException;
import com.atenea.service.core.CoreCapabilityRegistry;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.service.worksession.WorkSessionService;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionTurnRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

/** Replaces the removed local-session happy path; remote change ownership has
 * its positive integration suite in RemoteSessionServiceIntegrationTest. */
@SpringBootTest(properties={"atenea.auth.enabled=false", "atenea.auth.bootstrap.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class ChangeOnlyEntryPointsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CoreCapabilityRegistry capabilities;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired AgentRunRepository runs;
    @Autowired SessionTurnRepository turns;

    @Test void allIndependentCreatorsAreAbsentAndHaveNoEffects() throws Exception {
        long sessionCount=sessions.count(), changeCount=changes.count(), runCount=runs.count(), turnCount=turns.count();
        for (String path: List.of("/api/projects/1/sessions", "/api/projects/1/sessions/resolve",
                "/api/projects/1/sessions/resolve/view", "/api/projects/1/sessions/resolve/conversation-view",
                "/api/mobile/projects/1/sessions/resolve", "/api/mobile/sessions/6/start-fresh",
                "/api/sessions/6/recover-retained-draft", "/api/mobile/projects/1/rescue-sessions/resolve",
                "/api/admin/work-sessions/6/remote-close-plans",
                "/api/admin/work-sessions/6/remote-close-reconciliations")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        assertEquals(sessionCount,sessions.count()); assertEquals(changeCount,changes.count());
        assertEquals(runCount,runs.count()); assertEquals(turnCount,turns.count());
    }

    @Test void coreCannotAdvertiseOrInvokeTheRemovedCreator() {
        assertThrows(CoreCapabilityDisabledException.class, () -> capabilities.requireEnabled(CoreDomain.DEVELOPMENT,"create_work_session"));
        assertTrue(java.util.Arrays.stream(WorkSessionService.class.getDeclaredMethods())
                .noneMatch(m -> List.of("openSession","resolveSession","resolveFreshSessionConversationView").contains(m.getName())));
    }
}
