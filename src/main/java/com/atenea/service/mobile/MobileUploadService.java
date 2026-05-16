package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileUploadResponse;
import com.atenea.api.mobile.MobileUploadTelemetryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MobileUploadService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Path uploadRoot;
    private final ObjectMapper objectMapper;

    public MobileUploadService(
            @Value("${atenea.mobile-uploads.root:/workspace/repos/internal/atenea/operator-uploads}") String uploadRoot,
            ObjectMapper objectMapper
    ) {
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public MobileUploadResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MobileUploadException("No se ha recibido ningún fichero.");
        }

        Instant uploadedAt = Instant.now();
        String originalFilename = sanitize(file.getOriginalFilename());
        String storedFilename = FILE_TIME_FORMAT.format(uploadedAt)
                + "-"
                + UUID.randomUUID().toString().substring(0, 8)
                + "-"
                + originalFilename;
        Path inboxDir = uploadRoot.resolve("inbox").resolve(DAY_FORMAT.format(uploadedAt)).normalize();
        Path target = inboxDir.resolve(storedFilename).normalize();
        ensureInsideRoot(target);

        try {
            long startedAt = System.nanoTime();
            Files.createDirectories(inboxDir);
            long directoriesReadyAt = System.nanoTime();
            applyDirectoryPermissions(inboxDir);
            long directoryPermissionsReadyAt = System.nanoTime();
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            long copiedAt = System.nanoTime();
            applyFilePermissions(target);
            long filePermissionsReadyAt = System.nanoTime();
            MobileUploadResponse response = new MobileUploadResponse(
                    originalFilename,
                    storedFilename,
                    firstNonBlank(file.getContentType(), "application/octet-stream"),
                    file.getSize(),
                    target.toString(),
                    uploadRoot.resolve("latest.json").toString(),
                    uploadedAt,
                    new MobileUploadTelemetryResponse(
                            0L,
                            elapsedMillis(startedAt, directoriesReadyAt),
                            elapsedMillis(directoryPermissionsReadyAt, copiedAt),
                            elapsedMillis(directoriesReadyAt, directoryPermissionsReadyAt)
                                    + elapsedMillis(copiedAt, filePermissionsReadyAt),
                            0L));
            writeMetadata(response);
            long metadataReadyAt = System.nanoTime();
            return new MobileUploadResponse(
                    response.originalFilename(),
                    response.storedFilename(),
                    response.contentType(),
                    response.sizeBytes(),
                    response.storedPath(),
                    response.latestMetadataPath(),
                    response.uploadedAt(),
                    new MobileUploadTelemetryResponse(
                            elapsedMillis(startedAt, metadataReadyAt),
                            elapsedMillis(startedAt, directoriesReadyAt),
                            elapsedMillis(directoryPermissionsReadyAt, copiedAt),
                            elapsedMillis(directoriesReadyAt, directoryPermissionsReadyAt)
                                    + elapsedMillis(copiedAt, filePermissionsReadyAt),
                            elapsedMillis(filePermissionsReadyAt, metadataReadyAt)));
        } catch (IOException exception) {
            throw new UncheckedIOException("No se pudo guardar el fichero subido.", exception);
        }
    }

    private void writeMetadata(MobileUploadResponse response) throws IOException {
        Files.createDirectories(uploadRoot);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("originalFilename", response.originalFilename());
        metadata.put("storedFilename", response.storedFilename());
        metadata.put("contentType", response.contentType());
        metadata.put("sizeBytes", response.sizeBytes());
        metadata.put("storedPath", response.storedPath());
        metadata.put("uploadedAt", response.uploadedAt().toString());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Path latestPath = uploadRoot.resolve("latest.json");
        Path historyPath = uploadRoot.resolve("uploads.jsonl");
        Files.writeString(latestPath, json + "\n", StandardCharsets.UTF_8);
        Files.writeString(
                historyPath,
                objectMapper.writeValueAsString(metadata) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        applyFilePermissions(latestPath);
        applyFilePermissions(historyPath);
    }

    private void ensureInsideRoot(Path target) {
        if (!target.startsWith(uploadRoot)) {
            throw new MobileUploadException("Ruta de subida no permitida.");
        }
    }

    private String sanitize(String filename) {
        String value = firstNonBlank(filename, "upload.bin")
                .replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        value = value.replaceAll("_+", "_");
        return value.isBlank() ? "upload.bin" : value;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private long elapsedMillis(long start, long end) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, end - start));
    }

    private void applyDirectoryPermissions(Path directory) throws IOException {
        Path current = uploadRoot;
        applyPosixPermissions(current, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        ));
        for (Path part : uploadRoot.relativize(directory)) {
            current = current.resolve(part);
            applyPosixPermissions(current, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        }
    }

    private void applyFilePermissions(Path file) throws IOException {
        applyPosixPermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ
        ));
    }

    private void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (java.nio.file.FileSystemException ignored) {
            // The root upload directory can be owned by the host user. Keep the upload working.
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems keep the default JVM permissions.
        }
    }
}
