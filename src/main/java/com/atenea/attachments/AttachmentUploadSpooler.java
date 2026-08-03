package com.atenea.attachments;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.service.worksession.AttachmentLimitException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AttachmentUploadSpooler {

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int SIGNATURE_BYTES = 16;
    private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/plain",
            "application/json",
            "application/pdf",
            "application/zip");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;

    public AttachmentUploadSpooler() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "atenea-attachment-upload-spool"));
    }

    AttachmentUploadSpooler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public SpooledUpload spool(MultipartFile file, long maxBytes) {
        if (file == null) {
            throw new IllegalArgumentException("Selecciona un fichero no vacío.");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("El límite del adjunto no es válido.");
        }

        String contentType = normalizedContentType(file.getContentType());
        if (!ACCEPTED_CONTENT_TYPES.contains(contentType)) {
            throw new AttachmentWorkerException(
                    "El formato del adjunto no está permitido.",
                    415,
                    "unsupported_content_type");
        }

        Path spoolPath = null;
        try {
            preparePrivateRoot();
            spoolPath = Files.createTempFile(
                    root,
                    "upload-",
                    ".spool",
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
            requirePrivateFile(spoolPath);

            MessageDigest digest = sha256Digest();
            byte[] signature = new byte[SIGNATURE_BYTES];
            int signatureLength = 0;
            long total = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (InputStream input = file.getInputStream();
                    OutputStream output = Files.newOutputStream(
                            spoolPath,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (total > maxBytes - read) {
                        throw new AttachmentLimitException(
                                "El adjunto supera el límite de 16 MiB.");
                    }
                    int signatureCopy = Math.min(read, SIGNATURE_BYTES - signatureLength);
                    if (signatureCopy > 0) {
                        System.arraycopy(buffer, 0, signature, signatureLength, signatureCopy);
                        signatureLength += signatureCopy;
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            if (total == 0) {
                throw new IllegalArgumentException("Selecciona un fichero no vacío.");
            }
            if (Files.size(spoolPath) != total) {
                throw new AttachmentWorkerException(
                        "El spool temporal no conservó el tamaño exacto del adjunto.",
                        409,
                        "attachment_spool_integrity_conflict");
            }

            byte[] exactSignature = Arrays.copyOf(signature, signatureLength);
            requireMatchingContent(contentType, exactSignature, spoolPath);
            AttachmentKind kind = contentType.startsWith("image/")
                    ? AttachmentKind.IMAGE
                    : AttachmentKind.FILE;
            return new SpooledUpload(
                    spoolPath,
                    contentType,
                    kind,
                    total,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw cleanupAfterFailure(
                    spoolPath,
                    new AttachmentWorkerException(
                            "No se pudo preparar el adjunto de forma privada.",
                            exception));
        } catch (RuntimeException exception) {
            throw cleanupAfterFailure(spoolPath, exception);
        }
    }

    private void preparePrivateRoot() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(
                        root,
                        PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent request created it; validate the exact result below.
            }
        }
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("attachment spool root is not a real directory");
        }
        if (!Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
                .equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
            throw new IOException("attachment spool root ownership is not private");
        }
    }

    private void requirePrivateFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS))
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .equals(PRIVATE_FILE_PERMISSIONS)) {
            throw new IOException("attachment spool file ownership is not private");
        }
    }

    private void requireMatchingContent(String contentType, byte[] signature, Path path)
            throws IOException {
        boolean matches = switch (contentType) {
            case "image/png" -> startsWith(signature, new byte[]{
                    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            case "image/jpeg" -> startsWith(signature, new byte[]{
                    (byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/webp" -> signature.length >= 12
                    && startsWith(signature, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && startsWith(
                            Arrays.copyOfRange(signature, 8, signature.length),
                            "WEBP".getBytes(StandardCharsets.US_ASCII));
            case "application/pdf" -> startsWith(
                    signature,
                    "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "application/zip" -> startsWithAny(signature, new byte[][]{
                    {'P', 'K', 0x03, 0x04},
                    {'P', 'K', 0x05, 0x06},
                    {'P', 'K', 0x07, 0x08}});
            case "application/json" -> isJson(path);
            case "text/plain" -> isUtf8TextWithoutNul(path);
            default -> false;
        };
        if (!matches) {
            throw new AttachmentWorkerException(
                    "El formato declarado no coincide con el contenido del adjunto.",
                    422,
                    "content_type_mismatch");
        }
    }

    private boolean isJson(Path path) throws IOException {
        try (JsonParser parser = new JsonFactory().createParser(path.toFile())) {
            int depth = 0;
            int rootValues = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0) {
                    rootValues++;
                    if (rootValues > 1) {
                        return false;
                    }
                }
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                    depth++;
                } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
                    depth--;
                }
            }
            return rootValues == 1 && depth == 0;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private boolean isUtf8TextWithoutNul(Path path) throws IOException {
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] characters = new char[8192];
            int read;
            while ((read = reader.read(characters)) != -1) {
                for (int index = 0; index < read; index++) {
                    if (characters[index] == '\0') {
                        return false;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private boolean startsWithAny(byte[] value, byte[][] prefixes) {
        for (byte[] prefix : prefixes) {
            if (startsWith(value, prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String normalizedContentType(String value) {
        if (value == null || value.isBlank()) {
            return "application/octet-stream";
        }
        int separator = value.indexOf(';');
        return (separator >= 0 ? value.substring(0, separator) : value)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private <T extends RuntimeException> T cleanupAfterFailure(Path path, T failure) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        return failure;
    }

    public static final class SpooledUpload implements AutoCloseable {

        private final Path path;
        private final String contentType;
        private final AttachmentKind kind;
        private final long sizeBytes;
        private final String sha256;

        private SpooledUpload(
                Path path,
                String contentType,
                AttachmentKind kind,
                long sizeBytes,
                String sha256
        ) {
            this.path = path;
            this.contentType = contentType;
            this.kind = kind;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        Path path() {
            return path;
        }

        String contentType() {
            return contentType;
        }

        AttachmentKind kind() {
            return kind;
        }

        long sizeBytes() {
            return sizeBytes;
        }

        String sha256() {
            return sha256;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                throw new AttachmentWorkerException(
                        "No se pudo eliminar el spool temporal del adjunto.",
                        exception);
            }
        }
    }
}
