package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.service.worksession.AttachmentLimitException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class AttachmentUploadSpoolerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsAndValidatesEveryAllowedTypeWithPrivatePermissions() throws Exception {
        Path root = temporaryDirectory.resolve("spool");
        AttachmentUploadSpooler spooler = new AttachmentUploadSpooler(root);
        List<Fixture> fixtures = List.of(
                new Fixture("image/png", bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1),
                        AttachmentKind.IMAGE),
                new Fixture("image/jpeg", bytes(0xff, 0xd8, 0xff, 0xe0, 1),
                        AttachmentKind.IMAGE),
                new Fixture("image/webp", "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII),
                        AttachmentKind.IMAGE),
                new Fixture("text/plain; charset=utf-8", "texto válido".getBytes(StandardCharsets.UTF_8),
                        AttachmentKind.FILE),
                new Fixture("application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                        AttachmentKind.FILE),
                new Fixture("application/pdf", "%PDF-1.7 synthetic".getBytes(StandardCharsets.US_ASCII),
                        AttachmentKind.FILE),
                new Fixture("application/zip", bytes('P', 'K', 0x05, 0x06, 0, 0),
                        AttachmentKind.FILE));

        for (Fixture fixture : fixtures) {
            Path spooledPath;
            try (AttachmentUploadSpooler.SpooledUpload upload = spooler.spool(
                    new MockMultipartFile(
                            "file",
                            "fixture.bin",
                            fixture.contentType(),
                            fixture.content()),
                    1024)) {
                spooledPath = upload.path();
                assertTrue(Files.exists(spooledPath));
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(spooledPath));
                assertEquals(fixture.kind(), upload.kind());
                assertEquals(normalized(fixture.contentType()), upload.contentType());
                assertEquals(fixture.content().length, upload.sizeBytes());
                assertEquals(sha256(fixture.content()), upload.sha256());
                assertArrayEquals(fixture.content(), Files.readAllBytes(spooledPath));
            }
            assertFalse(Files.exists(spooledPath));
        }

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(root));
        assertRootEmpty(root);
    }

    @Test
    void rejectsMismatchedUnsupportedAndMalformedContentWithoutResidue() {
        Path root = temporaryDirectory.resolve("spool");
        AttachmentUploadSpooler spooler = new AttachmentUploadSpooler(root);

        AttachmentWorkerException mismatched = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(new MockMultipartFile(
                        "file", "not.png", "image/png", "plain".getBytes(StandardCharsets.UTF_8)),
                        1024));
        assertEquals("content_type_mismatch", mismatched.getCode());

        AttachmentWorkerException malformedJson = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(new MockMultipartFile(
                        "file", "broken.json", "application/json", "{".getBytes(StandardCharsets.UTF_8)),
                        1024));
        assertEquals("content_type_mismatch", malformedJson.getCode());

        AttachmentWorkerException multipleJsonValues = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(new MockMultipartFile(
                        "file",
                        "ambiguous.json",
                        "application/json",
                        "{} {}".getBytes(StandardCharsets.UTF_8)),
                        1024));
        assertEquals("content_type_mismatch", multipleJsonValues.getCode());

        AttachmentWorkerException malformedText = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(new MockMultipartFile(
                        "file", "broken.txt", "text/plain", bytes(0xc3, 0x28)),
                        1024));
        assertEquals("content_type_mismatch", malformedText.getCode());

        AttachmentWorkerException unsupported = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(new MockMultipartFile(
                        "file", "binary.bin", "application/octet-stream", bytes(1)),
                        1024));
        assertEquals("unsupported_content_type", unsupported.getCode());
        assertRootEmpty(root);
    }

    @Test
    void enforcesActualStreamBoundAndCleansReadFailures() {
        Path root = temporaryDirectory.resolve("spool");
        AttachmentUploadSpooler spooler = new AttachmentUploadSpooler(root);

        assertThrows(AttachmentLimitException.class, () -> spooler.spool(
                streamingFile("application/pdf", repeatedInput('%', 1025), 1),
                1024));
        assertRootEmpty(root);

        AttachmentWorkerException readFailure = assertThrows(
                AttachmentWorkerException.class,
                () -> spooler.spool(
                        streamingFile("application/pdf", failingInput(), 10),
                        1024));
        assertEquals("attachment_worker_unavailable", readFailure.getCode());
        assertRootEmpty(root);
    }

    @Test
    void rejectsNonPrivatePreexistingRootWithoutChangingIt() throws Exception {
        Path root = temporaryDirectory.resolve("spool");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        AttachmentUploadSpooler spooler = new AttachmentUploadSpooler(root);

        assertThrows(AttachmentWorkerException.class, () -> spooler.spool(
                new MockMultipartFile(
                        "file",
                        "document.pdf",
                        "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)),
                1024));

        assertEquals(PosixFilePermissions.fromString("rwxr-x---"),
                Files.getPosixFilePermissions(root));
        assertRootEmpty(root);
    }

    private static MultipartFile streamingFile(
            String contentType,
            InputStream input,
            long declaredSize
    ) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return "streamed.bin";
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public long getSize() {
                return declaredSize;
            }

            @Override
            public byte[] getBytes() {
                throw new AssertionError("Whole-file buffering must not be used");
            }

            @Override
            public InputStream getInputStream() {
                return input;
            }

            @Override
            public void transferTo(Path destination) {
                throw new AssertionError("Unbounded transfer must not be used");
            }

            @Override
            public void transferTo(java.io.File destination) {
                throw new AssertionError("Unbounded transfer must not be used");
            }
        };
    }

    private static InputStream repeatedInput(int value, int size) {
        return new InputStream() {
            private int remaining = size;

            @Override
            public int read() {
                if (remaining-- <= 0) {
                    return -1;
                }
                return value;
            }
        };
    }

    private static InputStream failingInput() {
        return new InputStream() {
            private final ByteArrayInputStream prefix = new ByteArrayInputStream(
                    "%PDF-".getBytes(StandardCharsets.US_ASCII));

            @Override
            public int read() throws IOException {
                int value = prefix.read();
                if (value != -1) {
                    return value;
                }
                throw new IOException("synthetic read failure");
            }
        };
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static String normalized(String contentType) {
        int separator = contentType.indexOf(';');
        return (separator >= 0 ? contentType.substring(0, separator) : contentType);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static void assertRootEmpty(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            assertEquals(0L, files.count());
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect the test spool", exception);
        }
    }

    private record Fixture(String contentType, byte[] content, AttachmentKind kind) {
    }
}
