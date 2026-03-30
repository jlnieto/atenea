package com.atenea.api.mobile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=true",
        "atenea.auth.bootstrap.email=operator@atenea.local",
        "atenea.auth.bootstrap.password=secret-pass",
        "atenea.auth.bootstrap.display-name=Integration Operator",
        "atenea.auth.jwt.secret=integration-mobile-secret-2026"
})
class MobileAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
    }

    @Test
    void mobileEndpointsRequireAuthAndLoginWorks() throws Exception {
        mockMvc.perform(get("/api/mobile/inbox"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret-pass"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.operator.email").value("operator@atenea.local"));
    }

    @Test
    void refreshRotatesSessionAndLogoutRevokesRefreshToken() throws Exception {
        JsonNode loginJson = objectMapper.readTree(mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret-pass"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        String accessToken = loginJson.get("accessToken").asText();
        String refreshToken = loginJson.get("refreshToken").asText();

        mockMvc.perform(get("/api/mobile/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Integration Operator"));

        JsonNode refreshJson = objectMapper.readTree(mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "%s"
                                }
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        String rotatedRefreshToken = refreshJson.get("refreshToken").asText();

        mockMvc.perform(post("/api/mobile/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "%s"
                                }
                                """.formatted(rotatedRefreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "%s"
                                }
                                """.formatted(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOperatorCanRegisterAndListPushDevices() throws Exception {
        JsonNode loginJson = objectMapper.readTree(mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret-pass"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        String accessToken = loginJson.get("accessToken").asText();

        mockMvc.perform(post("/api/mobile/notifications/push-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expoPushToken": "ExponentPushToken[test-token]",
                                  "deviceId": "device-1",
                                  "deviceName": "Pixel Test",
                                  "platform": "android",
                                  "appVersion": "1.0.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/mobile/notifications/push-devices")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expoPushToken").value("ExponentPushToken[test-token]"));

        mockMvc.perform(post("/api/mobile/notifications/push-token/unregister")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expoPushToken": "ExponentPushToken[test-token]"
                                }
                                """))
                .andExpect(status().isNoContent());
    }
}
