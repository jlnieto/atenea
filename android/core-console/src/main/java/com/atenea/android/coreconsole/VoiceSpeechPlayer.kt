package com.atenea.android.coreconsole

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.atenea.android.api.MobileVoiceAudio
import java.io.File

internal class VoiceSpeechPlayer(
    private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null

    fun play(
        audio: MobileVoiceAudio,
        volume: Float,
        onStarted: () -> Unit,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val file = File(context.cacheDir, "atenea-voice-output.${audio.extension()}")
        file.writeBytes(audio.bytes)
        audioFile = file

        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(file.absolutePath)
        player.setVolume(volume.coerceIn(0.0f, 1.0f), volume.coerceIn(0.0f, 1.0f))
        player.setOnPreparedListener {
            if (mediaPlayer === it) {
                onStarted()
                it.start()
            }
        }
        player.setOnCompletionListener {
            if (mediaPlayer === it) {
                mediaPlayer = null
            }
            it.release()
            onCompletion()
        }
        player.setOnErrorListener { failedPlayer, _, _ ->
            if (mediaPlayer === failedPlayer) {
                mediaPlayer = null
            }
            failedPlayer.release()
            onError("No se pudo reproducir la voz de Atenea.")
            true
        }
        player.prepareAsync()
    }

    fun stop(): Boolean {
        val player = mediaPlayer ?: return false
        mediaPlayer = null
        runCatching {
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            if (player.isPlaying) {
                player.stop()
            }
        }
        player.release()
        return true
    }

    fun release() {
        stop()
        runCatching { audioFile?.delete() }
        audioFile = null
    }

    private fun MobileVoiceAudio.extension(): String = when {
        contentType.contains("wav", ignoreCase = true) -> "wav"
        contentType.contains("aac", ignoreCase = true) -> "aac"
        contentType.contains("ogg", ignoreCase = true) ||
            contentType.contains("opus", ignoreCase = true) -> "ogg"
        else -> "mp3"
    }
}
