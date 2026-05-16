package com.atenea.android.voiceruntime

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcRealtimeClient(
    private val context: Context,
    private val clientSecret: String,
    private val outputVolumeProvider: () -> Float,
    private val listener: Listener
) {
    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onEvent(message: String)
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    private val lock = Any()
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var localAudioSource: org.webrtc.AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var remoteAudioTrack: org.webrtc.AudioTrack? = null
    private var closed = false
    private var connected = false

    val isOpen: Boolean
        get() = synchronized(lock) { connected && dataChannel?.state() == DataChannel.State.OPEN }

    fun connect() {
        listener.onConnecting()
        runCatching {
            AteneaDiagnostics.info("webrtc", "factory_initialize_start")
            ensureFactoryInitialized(context)
            AteneaDiagnostics.info("webrtc", "factory_create_start")
            val localFactory = createFactory(context)
            AteneaDiagnostics.info("webrtc", "peer_connection_create_start")
            val localPeerConnection = createPeerConnection(localFactory)
            AteneaDiagnostics.info("webrtc", "data_channel_create_start")
            val localDataChannel = localPeerConnection.createDataChannel("oai-events", DataChannel.Init())
            localDataChannel.registerObserver(dataChannelObserver())
            AteneaDiagnostics.info("webrtc", "audio_track_create_start")
            val audioSource = localFactory.createAudioSource(MediaConstraints())
            val audioTrack = localFactory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource)
            audioTrack.setEnabled(true)
            val audioSender = localPeerConnection.addTrack(audioTrack, listOf(LOCAL_STREAM_ID))
                ?: throw IllegalStateException("No se pudo publicar el track local de audio")

            synchronized(lock) {
                factory = localFactory
                peerConnection = localPeerConnection
                dataChannel = localDataChannel
                localAudioSource = audioSource
                localAudioTrack = audioTrack
            }
            AteneaDiagnostics.info("webrtc", "audio_sendrecv_track_added", mapOf("senderId" to audioSender.id()))

            AteneaDiagnostics.info("webrtc", "sdp_offer_start")
            val offer = createOffer(localPeerConnection)
            AteneaDiagnostics.info("webrtc", "sdp_offer_created", describeSdp(offer))
            AteneaDiagnostics.info("webrtc", "local_description_set_start")
            setLocalDescription(localPeerConnection, offer)
            AteneaDiagnostics.info("webrtc", "ice_gathering_wait_start")
            waitForIceGathering(localPeerConnection)
            val sdp = localPeerConnection.localDescription?.description ?: offer.description
            AteneaDiagnostics.info("webrtc", "local_sdp_offer_ready", describeSdp(sdp))
            AteneaDiagnostics.info("webrtc", "openai_sdp_post_start", mapOf("sdpLength" to sdp.length))
            val answerSdp = RealtimeSdpNormalizer.normalizeAnswerBody(postOffer(sdp))
            AteneaDiagnostics.info("webrtc", "remote_sdp_answer_received", describeAnswerSdp(answerSdp))
            val normalizedAnswer = RealtimeSdpNormalizer.normalizeRemoteAnswerForAndroid(answerSdp)
            if (normalizedAnswer.changed) {
                AteneaDiagnostics.info(
                    "webrtc",
                    "remote_sdp_answer_normalized",
                    describeAnswerSdp(normalizedAnswer.sdp) + mapOf("changes" to normalizedAnswer.changes.joinToString(", "))
                )
            }
            AteneaDiagnostics.info("webrtc", "remote_description_set_start", mapOf("sdpLength" to normalizedAnswer.sdp.length))
            setRemoteDescription(
                localPeerConnection,
                SessionDescription(SessionDescription.Type.ANSWER, normalizedAnswer.sdp)
            )
            AteneaDiagnostics.info("webrtc", "remote_description_set_ok")
        }.onFailure { error ->
            AteneaDiagnostics.error("webrtc", "connect_failed", error)
            close("Error conectando WebRTC")
            listener.onError(error.message ?: error.javaClass.simpleName)
        }
    }

    fun send(message: String): Boolean {
        val channel = synchronized(lock) { dataChannel }
        if (channel?.state() != DataChannel.State.OPEN) {
            AteneaDiagnostics.warn("webrtc", "data_channel_not_open", mapOf("state" to channel?.state()?.name))
            return false
        }
        val bytes = message.toByteArray(Charsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun setOutputVolume(volume: Float) {
        val safeVolume = volume.coerceIn(0.0f, 2.5f)
        synchronized(lock) {
            remoteAudioTrack?.setVolume(safeVolume.toDouble())
        }
    }

    fun close(reason: String) {
        val shouldNotify = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                connected = false
                true
            }
        }
        synchronized(lock) {
            AteneaDiagnostics.info("webrtc", "close", mapOf("reason" to reason))
            dataChannel?.unregisterObserver()
            dataChannel?.dispose()
            dataChannel = null
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null
            localAudioSource?.dispose()
            localAudioSource = null
            remoteAudioTrack = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            factory?.dispose()
            factory = null
            eglBase?.release()
            eglBase = null
            audioDeviceModule?.release()
            audioDeviceModule = null
        }
        if (shouldNotify) {
            listener.onDisconnected(reason)
        }
    }

    private fun createFactory(context: Context): PeerConnectionFactory {
        val egl = EglBase.create()
        val module = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        eglBase = egl
        audioDeviceModule = module
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    egl.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(factory: PeerConnectionFactory): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        AteneaDiagnostics.info(
            "webrtc",
            "rtc_config_created",
            mapOf(
                "iceServerCount" to iceServers.size,
                "bundlePolicy" to rtcConfig.bundlePolicy.name,
                "rtcpMuxPolicy" to rtcConfig.rtcpMuxPolicy.name,
                "tcpCandidatePolicy" to rtcConfig.tcpCandidatePolicy.name,
                "continualGatheringPolicy" to rtcConfig.continualGatheringPolicy.name
            )
        )
        return factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    val track = receiver.track()
                    if (track is org.webrtc.AudioTrack) {
                        track.setEnabled(true)
                        track.setVolume(outputVolumeProvider().coerceIn(0.0f, 2.5f).toDouble())
                        synchronized(lock) {
                            remoteAudioTrack = track
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    AteneaDiagnostics.info("webrtc", "ice_connection_state", mapOf("state" to state.name))
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> Unit
                        PeerConnection.IceConnectionState.DISCONNECTED -> listener.onDisconnected("ICE desconectado.")
                        PeerConnection.IceConnectionState.FAILED -> listener.onError("ICE fallo.")
                        PeerConnection.IceConnectionState.CLOSED -> listener.onDisconnected("ICE cerrado.")
                        else -> Unit
                    }
                }
            }
        ) ?: throw IllegalStateException("No se pudo crear PeerConnection")
    }

    private fun dataChannelObserver(): DataChannel.Observer =
        object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                AteneaDiagnostics.info("webrtc", "data_channel_state", mapOf("state" to dataChannel?.state()?.name))
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    markConnected()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onEvent(String(bytes, Charsets.UTF_8))
            }
        }

    private fun markConnected() {
        val shouldNotify = synchronized(lock) {
            if (closed || connected) {
                false
            } else {
                connected = true
                true
            }
        }
        if (shouldNotify) {
            listener.onConnected()
        }
    }

    private fun createOffer(peerConnection: PeerConnection): SessionDescription {
        val observer = BlockingSdpObserver()
        peerConnection.createOffer(observer, MediaConstraints())
        return observer.await()
    }

    private fun setLocalDescription(peerConnection: PeerConnection, description: SessionDescription) {
        val observer = BlockingSdpObserver()
        peerConnection.setLocalDescription(observer, description)
        observer.awaitSet()
    }

    private fun describeSdp(description: SessionDescription): Map<String, Any?> =
        describeSdp(description.description) + mapOf("type" to description.type.canonicalForm())

    private fun describeSdp(sdp: String, includeSanitizedBody: Boolean = false): Map<String, Any?> {
        val lines = sdp.lineSequence().toList()
        val details = mapOf(
            "sdpLength" to sdp.length,
            "sdpSha256" to sha256(sdp),
            "firstLine" to lines.firstOrNull().orEmpty().take(80),
            "audioMLineCount" to lines.count { it.startsWith("m=audio ") },
            "applicationMLineCount" to lines.count { it.startsWith("m=application ") },
            "rtpmapCount" to lines.count { it.startsWith("a=rtpmap:") },
            "candidateCount" to lines.count { it.startsWith("a=candidate:") },
            "hasIceUfrag" to lines.any { it.startsWith("a=ice-ufrag:") },
            "hasFingerprint" to lines.any { it.startsWith("a=fingerprint:") },
            "mLines" to lines.filter { it.startsWith("m=") }.joinToString(" | "),
            "midLines" to lines.filter { it.startsWith("a=mid:") }.joinToString(" | "),
            "candidateSummaries" to summarizeCandidates(lines)
        )
        return if (includeSanitizedBody) {
            details + mapOf("sanitizedSdp" to sanitizeSdpForDiagnostics(sdp))
        } else {
            details
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun setRemoteDescription(peerConnection: PeerConnection, description: SessionDescription) {
        val observer = BlockingSdpObserver()
        peerConnection.setRemoteDescription(observer, description)
        try {
            observer.awaitSet()
        } catch (error: IllegalStateException) {
            AteneaDiagnostics.warn(
                "webrtc",
                "remote_description_set_failed",
                describeSdp(description.description, includeSanitizedBody = true) + mapOf("message" to error.message)
            )
            throw error
        }
    }

    private fun waitForIceGathering(peerConnection: PeerConnection) {
        val deadline = System.currentTimeMillis() + ICE_GATHERING_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline &&
            peerConnection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE
        ) {
            Thread.sleep(50)
        }
    }

    private fun postOffer(sdp: String): String {
        val connection = (URL(REALTIME_CALLS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $clientSecret")
            setRequestProperty("Content-Type", "application/sdp")
            setRequestProperty("Accept", "application/sdp")
        }
        return try {
            connection.outputStream.use { output ->
                output.write(sdp.toByteArray(Charsets.UTF_8))
            }
            val body = if (connection.responseCode in 200..299) {
                AteneaDiagnostics.info("webrtc", "openai_sdp_post_ok", mapOf("http" to connection.responseCode))
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                AteneaDiagnostics.warn("webrtc", "openai_sdp_post_failed", mapOf("http" to connection.responseCode, "body" to errorBody.take(2000)))
                throw IllegalStateException("OpenAI Realtime WebRTC HTTP ${connection.responseCode}: $errorBody")
            }
            body.trim().ifBlank { throw IllegalStateException("OpenAI Realtime WebRTC devolvio SDP vacio") }
        } finally {
            connection.disconnect()
        }
    }

    private fun describeAnswerSdp(sdp: String, includeSanitizedBody: Boolean = false): Map<String, Any?> {
        val lines = sdp.lineSequence().toList()
        val details = mapOf(
            "sdpLength" to sdp.length,
            "sdpSha256" to sha256(sdp),
            "firstLine" to lines.firstOrNull().orEmpty().take(80),
            "audioMLineCount" to lines.count { it.startsWith("m=audio ") },
            "applicationMLineCount" to lines.count { it.startsWith("m=application ") },
            "candidateCount" to lines.count { it.startsWith("a=candidate:") },
            "hasIceUfrag" to lines.any { it.startsWith("a=ice-ufrag:") },
            "hasFingerprint" to lines.any { it.startsWith("a=fingerprint:") },
            "hasSetupActive" to lines.any { it == "a=setup:active" },
            "mLines" to lines.filter { it.startsWith("m=") }.joinToString(" | "),
            "midLines" to lines.filter { it.startsWith("a=mid:") }.joinToString(" | "),
            "candidateSummaries" to summarizeCandidates(lines)
        )
        return if (includeSanitizedBody) {
            details + mapOf("sanitizedSdp" to sanitizeSdpForDiagnostics(sdp))
        } else {
            details
        }
    }

    private fun summarizeCandidates(lines: List<String>): String {
        var currentMid = "session"
        val summaries = mutableListOf<String>()
        lines.forEach { line ->
            if (line.startsWith("a=mid:")) {
                currentMid = line.removePrefix("a=mid:")
            }
            if (line.startsWith("a=candidate:")) {
                val tokens = line.removePrefix("a=candidate:").split(Regex("\\s+")).filter { it.isNotBlank() }
                val typeIndex = tokens.indexOf("typ")
                val tcpTypeIndex = tokens.indexOf("tcptype")
                val summary = listOfNotNull(
                    "mid=$currentMid",
                    tokens.getOrNull(1)?.let { "component=$it" },
                    tokens.getOrNull(2)?.let { "protocol=${it.lowercase()}" },
                    if (typeIndex >= 0) tokens.getOrNull(typeIndex + 1)?.let { "type=$it" } else null,
                    if (tcpTypeIndex >= 0) tokens.getOrNull(tcpTypeIndex + 1)?.let { "tcpType=$it" } else null
                ).joinToString("/")
                summaries += summary.ifBlank { "mid=$currentMid/unparsed" }
            }
        }
        return summaries.joinToString(" | ")
    }

    private fun sanitizeSdpForDiagnostics(sdp: String): String =
        sdp.lineSequence()
            .map { line ->
                when {
                    line.startsWith("a=candidate:") -> "a=candidate:<redacted>"
                    line.startsWith("a=ice-pwd:") -> "a=ice-pwd:<redacted>"
                    line.startsWith("a=ice-ufrag:") -> "a=ice-ufrag:<redacted>"
                    line.startsWith("a=fingerprint:") -> "a=fingerprint:<redacted>"
                    else -> line
                }
            }
            .joinToString("\n")
            .take(MAX_DIAGNOSTIC_SDP_CHARS)

    private class BlockingSdpObserver : SdpObserver {
        private val latch = CountDownLatch(1)
        private var description: SessionDescription? = null
        private var error: String? = null

        override fun onCreateSuccess(description: SessionDescription) {
            this.description = description
            latch.countDown()
        }

        override fun onSetSuccess() {
            latch.countDown()
        }

        override fun onCreateFailure(error: String) {
            this.error = error
            latch.countDown()
        }

        override fun onSetFailure(error: String) {
            this.error = error
            latch.countDown()
        }

        fun await(): SessionDescription {
            awaitLatch()
            error?.let { throw IllegalStateException(it) }
            return description ?: throw IllegalStateException("Operacion SDP sin descripcion")
        }

        fun awaitSet() {
            awaitLatch()
            error?.let { throw IllegalStateException(it) }
        }

        private fun awaitLatch() {
            if (!latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Timeout SDP")
            }
        }
    }

    companion object {
        private const val REALTIME_CALLS_URL = "https://api.openai.com/v1/realtime/calls"
        private const val LOCAL_STREAM_ID = "atenea-local-audio"
        private const val LOCAL_AUDIO_TRACK_ID = "atenea-local-audio-track"
        private const val SDP_TIMEOUT_MS = 10_000L
        private const val ICE_GATHERING_TIMEOUT_MS = 2_000L
        private const val MAX_DIAGNOSTIC_SDP_CHARS = 6_000
        private var factoryInitialized = false

        @Synchronized
        private fun ensureFactoryInitialized(context: Context) {
            if (factoryInitialized) {
                return
            }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
    }
}
