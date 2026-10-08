package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseControlTransportTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void unixTransportSendsClosedRequestAndAcceptsFragmentedReply() throws Exception {
        var address=UnixDomainSocketAddress.of(directory.resolve("fixture.sock"));
        try (var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             var thread=Executors.newSingleThreadExecutor()) {
            server.bind(address);
            var request=thread.submit(() -> {
                try (var channel=server.accept()) {
                    var reader=new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),java.nio.charset.StandardCharsets.UTF_8));
                    var json=mapper.readTree(reader.readLine());
                    for (String part:new String[]{"{\"ok\":true,", "\"result\":{\"state\":\"PREPARING\"}}\n"}) {
                        var buffer=ByteBuffer.wrap(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) channel.write(buffer);
                    }
                    return json;
                }
            });
            UUID id=UUID.randomUUID(); String sha="1".repeat(40);
            var client=new ReleaseControlClient(mapper,true,address);
            assertEquals("PREPARING",client.plan(id,DeliveryTarget.APP_PROD,sha).path("state").asText());
            var sent=request.get(5,TimeUnit.SECONDS);
            var fields=new java.util.HashSet<String>(); sent.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("operation","planId","target","sourceCommit"),fields);
            assertEquals(id.toString(),sent.path("planId").asText());
            assertEquals(sha,sent.path("sourceCommit").asText());
            assertEquals("PLAN",sent.path("operation").asText());
        }
    }

    @Test void missingSocketReportsSafeAmbiguityWithoutLeakingPaths() {
        var client=new ReleaseControlClient(mapper,true,UnixDomainSocketAddress.of(directory.resolve("absent.sock")));
        var error=assertThrows(DeliveryRejectedException.class,()->client.plan(UUID.randomUUID(),DeliveryTarget.APP_PROD,"1".repeat(40)));
        assertEquals("RELEASE_TRANSPORT_UNAVAILABLE",error.code());
        assertFalse(error.getMessage().contains(directory.toString()));
    }
}
