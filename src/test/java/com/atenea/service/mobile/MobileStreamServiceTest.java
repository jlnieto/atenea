package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atenea.api.mobile.MobileSessionEventResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MobileStreamServiceTest {

    @Test
    void filtersAlreadySentAndRepeatedStableEventIds() {
        Set<String> sent = new HashSet<>(Set.of("progress:7:1"));
        MobileSessionEventResponse alreadySent = event("progress:7:1", 1L);
        MobileSessionEventResponse newEvent = event("progress:7:2", 2L);
        MobileSessionEventResponse repeatedInBatch = event("progress:7:2", 2L);

        List<MobileSessionEventResponse> unseen = MobileStreamService.unseenEvents(
                List.of(alreadySent, newEvent, repeatedInBatch), sent);

        assertEquals(List.of(newEvent), unseen);
        assertEquals(Set.of("progress:7:1", "progress:7:2"), sent);
    }

    private MobileSessionEventResponse event(String eventId, long sequence) {
        return new MobileSessionEventResponse(
                "RUN_PROGRESS_CHECKING",
                Instant.parse("2026-07-31T12:00:00Z").plusSeconds(sequence),
                "Comprobando el resultado",
                null,
                7L,
                null,
                null,
                eventId,
                sequence);
    }
}
