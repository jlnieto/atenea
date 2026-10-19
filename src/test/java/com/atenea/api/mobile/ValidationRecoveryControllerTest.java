package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.ValidationRecoveryService;
import com.atenea.service.mobile.ValidationRecoveryStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ValidationRecoveryControllerTest {
    final ValidationRecoveryService service=mock(ValidationRecoveryService.class);
    final AuthenticatedOperator actor=new AuthenticatedOperator(1L,"operator@test.invalid","Operator");
    final UUID id=UUID.randomUUID();
    MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
        mvc=MockMvcBuilders.standaloneSetup(new ValidationRecoveryController(service))
            .setControllerAdvice(new com.atenea.api.ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void overviewOnlyReadsAndDoesNotPrepareOrInvestigate() throws Exception {
        when(service.observe(26L,actor)).thenReturn(new ValidationRecoveryService.Overview(26L,UUID.randomUUID(),1,
            "UNSUPPORTED_DEPENDENCY_MANIFEST",true,true,null,null));
        mvc.perform(get("/api/mobile/sessions/26/validation-recovery")).andExpect(status().isOk())
            .andExpect(jsonPath("$.protocolVersion").value("validation-recovery/v1"));
        verify(service).observe(26L,actor);verifyNoMoreInteractions(service);
    }
    @Test void everyActionRejectsCommandsPathsVersionsAndExtraArguments() throws Exception {
        for(String action:List.of("prepare","investigate",id+"/apply",id+"/recover",id+"/cancel")) {
            for(String body:List.of("[]","{\"path\":\"foreign\"}","{\"command\":\"id\"}","{\"version\":157}","{\"sourceRevision\":99}")) {
                mvc.perform(post("/api/mobile/sessions/26/validation-recovery/"+action)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }
    @Test void diagnosticIsAuthenticatedReadOnlyAndDoesNotInvestigate() throws Exception {
        when(service.diagnostic(26L,actor)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"protocolVersion\":\"closed-validation-diagnostic/v1\"}"));
        mvc.perform(get("/api/mobile/sessions/26/validation-recovery/diagnostic")).andExpect(status().isOk())
            .andExpect(jsonPath("$.protocolVersion").value("closed-validation-diagnostic/v1"));
        verify(service).diagnostic(26L,actor);verifyNoMoreInteractions(service);
    }
    @Test void unauthenticatedReadAndActionsAreDenied() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/mobile/sessions/26/validation-recovery")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mobile/sessions/26/validation-recovery/diagnostic")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/sessions/26/validation-recovery/prepare").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void preparingAndApplyingAreDifferentExplicitRequests() throws Exception {
        when(service.prepare(26L,actor)).thenReturn(new ValidationRecoveryStore.View(id,"REPAIR","PREPARE_REQUESTED",1,null,null));
        when(service.apply(26L,id,actor)).thenReturn(new ValidationRecoveryStore.View(id,"REPAIR","APPLY_REQUESTED",1,null,null));
        mvc.perform(post("/api/mobile/sessions/26/validation-recovery/prepare").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PREPARE_REQUESTED"));
        verify(service,never()).apply(anyLong(),any(),any());
        mvc.perform(post("/api/mobile/sessions/26/validation-recovery/"+id+"/apply").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("APPLY_REQUESTED"));
    }
    @Test void databaseAdmissionGuardIsAnActionableConflictNotHttp500() throws Exception {
        when(service.prepare(26L,actor)).thenThrow(new org.springframework.jdbc.UncategorizedSQLException(
            "synthetic","redacted",new java.sql.SQLException("ATENEA_VALIDATION_REPAIR_IN_PROGRESS","55000")));
        mvc.perform(post("/api/mobile/sessions/26/validation-recovery/prepare").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.details[0]").value("VALIDATION_RECOVERY_NEEDS_ATTENTION"));
    }
}
