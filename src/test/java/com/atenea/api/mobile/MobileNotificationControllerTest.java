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
import com.atenea.service.mobile.MobileNotificationInboxService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MobileNotificationControllerTest {

    @Mock
    private MobilePushNotificationService mobilePushNotificationService;
    @Mock
    private MobileNotificationInboxService inboxService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"), null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileNotificationController(mobilePushNotificationService, inboxService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerListAndUnregisterDelegateToService() throws Exception {
        MobilePushDeviceResponse response = new MobilePushDeviceResponse(
                3L,
                "fcm-token",
                "device-1",
                "Pixel",
                "android",
                "1.0.0",
                true,
                Instant.parse("2026-03-29T11:00:00Z"),
                Instant.parse("2026-03-29T11:00:00Z"));
        when(mobilePushNotificationService.registerPushToken(any(), any())).thenReturn(response);
        when(mobilePushNotificationService.getDevices(any())).thenReturn(List.of(response));

        mockMvc.perform(post("/api/mobile/notifications/push-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pushToken": "fcm-token",
                                  "deviceId": "device-1",
                                  "deviceName": "Pixel",
                                  "platform": "android",
                                  "appVersion": "1.0.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushToken").value("fcm-token"));

        mockMvc.perform(get("/api/mobile/notifications/push-devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceName").value("Pixel"));

        mockMvc.perform(post("/api/mobile/notifications/push-token/unregister")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pushToken": "fcm-token"
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void inboxAndReadUseAuthenticatedOperator() throws Exception {
        UUID id = UUID.fromString("adad1f5a-18b8-43d2-8e9c-c5f92037f693");
        var item = new MobileNotificationInboxService.Item(id, "OPERATIONS_DEGRADATION",
                "Degradación detectada", "Revisa Operaciones", "OPERATIONS", 3L,
                Instant.parse("2026-10-16T06:25:11Z"), null);
        when(inboxService.list(4L, 0)).thenReturn(new MobileNotificationInboxService.Inbox(
                "atenea-mobile-notification-inbox/v1", List.of(item), 1L, null));
        when(inboxService.markRead(4L, id)).thenReturn(item);
        when(inboxService.markAllRead(4L)).thenReturn(new MobileNotificationInboxService.ReadAllResult(
                Instant.parse("2026-10-16T06:30:00Z"), 0L));
        mockMvc.perform(get("/api/mobile/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].targetId").value(3));
        mockMvc.perform(post("/api/mobile/notifications/{id}/read", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(post("/api/mobile/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.readAt").value("2026-10-16T06:30:00Z"));
    }
}
