package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorProfileResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MobileAuthControllerTest {

    @Mock
    private OperatorAuthenticationService operatorAuthenticationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileAuthController(operatorAuthenticationService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void loginRefreshAndMeDelegateToAuthService() throws Exception {
        MobileAuthSessionResponse session = new MobileAuthSessionResponse(
                "access-token",
                Instant.parse("2026-03-29T10:15:00Z"),
                "refresh-token",
                Instant.parse("2026-04-28T10:00:00Z"),
                new OperatorProfileResponse(4L, "operator@atenea.local", "Operator"));
        when(operatorAuthenticationService.login(new MobileLoginRequest("operator@atenea.local", "secret")))
                .thenReturn(session);
        when(operatorAuthenticationService.refresh(new MobileRefreshTokenRequest("refresh-token")))
                .thenReturn(session);
        when(operatorAuthenticationService.getCurrentOperator(any(AuthenticatedOperator.class)))
                .thenReturn(new OperatorProfileResponse(4L, "operator@atenea.local", "Operator"));

        mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.operator.email").value("operator@atenea.local"));

        mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));

        mockMvc.perform(get("/api/mobile/auth/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(
                                        new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"),
                                        null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.displayName").value("Operator"));
    }
}
