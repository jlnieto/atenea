package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileDiagnosticReceiptResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.mobile.MobileDiagnosticReportRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MobileDiagnosticService {
    public static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "generatedAt", "reason", "app", "device", "runtime", "lastCrash", "processExits", "events");
    private final MobileDiagnosticReportRepository repository;
    private final ObjectMapper objectMapper;

    public MobileDiagnosticService(MobileDiagnosticReportRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MobileDiagnosticReceiptResponse store(AuthenticatedOperator actor, MultipartFile file) {
        long operatorId = operatorId(actor);
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > MAX_REPORT_BYTES) throw tooLarge();
        try {
            if (file.getContentType() == null) throw invalid();
            MediaType contentType = MediaType.parseMediaType(file.getContentType());
            if (!"application".equals(contentType.getType()) || !"json".equals(contentType.getSubtype())) throw invalid();
        } catch (IllegalArgumentException exception) { throw invalid(); }
        byte[] bytes;
        try (var stream = file.getInputStream()) {
            bytes = stream.readNBytes(MAX_REPORT_BYTES + 1);
        } catch (IOException exception) {
            throw new MobileDiagnosticException(HttpStatus.BAD_REQUEST, "DIAGNOSTIC_READ_FAILED",
                    "No se pudo leer el informe de diagnóstico.");
        }
        if (bytes.length > MAX_REPORT_BYTES) throw tooLarge();
        JsonNode report = validate(bytes);
        String sha256 = hash(bytes);
        // The server binds identity to the authenticated operator and exact bytes.
        // Automatic auth/transport retries cannot create a second report or change its receipt.
        UUID id = UUID.nameUUIDFromBytes(("mobile-diagnostics-v1:" + operatorId + ":" + sha256)
                .getBytes(StandardCharsets.US_ASCII));
        repository.insertIfAbsent(id, operatorId, Instant.now(), Instant.parse(report.path("generatedAt").asText()),
                report.path("app").path("versionName").asText(), report.path("app").path("versionCode").intValue(),
                report.path("device").path("model").asText(), bytes.length, sha256, bytes);
        return receipt(actor, id);
    }

    @Transactional(readOnly = true)
    public List<MobileDiagnosticReceiptResponse> recent(AuthenticatedOperator actor, int limit) {
        if (limit < 1 || limit > 20) throw new MobileDiagnosticException(HttpStatus.BAD_REQUEST,
                "INVALID_DIAGNOSTIC_LIMIT", "Consulta entre 1 y 20 informes.");
        return repository.findByOperatorIdOrderByReceivedAtDescIdDesc(operatorId(actor), PageRequest.of(0, limit))
                .stream().map(MobileDiagnosticReceiptResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MobileDiagnosticReceiptResponse latest(AuthenticatedOperator actor) {
        return recent(actor, 1).stream().findFirst().orElseThrow(MobileDiagnosticService::notFound);
    }

    @Transactional(readOnly = true)
    public MobileDiagnosticReceiptResponse receipt(AuthenticatedOperator actor, UUID id) {
        return repository.findReceiptByIdAndOperatorId(id, operatorId(actor))
                .map(MobileDiagnosticReceiptResponse::from).orElseThrow(MobileDiagnosticService::notFound);
    }

    @Transactional(readOnly = true)
    public byte[] content(AuthenticatedOperator actor, UUID id) {
        var report = repository.findByIdAndOperatorId(id, operatorId(actor))
                .orElseThrow(MobileDiagnosticService::notFound);
        byte[] bytes = report.getReportBytes();
        if (bytes == null || bytes.length != report.getSizeBytes() || bytes.length > MAX_REPORT_BYTES
                || !hash(bytes).equals(report.getSha256())) {
            throw new MobileDiagnosticException(HttpStatus.INTERNAL_SERVER_ERROR, "DIAGNOSTIC_EVIDENCE_MISMATCH",
                    "El informe guardado no coincide con su checksum.");
        }
        return bytes;
    }

    private JsonNode validate(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            JsonNode report = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if (report == null || !report.isObject() || report.size() != FIELDS.size()) throw invalid();
            for (var names = report.fieldNames(); names.hasNext();) if (!FIELDS.contains(names.next())) throw invalid();
            Instant.parse(text(report, "generatedAt", 80));
            text(report, "reason", 128);
            JsonNode app = report.path("app");
            text(app, "versionName", 80);
            if (!app.path("versionCode").canConvertToInt() || !app.path("versionCode").isIntegralNumber()
                    || app.path("versionCode").intValue() < 1) throw invalid();
            text(report.path("device"), "model", 150);
            if (!report.path("runtime").isObject()
                    || !(report.path("lastCrash").isObject() || report.path("lastCrash").isNull())
                    || !report.path("processExits").isArray() || report.path("processExits").size() > 8
                    || !report.path("events").isArray() || report.path("events").size() > 220) throw invalid();
            return report;
        } catch (IOException | DateTimeParseException exception) { throw invalid(); }
    }

    private static String text(JsonNode parent, String field, int limit) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) throw invalid();
        return value.asText();
    }
    private static long operatorId(AuthenticatedOperator actor) {
        if (actor == null || actor.operatorId() == null || actor.operatorId() <= 0)
            throw new OperatorAuthenticationException("Inicia sesión para consultar o enviar diagnósticos.");
        return actor.operatorId();
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private static MobileDiagnosticException invalid() {
        return new MobileDiagnosticException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSTIC_REPORT",
                "El fichero no es un informe JSON válido de Atenea.");
    }
    private static MobileDiagnosticException tooLarge() {
        return new MobileDiagnosticException(HttpStatus.PAYLOAD_TOO_LARGE, "DIAGNOSTIC_REPORT_TOO_LARGE",
                "El informe de diagnóstico supera el límite de 4 MiB.");
    }
    private static MobileDiagnosticException notFound() {
        return new MobileDiagnosticException(HttpStatus.NOT_FOUND, "DIAGNOSTIC_NOT_FOUND",
                "No hay un informe de diagnóstico disponible.");
    }
}
