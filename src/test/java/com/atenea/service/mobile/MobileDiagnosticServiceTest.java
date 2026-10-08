package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.mobile.MobileDiagnosticReportEntity;
import com.atenea.persistence.mobile.MobileDiagnosticReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class MobileDiagnosticServiceTest {
    private final AuthenticatedOperator actor = new AuthenticatedOperator(42L, "synthetic@atenea.test", "Test");
    private MobileDiagnosticReportRepository repository;
    private MobileDiagnosticService service;

    @BeforeEach void setUp() {
        repository = mock(MobileDiagnosticReportRepository.class);
        service = new MobileDiagnosticService(repository, new ObjectMapper());
    }

    @Test void uploadPreservesExactCrashBytesWithoutWorkspaceOrHostPathAndBindsReceiptToOperator() {
        emulateInsert();
        byte[] bytes = report();
        var receipt = service.store(actor, file(bytes));
        assertEquals(bytes.length, receipt.sizeBytes());
        assertEquals(MobileDiagnosticService.hash(bytes), receipt.sha256());
        assertEquals(142, receipt.appVersionCode());
        assertEquals("/api/mobile/diagnostics/" + receipt.id() + "/content", receipt.contentPath());
        verify(repository).insertIfAbsent(eq(receipt.id()), eq(42L), any(),
                eq(Instant.parse("2026-10-07T10:00:00Z")), eq("0.5.109"), eq(142), eq("SM-M135F"),
                eq(bytes.length), eq(receipt.sha256()), aryEq(bytes));
    }

    @Test void byteIdenticalRetryReturnsSameIdentityAndOriginalReceipt() {
        emulateInsert();
        var first = service.store(actor, file(report()));
        var retry = service.store(actor, file(report()));
        assertEquals(first, retry);
    }

    @Test void anonymousReadsAndUploadsAreRejectedBeforePersistence() {
        assertThrows(OperatorAuthenticationException.class, () -> service.store(null, file(report())));
        assertThrows(OperatorAuthenticationException.class, () -> service.latest(null));
        assertThrows(OperatorAuthenticationException.class, () -> service.content(null, UUID.randomUUID()));
        verifyNoInteractions(repository);
    }

    @Test void arbitraryFilesInvalidJsonOrCallerOwnershipMetadataAreNotAccepted() {
        for (byte[] invalid : List.of("arbitrary command".getBytes(StandardCharsets.UTF_8),
                "{\"app\":{}}".getBytes(StandardCharsets.UTF_8),
                (new String(report(), StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8),
                new String(report(), StandardCharsets.UTF_8).replace("\"reason\":", "\"reason\":\"duplicate\",\"reason\":")
                        .getBytes(StandardCharsets.UTF_8),
                new String(report(), StandardCharsets.UTF_8).replace("\"runtime\":{}", "\"runtime\":{},\"operatorId\":99")
                        .getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff})) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(MobileDiagnosticException.class,
                    () -> service.store(actor, file(invalid))).status());
        }
        assertThrows(MobileDiagnosticException.class, () -> service.store(actor,
                new MockMultipartFile("file", "report.json", "text/plain", report())));
        verifyNoInteractions(repository);
    }

    @Test void oversizedOrEmptyReportsAreRejectedWithoutWriting() {
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(MobileDiagnosticException.class,
                () -> service.store(actor, file(new byte[MobileDiagnosticService.MAX_REPORT_BYTES + 1]))).status());
        assertThrows(MobileDiagnosticException.class, () -> service.store(actor, file(new byte[0])));
        verifyNoInteractions(repository);
    }

    @Test void foreignReportAndUnknownReportAreIndistinguishableAndAlwaysOwnerScoped() {
        UUID id = UUID.randomUUID();
        when(repository.findReceiptByIdAndOperatorId(id, 42L)).thenReturn(Optional.empty());
        when(repository.findByIdAndOperatorId(id, 42L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(MobileDiagnosticException.class,
                () -> service.receipt(actor, id)).status());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(MobileDiagnosticException.class,
                () -> service.content(actor, id)).status());
        verify(repository).findReceiptByIdAndOperatorId(id, 42L);
        verify(repository).findByIdAndOperatorId(id, 42L);
        verifyNoMoreInteractions(repository);
    }

    @Test void downloadsVerifyOriginalSizeAndHashBeforeExposingEvidence() {
        UUID id = UUID.randomUUID();
        var entity = mock(MobileDiagnosticReportEntity.class);
        when(repository.findByIdAndOperatorId(id, 42L)).thenReturn(Optional.of(entity));
        when(entity.getReportBytes()).thenReturn(report());
        when(entity.getSizeBytes()).thenReturn(report().length);
        when(entity.getSha256()).thenReturn(MobileDiagnosticService.hash(report()), "0".repeat(64));
        assertArrayEquals(report(), service.content(actor, id));
        assertEquals("DIAGNOSTIC_EVIDENCE_MISMATCH", assertThrows(MobileDiagnosticException.class,
                () -> service.content(actor, id)).code());
    }

    @Test void recentQueryIsBoundedReadOnlyAndDoesNotCreateReports() {
        when(repository.findByOperatorIdOrderByReceivedAtDescIdDesc(eq(42L), any(Pageable.class))).thenReturn(List.of());
        assertTrue(service.recent(actor, 10).isEmpty());
        assertThrows(MobileDiagnosticException.class, () -> service.recent(actor, 21));
        assertThrows(MobileDiagnosticException.class, () -> service.recent(actor, 0));
        verify(repository).findByOperatorIdOrderByReceivedAtDescIdDesc(eq(42L), argThat(p -> p.getPageSize() == 10));
        verifyNoMoreInteractions(repository);
    }

    private void emulateInsert() {
        AtomicReference<MobileDiagnosticReportRepository.Receipt> stored = new AtomicReference<>();
        when(repository.insertIfAbsent(any(), anyLong(), any(), any(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), any(byte[].class))).thenAnswer(invocation -> {
            if (stored.get() != null) return 0;
            var receipt = mock(MobileDiagnosticReportRepository.Receipt.class);
            when(receipt.getId()).thenReturn(invocation.getArgument(0));
            when(receipt.getReceivedAt()).thenReturn(invocation.getArgument(2));
            when(receipt.getGeneratedAt()).thenReturn(invocation.getArgument(3));
            when(receipt.getAppVersionName()).thenReturn(invocation.getArgument(4));
            when(receipt.getAppVersionCode()).thenReturn(invocation.getArgument(5));
            when(receipt.getDeviceModel()).thenReturn(invocation.getArgument(6));
            when(receipt.getSizeBytes()).thenReturn(invocation.getArgument(7));
            when(receipt.getSha256()).thenReturn(invocation.getArgument(8));
            stored.set(receipt);
            return 1;
        });
        when(repository.findReceiptByIdAndOperatorId(any(), eq(42L))).thenAnswer(invocation -> Optional.of(stored.get()));
    }

    public static byte[] report() {
        return """
                {"generatedAt":"2026-10-07T10:00:00Z","reason":"manual_diagnostics_upload",
                 "app":{"versionName":"0.5.109","versionCode":142},"device":{"model":"SM-M135F"},
                 "runtime":{},"lastCrash":{"exception":"java.lang.IllegalStateException",
                 "message":"Synthetic scroll failure","stacktrace":"com.synthetic.Conversation.scroll"},
                 "processExits":[],"events":[]}
                """.getBytes(StandardCharsets.UTF_8);
    }
    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "../../ignored.json", "application/json", bytes);
    }
}
