package com.atenea.android.coreconsole

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.atenea.android.api.WorkSessionAttachmentCapability
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class LocalWorkSessionImage(
    val displayName: String,
    val contentType: String,
    val bytes: ByteArray,
    val preview: Bitmap? = null
)

internal fun Context.readWorkSessionImage(
    uri: Uri,
    capability: WorkSessionAttachmentCapability
): LocalWorkSessionImage {
    val metadata = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        ContentImageMetadata(
            displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
            sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        )
    }
    val declaredType = contentResolver.getType(uri)?.lowercase()
        ?: throw ImageSelectionException("No se pudo verificar el tipo de imagen.")
    if (declaredType !in capability.acceptedContentTypes || declaredType !in SUPPORTED_IMAGE_TYPES) {
        throw ImageSelectionException("Usa una imagen PNG, JPEG o WebP.")
    }
    val declaredSize = metadata?.sizeBytes
    if (declaredSize != null && declaredSize > capability.maxFileBytes) {
        throw ImageSelectionException("La imagen supera ${formatAttachmentBytes(capability.maxFileBytes)}.")
    }
    val input = contentResolver.openInputStream(uri)
        ?: throw ImageSelectionException("No se pudo leer la imagen seleccionada.")
    val validated = input.use {
        readValidatedWorkSessionImage(
            input = it,
            displayName = metadata?.displayName ?: "imagen",
            declaredContentType = declaredType,
            declaredSize = declaredSize,
            maxBytes = minOf(capability.maxFileBytes, capability.remainingSessionBytes)
        )
    }
    return validated.copy(preview = decodeBoundedImagePreview(validated.bytes))
}

internal fun readValidatedWorkSessionImage(
    input: InputStream,
    displayName: String,
    declaredContentType: String,
    declaredSize: Long?,
    maxBytes: Long
): LocalWorkSessionImage {
    require(maxBytes > 0L) { "La sesión no tiene espacio para imágenes." }
    if (declaredSize != null && declaredSize <= 0L) {
        throw ImageSelectionException("La imagen está vacía.")
    }
    if (declaredSize != null && declaredSize > maxBytes) {
        throw ImageSelectionException("La imagen supera ${formatAttachmentBytes(maxBytes)}.")
    }
    val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw ImageSelectionException("La imagen supera ${formatAttachmentBytes(maxBytes)}.")
        }
        output.write(buffer, 0, read)
    }
    val bytes = output.toByteArray()
    if (bytes.isEmpty()) {
        throw ImageSelectionException("La imagen está vacía.")
    }
    val detectedType = detectImageContentType(bytes)
        ?: throw ImageSelectionException("El contenido no es una imagen PNG, JPEG o WebP válida.")
    if (detectedType != declaredContentType) {
        throw ImageSelectionException("El contenido no coincide con el tipo de imagen declarado.")
    }
    return LocalWorkSessionImage(
        displayName = displayName.take(160).ifBlank { "imagen" },
        contentType = detectedType,
        bytes = bytes
    )
}

internal fun detectImageContentType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    else -> null
}

private fun decodeBoundedImagePreview(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal class ImageSelectionException(message: String) : IllegalArgumentException(message)

private data class ContentImageMetadata(val displayName: String?, val sizeBytes: Long?)

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
)
