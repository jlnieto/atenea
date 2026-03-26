package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.AgentRunAlreadyRunningException;
import com.atenea.service.worksession.OpenWorkSessionAlreadyExistsException;
import com.atenea.service.worksession.WorkSessionNotOpenException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.atenea.service.worksession.WorkSessionService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkSessionControllerTest {

    @Mock
    private WorkSessionService workSessionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkSessionController(workSessionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void openSessionReturnsCreatedSession() throws Exception {
        when(workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", "main")))
                .thenReturn(new WorkSessionResponse(
                        12L,
                        7L,
                        WorkSessionStatus.OPEN,
                        "Inspect project state",
                        "main",
                        null,
                        null,
                        Instant.parse("2026-03-25T10:05:00Z"),
                        Instant.parse("2026-03-25T10:05:00Z"),
                        null,
                        new SessionOperationalSnapshotResponse(true, true, "main", false)));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state",
                                  "baseBranch": "main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.projectId").value(7))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.baseBranch").value("main"))
                .andExpect(jsonPath("$.repoState.repoValid").value(true))
                .andExpect(jsonPath("$.repoState.workingTreeClean").value(true))
                .andExpect(jsonPath("$.repoState.currentBranch").value("main"))
                .andExpect(jsonPath("$.repoState.runInProgress").value(false));
    }

    @Test
    void openSessionReturnsBadRequestWhenTitleIsBlank() throws Exception {
        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   ",
                                  "baseBranch": "main"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("title: must not be blank"));
    }

    @Test
    void openSessionReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
        when(workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", null)))
                .thenThrow(new WorkSessionProjectNotFoundException(7L));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project with id '7' was not found"));
    }

    @Test
    void openSessionReturnsConflictWhenOpenSessionAlreadyExists() throws Exception {
        when(workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", null)))
                .thenThrow(new OpenWorkSessionAlreadyExistsException(7L));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Project with id '7' already has an open WorkSession"));
    }

    @Test
    void openSessionReturnsUnprocessableEntityWhenRepoIsNotOperational() throws Exception {
        when(workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", null)))
                .thenThrow(new WorkSessionOperationBlockedException("Repository is not operational"));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Repository is not operational"));
    }

    @Test
    void getSessionReturnsSession() throws Exception {
        when(workSessionService.getSession(12L)).thenReturn(new WorkSessionResponse(
                12L,
                7L,
                WorkSessionStatus.OPEN,
                "Inspect project state",
                "main",
                null,
                null,
                Instant.parse("2026-03-25T10:05:00Z"),
                Instant.parse("2026-03-25T10:05:00Z"),
                null,
                new SessionOperationalSnapshotResponse(true, false, "feature/docs", true)));

        mockMvc.perform(get("/api/sessions/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.projectId").value(7))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.repoState.repoValid").value(true))
                .andExpect(jsonPath("$.repoState.workingTreeClean").value(false))
                .andExpect(jsonPath("$.repoState.currentBranch").value("feature/docs"))
                .andExpect(jsonPath("$.repoState.runInProgress").value(true));
    }

    @Test
    void getSessionReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(workSessionService.getSession(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }

    @Test
    void closeSessionReturnsClosedSession() throws Exception {
        when(workSessionService.closeSession(12L)).thenReturn(new WorkSessionResponse(
                12L,
                7L,
                WorkSessionStatus.CLOSED,
                "Inspect project state",
                "main",
                null,
                "thread-1",
                Instant.parse("2026-03-25T10:05:00Z"),
                Instant.parse("2026-03-25T10:06:00Z"),
                Instant.parse("2026-03-25T10:07:00Z"),
                new SessionOperationalSnapshotResponse(true, true, "main", false)));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closeSessionReturnsConflictWhenSessionIsAlreadyClosed() throws Exception {
        when(workSessionService.closeSession(12L))
                .thenThrow(new WorkSessionNotOpenException(12L, WorkSessionStatus.CLOSED));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' is not OPEN (current status: CLOSED)"));
    }

    @Test
    void closeSessionReturnsConflictWhenRunIsRunning() throws Exception {
        when(workSessionService.closeSession(12L))
                .thenThrow(new AgentRunAlreadyRunningException(12L));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' already has a running AgentRun"));
    }

    @Test
    void closeSessionReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(workSessionService.closeSession(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }
}
