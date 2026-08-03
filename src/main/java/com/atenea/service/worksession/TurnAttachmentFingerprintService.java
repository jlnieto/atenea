package com.atenea.service.worksession;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Produces versioned, domain-separated SHA-256 identities for image turns.
 * Text fields use a four-byte big-endian UTF-8 length prefix. A manifest then
 * writes its count and, in submitted order, each UUID as two big-endian longs,
 * its media type, its eight-byte size and its raw 32-byte content digest. The
 * request identity frames the normalized message followed by the raw manifest
 * digest.
 */
@Component
public class TurnAttachmentFingerprintService {

    private static final String MANIFEST_DOMAIN = "atenea-turn-attachment-manifest-v1";
    private static final String REQUEST_DOMAIN = "atenea-image-turn-request-v1";
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp"
    );

    public String normalizeMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Turn message must not be null");
        }
        String normalized = Normalizer.normalize(
                message.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC
        ).strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Turn message must not be blank");
        }
        return normalized;
    }

    public String attachmentManifestSha256(List<AttachmentFingerprintInput> attachments) {
        List<AttachmentFingerprintInput> validated = validateAttachments(attachments);
        return sha256(writeCanonical(output -> {
            writeText(output, MANIFEST_DOMAIN);
            output.writeInt(validated.size());
            for (AttachmentFingerprintInput attachment : validated) {
                output.writeLong(attachment.id().getMostSignificantBits());
                output.writeLong(attachment.id().getLeastSignificantBits());
                writeText(output, attachment.contentType());
                output.writeLong(attachment.sizeBytes());
                output.write(HexFormat.of().parseHex(attachment.sha256()));
            }
        }));
    }

    public String requestFingerprintSha256(
            String message,
            List<AttachmentFingerprintInput> attachments
    ) {
        String normalizedMessage = normalizeMessage(message);
        String manifestSha256 = attachmentManifestSha256(attachments);
        return sha256(writeCanonical(output -> {
            writeText(output, REQUEST_DOMAIN);
            writeText(output, normalizedMessage);
            output.write(HexFormat.of().parseHex(manifestSha256));
        }));
    }

    private List<AttachmentFingerprintInput> validateAttachments(
            List<AttachmentFingerprintInput> attachments
    ) {
        if (attachments == null || attachments.isEmpty() || attachments.size() > 4) {
            throw new IllegalArgumentException("An image turn requires one to four attachments");
        }
        Set<UUID> distinctIds = new HashSet<>();
        for (AttachmentFingerprintInput attachment : attachments) {
            if (attachment == null || attachment.id() == null) {
                throw new IllegalArgumentException("Attachment identity must not be null");
            }
            if (!distinctIds.add(attachment.id())) {
                throw new IllegalArgumentException("Attachment identities must be distinct");
            }
            if (!IMAGE_CONTENT_TYPES.contains(attachment.contentType())) {
                throw new IllegalArgumentException("Attachment media type is not canonical");
            }
            if (attachment.sizeBytes() <= 0) {
                throw new IllegalArgumentException("Attachment byte size must be positive");
            }
            if (attachment.sha256() == null
                    || !LOWERCASE_SHA256.matcher(attachment.sha256()).matches()) {
                throw new IllegalArgumentException("Attachment SHA-256 must be lowercase canonical hex");
            }
        }
        return List.copyOf(attachments);
    }

    private byte[] writeCanonical(CanonicalWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical fingerprint serialization failed", exception);
        }
    }

    private void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    private interface CanonicalWriter {
        void write(DataOutputStream output) throws IOException;
    }

    public record AttachmentFingerprintInput(
            UUID id,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
    }
}
