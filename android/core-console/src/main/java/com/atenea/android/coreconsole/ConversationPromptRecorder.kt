package com.atenea.android.coreconsole

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.time.Instant

internal class ConversationPromptRecorder(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    @Suppress("DEPRECATION")
    fun start(): ConversationPromptRecording {
        release()
        val file = File(context.cacheDir, "atenea-prompt-${Instant.now().toEpochMilli()}.m4a")
        val mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        outputFile = file
        return ConversationPromptRecording(file, "audio/mp4")
    }

    fun normalizedAmplitude(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amplitude / 32767f).coerceIn(0.02f, 1f)
    }

    fun stop(): ConversationPromptRecording? {
        val file = outputFile
        outputFile = null
        val stopped = runCatching { recorder?.stop() }.isSuccess
        releaseRecorder()
        return if (stopped && file != null && file.length() > 0L) {
            ConversationPromptRecording(file, "audio/mp4")
        } else {
            file?.delete()
            null
        }
    }

    fun release() {
        releaseRecorder()
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseRecorder() {
        recorder?.let { current ->
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        recorder = null
    }
}

internal data class ConversationPromptRecording(
    val file: File,
    val contentType: String
)
