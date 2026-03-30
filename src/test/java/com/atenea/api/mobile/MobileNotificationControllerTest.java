package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.MobilePushNotificationService;
import java.time.Instant;
import java.util.List;
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
class MobileNotificationControllerTest {

    @Mock
    private MobilePushNotificationService mobilePushNotificationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileNotificationController(mobilePushNotificationService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void registerListAndUnregisterDelegateToService() throws Exception {
        MobilePushDeviceResponse response = new MobilePushDeviceResponse(
                3L,
                "ExponentPushToken[test-token]",
                "device-1",
                "Pixel",
                "android",
                "1.0.0",
                true,
                Instant.parse("2026-03-29T11:00:00Z"),
                Instant.parse("2026-03-29T11:00:00Z"));
        when(mobilePushNotificationService.registerPushToken(any(), any())).thenReturn(response);
        when(mobilePushNotificationService.getDevices(any())).thenReturn(List.of(response));

        var principal = SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"),
                        null));

        mockMvc.perform(post("/api/mobile/notifications/push-token")
                        .with(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expoPushToken": "ExponentPushToken[test-token]",
                                  "deviceId": "device-1",
                                  "deviceName": "Pixel",
                                  "platform": "android",
                                  "appVersion": "1.0.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expoPushToken").value("ExponentPushToken[test-token]"));

        mockMvc.perform(get("/api/mobile/notifications/push-devices")
                        .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceName").value("Pixel"));

        mockMvc.perform(post("/api/mobile/notifications/push-token/unregister")
                        .with(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expoPushToken": "ExponentPushToken[test-token]"
                                }
                                """))
                .andExpect(status().isNoContent());
    }
}
