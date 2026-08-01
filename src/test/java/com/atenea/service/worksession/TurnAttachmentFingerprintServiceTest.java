package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atenea.service.worksession.TurnAttachmentFingerprintService.AttachmentFingerprintInput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnAttachmentFingerprintServiceTest {

    private final TurnAttachmentFingerprintService fingerprints =
            new TurnAttachmentFingerprintService();

    @Test
    void producesStableGoldenFingerprintsFromCanonicalMessageAndManifest() {
        List<AttachmentFingerprintInput> attachments = List.of(first(), second());

        String manifest = fingerprints.attachmentManifestSha256(attachments);
        String request = fingerprints.requestFingerprintSha256(
                "  Revisa cafe\u0301\r\nlínea dos \r ",
                attachments
        );

        assertEquals("Revisa café\nlínea dos", fingerprints.normalizeMessage(
                "  Revisa cafe\u0301\r\nlínea dos \r "));
        assertEquals("4cf77a4a090eb244575f02e6c53a3341b506a79c5ea8d64e01897048d5b66645", manifest);
        assertEquals("6f7ca4eada77f3c0239984fa12c1e33e9b8bd0d1c8d5a3c6df20ec2839a2f392", request);
        assertEquals(manifest, fingerprints.attachmentManifestSha256(List.of(first(), second())));
        assertEquals(request, fingerprints.requestFingerprintSha256(
                "Revisa café\nlínea dos",
                List.of(first(), second())
        ));
    }

    @Test
    void attachmentOrderChangesBothManifestAndRequestFingerprints() {
        List<AttachmentFingerprintInput> original = List.of(first(), second());
        List<AttachmentFingerprintInput> reversed = List.of(second(), first());

        assertNotEquals(
                fingerprints.attachmentManifestSha256(original),
                fingerprints.attachmentManifestSha256(reversed)
        );
        assertNotEquals(
                fingerprints.requestFingerprintSha256("Inspect", original),
                fingerprints.requestFingerprintSha256("Inspect", reversed)
        );
    }

    @Test
    void everyImmutableFieldAndTheNormalizedMessageAffectTheFingerprint() {
        AttachmentFingerprintInput baseline = first();
        String manifest = fingerprints.attachmentManifestSha256(List.of(baseline));
        String request = fingerprints.requestFingerprintSha256("Inspect", List.of(baseline));

        assertNotEquals(manifest, fingerprints.attachmentManifestSha256(List.of(
                new AttachmentFingerprintInput(
                        UUID.fromString("00000000-0000-0000-0000-000000000099"),
                        baseline.contentType(), baseline.sizeBytes(), baseline.sha256()))));
        assertNotEquals(manifest, fingerprints.attachmentManifestSha256(List.of(
                new AttachmentFingerprintInput(
                        baseline.id(), "image/webp", baseline.sizeBytes(), baseline.sha256()))));
        assertNotEquals(manifest, fingerprints.attachmentManifestSha256(List.of(
                new AttachmentFingerprintInput(
                        baseline.id(), baseline.contentType(), baseline.sizeBytes() + 1, baseline.sha256()))));
        assertNotEquals(manifest, fingerprints.attachmentManifestSha256(List.of(
                new AttachmentFingerprintInput(
                        baseline.id(), baseline.contentType(), baseline.sizeBytes(), "c".repeat(64)))));
        assertNotEquals(request, fingerprints.requestFingerprintSha256("Inspect carefully", List.of(baseline)));
    }

    @Test
    void rejectsNonCanonicalOrAmbiguousInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.requestFingerprintSha256("  \r\n ", List.of(first())));
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.attachmentManifestSha256(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.attachmentManifestSha256(List.of(first(), first())));
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.attachmentManifestSha256(List.of(new AttachmentFingerprintInput(
                        first().id(), "IMAGE/PNG", first().sizeBytes(), first().sha256()))));
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.attachmentManifestSha256(List.of(new AttachmentFingerprintInput(
                        first().id(), first().contentType(), 0, first().sha256()))));
        assertThrows(IllegalArgumentException.class,
                () -> fingerprints.attachmentManifestSha256(List.of(new AttachmentFingerprintInput(
                        first().id(), first().contentType(), first().sizeBytes(), "A".repeat(64)))));
    }

    private AttachmentFingerprintInput first() {
        return new AttachmentFingerprintInput(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "image/png",
                1024,
                "a".repeat(64)
        );
    }

    private AttachmentFingerprintInput second() {
        return new AttachmentFingerprintInput(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "image/jpeg",
                2048,
                "b".repeat(64)
        );
    }
}
