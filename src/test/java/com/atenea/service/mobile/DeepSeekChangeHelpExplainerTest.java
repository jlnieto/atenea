package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.deepseek.DeepSeekProperties;
import com.atenea.service.core.SessionSpeechBriefingProperties;
import com.atenea.service.costs.ApiUsageRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeepSeekChangeHelpExplainerTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final ApiUsageRecordingService usage=mock(ApiUsageRecordingService.class);
    private HttpServer server;
    private final AtomicReference<String> requestBody=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private DeepSeekChangeHelpExplainer client() {
        var connection=new DeepSeekProperties(); connection.setApiBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        connection.setApiKey("synthetic-test-key");
        var config=new SessionSpeechBriefingProperties(); config.setEnabled(true);
        return new DeepSeekChangeHelpExplainer(connection,config,HttpClient.newHttpClient(),mapper,usage);
    }
    private void answer(String stage,String destination,String text) {
        server.createContext("/chat/completions",exchange->{
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String inner=mapper.writeValueAsString(Map.of("stage",stage,"destination",destination,"answer",text));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",java.util.List.of(Map.of("message",Map.of("content",inner))),
                    "usage",Map.of("prompt_tokens",100,"completion_tokens",12,"total_tokens",112)));
            exchange.sendResponseHeaders(200,output.length); exchange.getResponseBody().write(output); exchange.close();
        });
    }
    @Test void existingExplanationProviderHasNoToolsAndReceivesAllowlistedState() throws Exception {
        answer("VALIDATION_REQUIRED","CHANGE","El cambio está guardado. Falta comprobarlo.");
        var context=new ChangeHelpFixture().build(); var guide=ChangeHelpGuide.explain(context,"Qué hago",false);
        assertEquals("El cambio está guardado. Falta comprobarlo.",client().explain(context,"Qué hago",guide));
        var body=mapper.readTree(requestBody.get()); assertFalse(body.has("tools"));
        String content=body.path("messages").path(1).path("content").asText();
        assertTrue(content.contains("recordedState")); assertTrue(content.contains("requiredChecks"));
        assertFalse(content.contains("synthetic-test-key")); assertFalse(content.contains("/srv/"));
        verify(usage).record(argThat(v -> "change_help".equals(v.feature()) && v.agentRunId()==null));
    }
    @Test void modelCannotChangeStageOrNavigation() {
        answer("PUBLICATION","CHAT","Publica ahora");
        var context=new ChangeHelpFixture().build();
        assertThrows(IllegalStateException.class,()->client().explain(context,"Hazlo",ChangeHelpGuide.explain(context,"",false)));
    }
    @Test void commandAndSecretExplanationsAreRejected() {
        assertFalse(DeepSeekChangeHelpExplainer.safeExplanation("Haz ssh al VPS y ejecuta sudo"));
        assertFalse(DeepSeekChangeHelpExplainer.safeExplanation("He desplegado el backend"));
        assertFalse(DeepSeekChangeHelpExplainer.safeExplanation("Bearer synthetic-token"));
        assertFalse(DeepSeekChangeHelpExplainer.safeExplanation("x".repeat(1801)));
    }
    @Test void noNewProviderOrCredentialIsImplicitlyEnabled() {
        var connection=new DeepSeekProperties(); var config=new SessionSpeechBriefingProperties();
        var client=new DeepSeekChangeHelpExplainer(connection,config,HttpClient.newHttpClient(),mapper,usage);
        assertFalse(client.available()); config.setEnabled(true); assertFalse(client.available());
        verifyNoInteractions(usage);
    }
}
