package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.ChangeHelpFixture;
import com.atenea.service.mobile.ChangeHelpGuide;
import com.atenea.service.mobile.ChangeHelpService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChangeHelpControllerTest {
    private final ChangeHelpService help=mock(ChangeHelpService.class);
    private final AuthenticatedOperator actor=new AuthenticatedOperator(1L,"operator@example.test","Operator");
    private MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
        mvc=MockMvcBuilders.standaloneSetup(new ChangeHelpController(help))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private ChangeHelpResponse response() { return ChangeHelpGuide.explain(new ChangeHelpFixture().build(),"Qué hago",false); }
    @Test void authenticatedOverviewDoesNotInvokeQuestionOrAction() throws Exception {
        when(help.overview(22L,actor)).thenReturn(response());
        mvc.perform(get("/api/mobile/sessions/22/help")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("VALIDATION_REQUIRED"))
                .andExpect(jsonPath("$.nextAction.destination").value("CHANGE"));
        verify(help).overview(22L,actor); verifyNoMoreInteractions(help);
    }
    @Test void questionAndSimplificationRemainScopedToSameChange() throws Exception {
        when(help.ask(eq(22L),eq(actor),anyString(),eq(true))).thenReturn(response());
        mvc.perform(post("/api/mobile/sessions/22/help").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"No lo entiendo\",\"simple\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workSessionId").value(22));
        verify(help).ask(22L,actor,"No lo entiendo",true); verifyNoMoreInteractions(help);
    }
    @Test void noAuthenticationCannotConsultOrAsk() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/mobile/sessions/22/help")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/sessions/22/help").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Qué hago\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(help);
    }
    @Test void callerCannotSupplyPathsCommandsModelsOrOtherContext() throws Exception {
        for (String field : List.of("command","path","version","model","changeKey","workSessionId","facts","destination")) {
            mvc.perform(post("/api/mobile/sessions/22/help").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"Qué hago\",\""+field+"\":\"forged\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(help);
    }
    @Test void invalidQuestionAndWrongTypesAreRejected() throws Exception {
        for (String request : List.of("{}","[]","{\"question\":false}","{\"question\":\" \"}",
                "{\"question\":\"x\",\"simple\":\"true\"}","{\"question\":\""+"x".repeat(1001)+"\"}")) {
            mvc.perform(post("/api/mobile/sessions/22/help").contentType(MediaType.APPLICATION_JSON)
                    .content(request)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(help);
    }
}
