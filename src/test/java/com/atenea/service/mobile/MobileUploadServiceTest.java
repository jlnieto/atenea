package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.attachments.AttachmentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class MobileUploadServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyGlobalUploadRemainsCompatibleWhileScopedCapabilityIsDisabled() {
        AttachmentProperties properties = new AttachmentProperties();
        MobileUploadService service =
                new MobileUploadService(temporaryDirectory.toString(), new ObjectMapper(), properties);

        service.store(new MockMultipartFile(
                "file", "note.txt", "text/plain", "synthetic".getBytes()));

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("latest.json")));
    }

    @Test
    void enabledScopedCapabilityRejectsGlobalUploadWithoutWriting() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(true);
        MobileUploadService service =
                new MobileUploadService(temporaryDirectory.toString(), new ObjectMapper(), properties);

        assertThrows(MobileUploadException.class, () -> service.store(new MockMultipartFile(
                "file", "note.txt", "text/plain", "synthetic".getBytes())));

        assertFalse(Files.exists(temporaryDirectory.resolve("latest.json")));
    }
}
