package com.atenea.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class AteneaApiClient(
    baseUrl: String,
    private val accessTokenProvider: () -> String?,
    private val refreshTokenProvider: () -> String? = { null },
    private val sessionUpdater: (MobileAuthSession) -> Unit = {}
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    suspend fun login(email: String, password: String): MobileAuthSession = postJson(
        path = "/api/mobile/auth/login",
        body = JSONObject()
            .put("email", email)
            .put("password", password),
        authenticated = false,
        parser = ::parseMobileAuthSession
    )

    suspend fun refresh(refreshToken: String): MobileAuthSession = postJson(
        path = "/api/mobile/auth/refresh",
        body = JSONObject()
            .put("refreshToken", refreshToken),
        authenticated = false,
        parser = ::parseMobileAuthSession
    )

    suspend fun runCoreCommand(
        input: String,
        scope: CoreScope = CoreScope.GLOBAL,
        projectId: Long? = null,
        workSessionId: Long? = null
    ): CoreCommandResponse = runCommand(
        input = input,
        channel = "TEXT",
        scope = scope,
        projectId = projectId,
        workSessionId = workSessionId
    )

    suspend fun runVoiceCommand(
        input: String,
        scope: CoreScope = CoreScope.GLOBAL,
        projectId: Long? = null,
        workSessionId: Long? = null
    ): CoreCommandResponse = runCommand(
        input = input,
        channel = "VOICE",
        scope = scope,
        projectId = projectId,
        workSessionId = workSessionId
    )

    private suspend fun runCommand(
        input: String,
        channel: String,
        scope: CoreScope,
        projectId: Long?,
        workSessionId: Long?
    ): CoreCommandResponse = postJson(
        path = "/api/core/commands",
        body = JSONObject()
            .put("input", input)
            .put("channel", channel)
            .put(
                "context",
                JSONObject()
                    .putNullable("projectId", projectId)
                    .putNullable("workSessionId", workSessionId)
                    .putNullable("operatorKey", null)
                    .put("scope", scope.name)
            )
            .put(
                "confirmation",
                JSONObject()
                    .put("confirmed", false)
                    .putNullable("confirmationToken", null)
            ),
        authenticated = true,
        parser = ::parseCoreCommandResponse
    )

    suspend fun confirmCoreCommand(
        commandId: Long,
        confirmationToken: String
    ): CoreCommandResponse = postJson(
        path = "/api/core/commands/$commandId/confirm",
        body = JSONObject()
            .put("confirmationToken", confirmationToken),
        authenticated = true,
        parser = ::parseCoreCommandResponse
    )

    suspend fun fetchCoreCommandHistory(): List<CoreCommandSummary> = getJson(
        path = "/api/core/commands",
        authenticated = true
    ) { json ->
        val items = json.optJSONArray("items") ?: JSONArray()
        List(items.length()) { index ->
            parseCoreCommandSummary(items.getJSONObject(index))
        }
    }

    suspend fun fetchCoreCommand(commandId: Long): CoreCommandResponse = getJson(
        path = "/api/core/commands/$commandId",
        authenticated = true,
        parser = ::parseCoreCommandResponse
    )

    suspend fun fetchMobileProjectsOverview(): List<MobileProjectOverview> = getJsonArray(
        path = "/api/mobile/projects/overview",
        authenticated = true
    ) { items ->
        List(items.length()) { index -> parseMobileProjectOverview(items.getJSONObject(index)) }
    }

    suspend fun resolveMobileWorkSession(projectId: Long, title: String? = null): ResolveMobileWorkSessionResult = postJson(
        path = "/api/mobile/projects/$projectId/sessions/resolve",
        body = JSONObject().putNullable("title", title),
        authenticated = true,
        parser = ::parseResolveMobileWorkSessionResult
    )

    suspend fun fetchMobileWorkSessionConversation(sessionId: Long): MobileWorkSessionConversation = getJson(
        path = "/api/mobile/sessions/$sessionId/conversation",
        authenticated = true,
        parser = ::parseMobileWorkSessionConversation
    )

    suspend fun fetchMobileWorkSessionSummary(sessionId: Long): MobileSessionSummary = getJson(
        path = "/api/mobile/sessions/$sessionId/summary",
        authenticated = true,
        parser = ::parseMobileSessionSummary
    )

    suspend fun fetchMobileSessionDeliverables(sessionId: Long): SessionDeliverablesView = getJson(
        path = "/api/mobile/sessions/$sessionId/deliverables",
        authenticated = true,
        parser = ::parseSessionDeliverablesView
    )

    suspend fun fetchMobileSessionDeliverable(
        sessionId: Long,
        deliverableId: Long
    ): SessionDeliverable = getJson(
        path = "/api/mobile/sessions/$sessionId/deliverables/$deliverableId",
        authenticated = true,
        parser = ::parseSessionDeliverable
    )

    suspend fun fetchMobileSessionEvents(
        sessionId: Long,
        after: String? = null,
        limit: Int = 50
    ): MobileSessionEvents = getJson(
        path = buildString {
            append("/api/mobile/sessions/")
            append(sessionId)
            append("/events?limit=")
            append(limit)
            after?.takeIf { it.isNotBlank() }?.let {
                append("&after=")
                append(URLEncoder.encode(it, Charsets.UTF_8.name()))
            }
        },
        authenticated = true,
        parser = ::parseMobileSessionEvents
    )

    suspend fun createMobileWorkSessionTurn(sessionId: Long, message: String): MobileWorkSessionConversation = postJson(
        path = "/api/mobile/sessions/$sessionId/turns",
        body = JSONObject().put("message", message),
        authenticated = true
    ) { json -> parseMobileWorkSessionConversation(json.getJSONObject("view")) }

    suspend fun resolveMobileRescueSession(projectId: Long, title: String? = "Rescate operativo"): ResolveMobileRescueSessionResult = postJson(
        path = "/api/mobile/projects/$projectId/rescue-sessions/resolve",
        body = JSONObject().putNullable("title", title),
        authenticated = true,
        parser = ::parseResolveMobileRescueSessionResult
    )

    suspend fun fetchMobileRescueConversation(sessionId: Long): MobileRescueConversation = getJson(
        path = "/api/mobile/rescue-sessions/$sessionId/conversation",
        authenticated = true,
        parser = ::parseMobileRescueConversation
    )

    suspend fun createMobileRescueTurn(sessionId: Long, message: String): MobileRescueConversation = postJson(
        path = "/api/mobile/rescue-sessions/$sessionId/turns",
        body = JSONObject().put("message", message),
        authenticated = true
    ) { json -> parseMobileRescueConversation(json.getJSONObject("view")) }

    suspend fun fetchOperationsHosts(): List<ManagedHost> = getJsonArray(
        path = "/api/mobile/operations/hosts",
        authenticated = true
    ) { items ->
        List(items.length()) { index ->
            parseManagedHost(items.getJSONObject(index))
        }
    }

    suspend fun fetchOperationsHostStatus(hostId: Long): OperationsHostStatus = getJson(
        path = "/api/mobile/operations/hosts/$hostId/status",
        authenticated = true,
        parser = ::parseOperationsHostStatus
    )

    suspend fun fetchOperationsIncidents(): List<OperationsIncident> = getJson(
        path = "/api/mobile/operations/incidents",
        authenticated = true
    ) { json ->
        val items = json.optJSONArray("incidents") ?: JSONArray()
        List(items.length()) { index ->
            parseOperationsIncident(items.getJSONObject(index))
        }
    }

    suspend fun uploadMobileFile(
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: ((MobileUploadTransferProgress) -> Unit)? = null
    ): MobileUpload = postMultipartFile(
        path = "/api/mobile/uploads",
        fieldName = "file",
        fileName = fileName,
        contentType = contentType,
        bytes = bytes,
        onProgress = onProgress,
        parser = ::parseMobileUpload
    )

    suspend fun fetchVoiceFocus(): MobileVoiceFocus = getJson(
        path = "/api/mobile/voice/focus",
        authenticated = true,
        parser = ::parseMobileVoiceFocus
    )

    suspend fun updateVoiceFocus(
        domain: VoiceDomain,
        activity: String? = null
    ): MobileVoiceFocus = postJson(
        path = "/api/mobile/voice/focus",
        body = JSONObject()
            .put("domain", domain.name)
            .putNullable("projectId", null)
            .putNullable("workSessionId", null)
            .putNullable("activeCommandId", null)
            .putNullable("managedHostId", null)
            .putNullable("activity", activity)
            .putNullable("playback", null),
        authenticated = true,
        parser = ::parseMobileVoiceFocus
    )

    suspend fun updateVoicePlayback(
        focus: MobileVoiceFocus?,
        playback: MobileVoicePlayback,
        activeCommandId: Long? = focus?.activeCommandId
    ): MobileVoiceFocus = postJson(
        path = "/api/mobile/voice/focus",
        body = JSONObject()
            .put("domain", (focus?.domain ?: VoiceDomain.NONE).name)
            .putNullable("projectId", focus?.projectId)
            .putNullable("workSessionId", focus?.workSessionId)
            .putNullable("activeCommandId", activeCommandId)
            .putNullable("managedHostId", focus?.managedHostId)
            .putNullable("activity", focus?.activity)
            .put(
                "playback",
                JSONObject()
                    .putNullable("sourceType", playback.sourceType)
                    .putNullable("sourceId", playback.sourceId)
                    .putNullable("segmentIndex", playback.segmentIndex)
                    .putNullable("segmentCount", playback.segmentCount)
            ),
        authenticated = true,
        parser = ::parseMobileVoiceFocus
    )

    suspend fun fetchActiveVoiceNotes(): List<MobileVoiceNote> = getJson(
        path = "/api/mobile/voice/notes/active",
        authenticated = true
    ) { json ->
        val items = json.optJSONArray("notes") ?: JSONArray()
        List(items.length()) { index -> parseMobileVoiceNote(items.getJSONObject(index)) }
    }

    suspend fun fetchVoiceNotesState(): MobileVoiceNotesState = getJson(
        path = "/api/mobile/voice/notes/state",
        authenticated = true,
        parser = ::parseMobileVoiceNotesState
    )

    suspend fun createVoiceNote(text: String): MobileVoiceNote = postJson(
        path = "/api/mobile/voice/notes",
        body = JSONObject().put("text", text),
        authenticated = true,
        parser = ::parseMobileVoiceNote
    )

    suspend fun archiveVoiceNote(noteId: Long): MobileVoiceNote = postJson(
        path = "/api/mobile/voice/notes/$noteId/archive",
        body = JSONObject(),
        authenticated = true,
        parser = ::parseMobileVoiceNote
    )

    suspend fun archiveLastVoiceNote(): MobileVoiceNote = postJson(
        path = "/api/mobile/voice/notes/archive-last",
        body = JSONObject(),
        authenticated = true,
        parser = ::parseMobileVoiceNote
    )

    suspend fun archiveActiveVoiceNotes(): List<MobileVoiceNote> = postJson(
        path = "/api/mobile/voice/notes/archive-active",
        body = JSONObject(),
        authenticated = true
    ) { json ->
        val items = json.optJSONArray("notes") ?: JSONArray()
        List(items.length()) { index -> parseMobileVoiceNote(items.getJSONObject(index)) }
    }

    suspend fun sendActiveVoiceNotes(instruction: String?): MobileVoiceNotesSendResult = postJson(
        path = "/api/mobile/voice/notes/send",
        body = JSONObject().putNullable("instruction", instruction),
        authenticated = true,
        parser = ::parseMobileVoiceNotesSendResult
    )

    suspend fun createVoiceNoteSendIntent(instruction: String?): MobileVoiceNoteSendIntent = postJson(
        path = "/api/mobile/voice/notes/send-intents",
        body = JSONObject().putNullable("instruction", instruction),
        authenticated = true,
        parser = ::parseMobileVoiceNoteSendIntent
    )

    suspend fun confirmVoiceNoteSendIntent(sendIntentId: Long): MobileVoiceNoteSendConfirmResult = postJson(
        path = "/api/mobile/voice/notes/send-intents/$sendIntentId/confirm",
        body = JSONObject(),
        authenticated = true,
        parser = ::parseMobileVoiceNoteSendConfirmResult
    )

    suspend fun cancelVoiceNoteSendIntent(sendIntentId: Long): MobileVoiceNoteSendIntent = postJson(
        path = "/api/mobile/voice/notes/send-intents/$sendIntentId/cancel",
        body = JSONObject(),
        authenticated = true,
        parser = ::parseMobileVoiceNoteSendIntent
    )

    suspend fun fetchMobileVoiceCodexStatus(): MobileVoiceCodexStatus = getJson(
        path = "/api/mobile/voice/codex/latest-status",
        authenticated = true,
        parser = ::parseMobileVoiceCodexStatus
    )

    suspend fun synthesizeMobileVoiceSpeech(
        text: String,
        voice: String? = null,
        speed: Double? = null
    ): MobileVoiceAudio = postJsonBytes(
        path = "/api/mobile/voice/speech",
        body = JSONObject()
            .put("text", text)
            .putNullable("voice", voice)
            .putNullable("speed", speed),
        authenticated = true
    ) { response ->
        MobileVoiceAudio(
            bytes = response.bytes,
            contentType = response.contentType ?: "audio/mpeg"
        )
    }

    suspend fun createMobileVoiceRealtimeSession(
        clientContext: String? = null,
        voice: String? = null,
        speed: Double? = null
    ): MobileVoiceRealtimeSession = postJson(
        path = "/api/mobile/voice/realtime/session",
        body = JSONObject().apply {
            clientContext?.takeIf { it.isNotBlank() }?.let { put("clientContext", it) }
            voice?.takeIf { it.isNotBlank() }?.let { put("voice", it) }
            speed?.let { put("speed", it) }
        },
        authenticated = true,
        parser = ::parseMobileVoiceRealtimeSession
    )

    suspend fun fetchApiCostsOverview(days: Int = 30): MobileApiCostsOverview = getJson(
        path = "/api/mobile/costs/overview?days=$days",
        authenticated = true,
        parser = ::parseMobileApiCostsOverview
    )

    suspend fun fetchApiCostsOverview(startDate: String, endDate: String): MobileApiCostsOverview = getJson(
        path = "/api/mobile/costs/overview?startDate=${urlEncode(startDate)}&endDate=${urlEncode(endDate)}",
        authenticated = true,
        parser = ::parseMobileApiCostsOverview
    )

    private suspend fun <T> getJson(
        path: String,
        authenticated: Boolean,
        parser: (JSONObject) -> T
    ): T = requestJson(path = path, method = "GET", body = null, authenticated = authenticated, parser = parser)

    private suspend fun <T> getJsonArray(
        path: String,
        authenticated: Boolean,
        parser: (JSONArray) -> T
    ): T = requestJsonArray(path = path, method = "GET", body = null, authenticated = authenticated, parser = parser)

    private suspend fun <T> postJson(
        path: String,
        body: JSONObject,
        authenticated: Boolean,
        parser: (JSONObject) -> T
    ): T = requestJson(path = path, method = "POST", body = body, authenticated = authenticated, parser = parser)

    private suspend fun <T> postJsonBytes(
        path: String,
        body: JSONObject,
        authenticated: Boolean,
        parser: (BinaryApiResponse) -> T
    ): T = parser(requestBytes(path = path, method = "POST", body = body, authenticated = authenticated, allowRefresh = true))

    private suspend fun <T> requestJson(
        path: String,
        method: String,
        body: JSONObject?,
        authenticated: Boolean,
        parser: (JSONObject) -> T
    ): T = parser(JSONObject(requestBody(path, method, body, authenticated).ifBlank { "{}" }))

    private suspend fun <T> requestJsonArray(
        path: String,
        method: String,
        body: JSONObject?,
        authenticated: Boolean,
        parser: (JSONArray) -> T
    ): T = parser(JSONArray(requestBody(path, method, body, authenticated).ifBlank { "[]" }))

    private suspend fun requestBody(
        path: String,
        method: String,
        body: JSONObject?,
        authenticated: Boolean
    ): String = requestBody(path, method, body, authenticated, allowRefresh = true)

    private suspend fun requestBody(
        path: String,
        method: String,
        body: JSONObject?,
        authenticated: Boolean,
        allowRefresh: Boolean
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (authenticated) {
                val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
                    ?: throw AteneaApiException(401, "No hay sesión activa.")
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val responseBody = connection.readResponseBody()
            if (connection.responseCode == 401 && authenticated && allowRefresh) {
                val refreshToken = refreshTokenProvider()?.takeIf { it.isNotBlank() }
                if (refreshToken != null) {
                    refreshSession(refreshToken)
                    return@withContext requestBody(
                        path = path,
                        method = method,
                        body = body,
                        authenticated = authenticated,
                        allowRefresh = false
                    )
                }
            }
            if (connection.responseCode !in 200..299) {
                throw buildApiException(connection.responseCode, responseBody)
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestBytes(
        path: String,
        method: String,
        body: JSONObject?,
        authenticated: Boolean,
        allowRefresh: Boolean
    ): BinaryApiResponse = withContext(Dispatchers.IO) {
        val connection = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("Accept", "audio/mpeg, audio/*")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (authenticated) {
                val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
                    ?: throw AteneaApiException(401, "No hay sesión activa.")
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            if (connection.responseCode == 401 && authenticated && allowRefresh) {
                val refreshToken = refreshTokenProvider()?.takeIf { it.isNotBlank() }
                if (refreshToken != null) {
                    refreshSession(refreshToken)
                    return@withContext requestBytes(
                        path = path,
                        method = method,
                        body = body,
                        authenticated = authenticated,
                        allowRefresh = false
                    )
                }
            }
            if (connection.responseCode !in 200..299) {
                throw buildApiException(connection.responseCode, connection.readResponseBody())
            }

            BinaryApiResponse(
                bytes = connection.inputStream.use { it.readBytes() },
                contentType = connection.contentType
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun <T> postMultipartFile(
        path: String,
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: ((MobileUploadTransferProgress) -> Unit)?,
        parser: (JSONObject) -> T
    ): T = parser(JSONObject(requestMultipartBody(path, fieldName, fileName, contentType, bytes, onProgress, allowRefresh = true)))

    private suspend fun requestMultipartBody(
        path: String,
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: ((MobileUploadTransferProgress) -> Unit)?,
        allowRefresh: Boolean
    ): String = withContext(Dispatchers.IO) {
        val boundary = "AteneaBoundary${System.currentTimeMillis()}"
        val requestStartedAt = System.nanoTime()
        fun elapsedMs(): Long = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt)
        val multipart = multipartParts(boundary, fieldName, fileName, contentType, bytes)
        onProgress?.invoke(MobileUploadTransferProgress(MobileUploadTransferPhase.PREPARING_REQUEST, 0L, multipart.totalBytes.toLong(), elapsedMs()))
        val connection = (URL("$normalizedBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doOutput = true
            setFixedLengthStreamingMode(multipart.totalBytes)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
                ?: throw AteneaApiException(401, "No hay sesión activa.")
            setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            onProgress?.invoke(MobileUploadTransferProgress(MobileUploadTransferPhase.WRITING_REQUEST, 0L, multipart.totalBytes.toLong(), elapsedMs()))
            connection.outputStream.use { output ->
                writeMultipartBody(output, multipart) { sentBytes, totalBytes ->
                    onProgress?.invoke(MobileUploadTransferProgress(MobileUploadTransferPhase.WRITING_REQUEST, sentBytes, totalBytes, elapsedMs()))
                }
            }
            onProgress?.invoke(MobileUploadTransferProgress(MobileUploadTransferPhase.WAITING_RESPONSE, multipart.totalBytes.toLong(), multipart.totalBytes.toLong(), elapsedMs()))
            val responseBody = connection.readResponseBody()
            onProgress?.invoke(MobileUploadTransferProgress(MobileUploadTransferPhase.RESPONSE_RECEIVED, multipart.totalBytes.toLong(), multipart.totalBytes.toLong(), elapsedMs()))
            if (connection.responseCode == 401 && allowRefresh) {
                val refreshToken = refreshTokenProvider()?.takeIf { it.isNotBlank() }
                if (refreshToken != null) {
                    refreshSession(refreshToken)
                    return@withContext requestMultipartBody(
                        path = path,
                        fieldName = fieldName,
                        fileName = fileName,
                        contentType = contentType,
                        bytes = bytes,
                        onProgress = onProgress,
                        allowRefresh = false
                    )
                }
            }
            if (connection.responseCode !in 200..299) {
                throw buildApiException(connection.responseCode, responseBody)
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun refreshSession(refreshToken: String) {
        try {
            val session = refresh(refreshToken)
            sessionUpdater(session)
        } catch (exception: AteneaApiException) {
            throw AteneaApiException(
                exception.status,
                "La sesión ha caducado. Vuelve a entrar en Atenea."
            )
        }
    }
}

private data class MultipartParts(
    val header: ByteArray,
    val fileBytes: ByteArray,
    val footer: ByteArray
) {
    val totalBytes: Int = header.size + fileBytes.size + footer.size
}

private fun multipartParts(
    boundary: String,
    fieldName: String,
    fileName: String,
    contentType: String,
    bytes: ByteArray
): MultipartParts {
    val lineBreak = "\r\n"
    val header = buildString {
        append("--$boundary$lineBreak")
        append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${fileName.escapeMultipart()}\"$lineBreak")
        append("Content-Type: $contentType$lineBreak$lineBreak")
    }.toByteArray(Charsets.UTF_8)
    val footer = "$lineBreak--$boundary--$lineBreak".toByteArray(Charsets.UTF_8)
    return MultipartParts(header = header, fileBytes = bytes, footer = footer)
}

private fun writeMultipartBody(
    output: OutputStream,
    parts: MultipartParts,
    onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)?
) {
    val total = parts.totalBytes.toLong()
    var sent = 0L
    fun writeChunk(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        output.write(chunk, offset, length)
        sent += length
        onProgress?.invoke(sent, total)
    }

    onProgress?.invoke(0L, total)
    writeChunk(parts.header)
    var offset = 0
    val bufferSize = 64 * 1024
    while (offset < parts.fileBytes.size) {
        val length = minOf(bufferSize, parts.fileBytes.size - offset)
        writeChunk(parts.fileBytes, offset, length)
        offset += length
    }
    writeChunk(parts.footer)
}

private fun String.escapeMultipart(): String = replace("\\", "\\\\").replace("\"", "\\\"")

data class OperatorProfile(
    val id: Long,
    val email: String,
    val displayName: String
)

data class MobileAuthSession(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val operator: OperatorProfile
)

enum class CoreScope {
    GLOBAL,
    PROJECT,
    SESSION
}

data class CoreCommandResponse(
    val commandId: Long,
    val status: String,
    val operatorMessage: String?,
    val speakableMessage: String?,
    val confirmation: CoreConfirmation?,
    val clarification: CoreClarification?
)

data class CoreConfirmation(
    val confirmationToken: String,
    val message: String?
)

data class CoreClarification(
    val message: String?,
    val options: List<CoreClarificationOption>
)

data class CoreClarificationOption(
    val type: String,
    val targetId: Long?,
    val label: String
)

data class CoreCommandSummary(
    val commandId: Long,
    val status: String,
    val rawInput: String,
    val operatorMessage: String?,
    val speakableMessage: String?,
    val resultSummary: String?,
    val errorMessage: String?,
    val createdAt: String?
) {
    val bestMessage: String?
        get() = operatorMessage ?: speakableMessage ?: resultSummary ?: errorMessage
}

data class MobileProjectOverview(
    val projectId: Long,
    val projectName: String,
    val description: String?,
    val defaultBaseBranch: String?,
    val session: MobileProjectSessionOverview?
)

data class MobileProjectSessionOverview(
    val sessionId: Long,
    val status: String,
    val title: String,
    val runInProgress: Boolean,
    val closeBlockedState: String?,
    val pullRequestStatus: String?,
    val lastActivityAt: String?
)

data class ResolveMobileWorkSessionResult(
    val created: Boolean,
    val view: MobileWorkSessionConversation
)

data class MobileWorkSessionConversation(
    val session: MobileWorkSession,
    val runInProgress: Boolean,
    val canCreateTurn: Boolean,
    val latestRun: MobileAgentRun?,
    val lastError: String?,
    val lastAgentResponse: String?,
    val recentTurns: List<MobileConversationTurn>
)

data class MobileWorkSession(
    val id: Long,
    val projectId: Long?,
    val title: String,
    val status: String,
    val operationalState: String,
    val baseBranch: String?,
    val workspaceBranch: String?,
    val pullRequestUrl: String?,
    val pullRequestStatus: String?,
    val finalCommitSha: String?,
    val openedAt: String?,
    val lastActivityAt: String?,
    val publishedAt: String?,
    val closedAt: String?,
    val closeBlockedState: String?,
    val closeBlockedReason: String?,
    val closeBlockedAction: String?,
    val closeRetryable: Boolean
)

data class MobileAgentRun(
    val id: Long,
    val status: String,
    val startedAt: String?,
    val finishedAt: String?,
    val outputSummary: String?,
    val errorSummary: String?
)

data class MobileConversationTurn(
    val id: Long,
    val actor: String,
    val messageText: String,
    val createdAt: String?
)

data class MobileSessionSummary(
    val conversation: MobileWorkSessionConversation,
    val approvedDeliverables: SessionDeliverablesView,
    val approvedPriceEstimate: ApprovedPriceEstimateSummary?,
    val actions: MobileSessionActions,
    val insights: MobileSessionInsights
)

data class MobileSessionActions(
    val canCreateTurn: Boolean,
    val canPublish: Boolean,
    val canSyncPullRequest: Boolean,
    val canClose: Boolean,
    val canGenerateDeliverables: Boolean,
    val canApproveDeliverables: Boolean,
    val canMarkApprovedPriceEstimateBilled: Boolean
)

data class MobileSessionInsights(
    val latestProgress: String?,
    val currentBlocker: MobileSessionBlocker?,
    val nextStepRecommended: String?
)

data class MobileSessionBlocker(
    val category: String?,
    val summary: String?
)

data class SessionDeliverablesView(
    val sessionId: Long,
    val deliverables: List<SessionDeliverableSummary>,
    val allCoreDeliverablesPresent: Boolean,
    val allCoreDeliverablesApproved: Boolean,
    val lastGeneratedAt: String?
)

data class SessionDeliverableSummary(
    val id: Long,
    val type: String,
    val status: String,
    val version: Int,
    val title: String,
    val approved: Boolean,
    val approvedAt: String?,
    val updatedAt: String?,
    val preview: String?,
    val latestApprovedDeliverableId: Long?
)

data class SessionDeliverable(
    val id: Long,
    val sessionId: Long,
    val type: String,
    val status: String,
    val version: Int,
    val title: String,
    val contentMarkdown: String?,
    val contentJson: String?,
    val generationNotes: String?,
    val errorMessage: String?,
    val approved: Boolean,
    val approvedAt: String?,
    val billingStatus: String?,
    val billingReference: String?,
    val billedAt: String?,
    val createdAt: String?,
    val updatedAt: String?
)

data class ApprovedPriceEstimateSummary(
    val sessionId: Long,
    val deliverableId: Long,
    val version: Int,
    val title: String,
    val currency: String?,
    val baseHourlyRate: Double,
    val equivalentHours: Double,
    val minimumPrice: Double,
    val recommendedPrice: Double,
    val maximumPrice: Double,
    val commercialPositioning: String?,
    val riskLevel: String?,
    val confidence: String?,
    val assumptions: List<String>,
    val exclusions: List<String>,
    val billingStatus: String?,
    val billingReference: String?,
    val billedAt: String?,
    val approvedAt: String?,
    val updatedAt: String?
)

data class MobileSessionEvents(
    val sessionId: Long,
    val events: List<MobileSessionEvent>,
    val generatedAt: String?
)

data class MobileSessionEvent(
    val type: String,
    val at: String?,
    val title: String,
    val details: String?,
    val runId: Long?,
    val turnId: Long?,
    val deliverableId: Long?
)

data class ResolveMobileRescueSessionResult(
    val created: Boolean,
    val view: MobileRescueConversation
)

data class MobileRescueConversation(
    val session: MobileRescueSession,
    val turns: List<MobileConversationTurn>
)

data class MobileRescueSession(
    val id: Long,
    val projectId: Long,
    val projectName: String,
    val repoPath: String?,
    val status: String,
    val title: String,
    val canCreateTurn: Boolean,
    val lastActivityAt: String?
)

data class ManagedHost(
    val id: Long,
    val name: String,
    val description: String?,
    val environment: String?,
    val active: Boolean
)

data class ManagedService(
    val id: Long,
    val hostId: Long,
    val name: String,
    val serviceType: String?,
    val systemdUnit: String?,
    val processPattern: String?,
    val active: Boolean
)

data class WebsiteCheck(
    val websiteId: Long,
    val name: String,
    val url: String,
    val expectedStatus: Int,
    val statusCode: Int?,
    val durationMillis: Long,
    val healthy: Boolean,
    val error: String?
)

data class OperationsActionRun(
    val id: Long,
    val action: String,
    val status: String,
    val exitCode: Int?,
    val stdoutSummary: String?,
    val stderrSummary: String?,
    val report: OperationsExecutionReport?,
    val startedAt: String?,
    val finishedAt: String?
)

data class OperationsExecutionReport(
    val action: String?,
    val host: String?,
    val status: String?,
    val summary: String?,
    val steps: List<OperationsExecutionStep>,
    val metrics: Map<String, String>
)

data class OperationsExecutionStep(
    val name: String?,
    val status: String?,
    val detail: String?
)

data class OperationsIncident(
    val id: Long,
    val hostId: Long?,
    val hostName: String?,
    val serviceId: Long?,
    val serviceName: String?,
    val status: String,
    val severity: String,
    val title: String,
    val summary: String?,
    val openedAt: String?,
    val lastActivityAt: String?,
    val resolvedAt: String?
)

data class OperationsHostStatus(
    val host: ManagedHost,
    val hostStatusRun: OperationsActionRun?,
    val services: List<ManagedService>,
    val websiteChecks: List<WebsiteCheck>,
    val openIncidents: List<OperationsIncident>
) {
    val healthyWebsites: Int
        get() = websiteChecks.count { it.healthy }

    val unhealthyWebsites: Int
        get() = websiteChecks.count { !it.healthy }
}

data class MobileUpload(
    val originalFilename: String,
    val storedFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val storedPath: String,
    val latestMetadataPath: String,
    val uploadedAt: String,
    val telemetry: MobileUploadTelemetry?
)

data class MobileUploadTelemetry(
    val backendTotalMs: Long,
    val backendEnsureDirectoryMs: Long,
    val backendCopyMs: Long,
    val backendPermissionsMs: Long,
    val backendMetadataMs: Long
)

enum class MobileUploadTransferPhase {
    PREPARING_REQUEST,
    WRITING_REQUEST,
    WAITING_RESPONSE,
    RESPONSE_RECEIVED
}

data class MobileUploadTransferProgress(
    val phase: MobileUploadTransferPhase,
    val sentBytes: Long,
    val totalBytes: Long,
    val elapsedMs: Long
)

enum class VoiceDomain {
    NONE,
    DEVELOPMENT,
    OPERATIONS,
    COMMUNICATIONS,
    PERSONAL
}

data class MobileVoicePlayback(
    val sourceType: String?,
    val sourceId: String?,
    val segmentIndex: Int?,
    val segmentCount: Int?
)

data class MobileVoiceFocus(
    val operatorId: Long?,
    val domain: VoiceDomain,
    val projectId: Long?,
    val projectName: String?,
    val workSessionId: Long?,
    val workSessionTitle: String?,
    val activeCommandId: Long?,
    val latestCommandId: Long?,
    val focusUpToDate: Boolean?,
    val managedHostId: Long?,
    val managedHostName: String?,
    val activity: String?,
    val playback: MobileVoicePlayback?,
    val activeNoteCount: Int,
    val updatedAt: String?
)

data class MobileVoiceNote(
    val id: Long,
    val text: String,
    val status: String,
    val focusSnapshotJson: String?,
    val consumedByCommandId: Long?,
    val capturedAt: String?,
    val consumedAt: String?,
    val updatedAt: String?
)

data class MobileVoiceNotesSendResult(
    val command: CoreCommandResponse,
    val consumedNotes: List<MobileVoiceNote>
)

data class MobileVoiceNotesState(
    val focus: MobileVoiceFocus?,
    val notes: List<MobileVoiceNote>,
    val pendingSendIntent: MobileVoiceNoteSendIntent?
)

data class MobileVoiceNoteSendIntent(
    val id: Long,
    val status: String,
    val destinationType: String,
    val projectId: Long?,
    val projectName: String?,
    val workSessionId: Long?,
    val workSessionTitle: String?,
    val noteIds: List<Long>,
    val noteCount: Int,
    val instruction: String?,
    val confirmationPrompt: String?,
    val errorMessage: String?,
    val agentRunId: Long?,
    val createdAt: String?,
    val expiresAt: String?,
    val updatedAt: String?
)

data class MobileVoiceNoteSendConfirmResult(
    val intent: MobileVoiceNoteSendIntent,
    val consumedNotes: List<MobileVoiceNote>,
    val operatorTurnId: Long?,
    val agentRunId: Long?,
    val message: String
)

data class MobileVoiceCodexStatus(
    val projectId: Long?,
    val projectName: String?,
    val workSessionId: Long?,
    val workSessionTitle: String?,
    val agentRunId: Long?,
    val runStatus: String?,
    val responseReady: Boolean,
    val failed: Boolean,
    val message: String,
    val updatedAt: String?
)

data class MobileVoiceAudio(
    val bytes: ByteArray,
    val contentType: String
)

data class MobileVoiceRealtimeSession(
    val provider: String,
    val sessionType: String,
    val model: String,
    val voice: String,
    val clientSecret: String,
    val expiresAt: Long?,
    val status: String
)

data class MobileApiCostsOverview(
    val generatedAt: String?,
    val startAt: String?,
    val endAt: String?,
    val currency: String,
    val total: Double,
    val providers: List<MobileApiCostProvider>,
    val usageSummaries: List<MobileApiUsageSummary>,
    val codexAuthStatuses: List<MobileCodexAuthStatus>
)

data class MobileApiCostProvider(
    val provider: String,
    val configured: Boolean,
    val status: String,
    val currency: String,
    val total: Double,
    val modelTotals: List<MobileApiCostModelTotal>,
    val lines: List<MobileApiCostLine>
)

data class MobileApiCostModelTotal(
    val provider: String,
    val model: String,
    val currency: String,
    val amount: Double
)

data class MobileApiCostLine(
    val label: String,
    val projectId: String?,
    val model: String?,
    val currency: String,
    val amount: Double
)

data class MobileApiUsageSummary(
    val provider: String,
    val usageType: String,
    val status: String,
    val requests: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val inputAudioTokens: Long,
    val outputAudioTokens: Long,
    val characters: Long,
    val lines: List<MobileApiUsageLine>
)

data class MobileApiUsageLine(
    val usageType: String,
    val model: String,
    val projectId: String?,
    val projectName: String?,
    val apiKeyId: String?,
    val apiKeyName: String?,
    val requests: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val inputAudioTokens: Long,
    val outputAudioTokens: Long,
    val characters: Long
)

data class MobileCodexAuthStatus(
    val server: String,
    val configured: Boolean,
    val compliant: Boolean,
    val status: String,
    val requiredAuthMode: String,
    val authMode: String?,
    val apiKeyPresent: Boolean,
    val tokensPresent: Boolean
)

private data class BinaryApiResponse(
    val bytes: ByteArray,
    val contentType: String?
)

class AteneaApiException(
    val status: Int,
    override val message: String
) : RuntimeException(message)

private fun parseMobileAuthSession(json: JSONObject): MobileAuthSession {
    val operator = json.getJSONObject("operator")
    return MobileAuthSession(
        accessToken = json.getString("accessToken"),
        accessTokenExpiresAt = json.getString("accessTokenExpiresAt"),
        refreshToken = json.getString("refreshToken"),
        refreshTokenExpiresAt = json.getString("refreshTokenExpiresAt"),
        operator = OperatorProfile(
            id = operator.getLong("id"),
            email = operator.getString("email"),
            displayName = operator.getString("displayName")
        )
    )
}

private fun parseCoreCommandResponse(json: JSONObject): CoreCommandResponse {
    val confirmation = json.optJSONObject("confirmation")
    val clarification = json.optJSONObject("clarification")
    return CoreCommandResponse(
        commandId = json.getLong("commandId"),
        status = json.getString("status"),
        operatorMessage = json.optNullableString("operatorMessage"),
        speakableMessage = json.optNullableString("speakableMessage"),
        confirmation = confirmation?.let {
            CoreConfirmation(
                confirmationToken = it.getString("confirmationToken"),
                message = it.optNullableString("message")
            )
        },
        clarification = clarification?.let {
            CoreClarification(
                message = it.optNullableString("message"),
                options = parseClarificationOptions(it.optJSONArray("options") ?: JSONArray())
            )
        }
    )
}

private fun parseClarificationOptions(items: JSONArray): List<CoreClarificationOption> =
    List(items.length()) { index ->
        val item = items.getJSONObject(index)
        CoreClarificationOption(
            type = item.getString("type"),
            targetId = if (item.has("targetId") && !item.isNull("targetId")) item.getLong("targetId") else null,
            label = item.optString("label", "")
        )
    }

private fun parseCoreCommandSummary(json: JSONObject): CoreCommandSummary = CoreCommandSummary(
    commandId = json.getLong("commandId"),
    status = json.getString("status"),
    rawInput = json.optString("rawInput", ""),
    operatorMessage = json.optNullableString("operatorMessage"),
    speakableMessage = json.optNullableString("speakableMessage"),
    resultSummary = json.optNullableString("resultSummary"),
    errorMessage = json.optNullableString("errorMessage"),
    createdAt = json.optNullableString("createdAt")
)

private fun parseMobileProjectOverview(json: JSONObject): MobileProjectOverview =
    MobileProjectOverview(
        projectId = json.getLong("projectId"),
        projectName = json.optString("projectName", ""),
        description = json.optNullableString("description"),
        defaultBaseBranch = json.optNullableString("defaultBaseBranch"),
        session = json.optJSONObject("session")?.let {
            MobileProjectSessionOverview(
                sessionId = it.getLong("sessionId"),
                status = it.optString("status", ""),
                title = it.optString("title", ""),
                runInProgress = it.optBoolean("runInProgress", false),
                closeBlockedState = it.optNullableString("closeBlockedState"),
                pullRequestStatus = it.optNullableString("pullRequestStatus"),
                lastActivityAt = it.optNullableString("lastActivityAt")
            )
        }
    )

private fun parseResolveMobileWorkSessionResult(json: JSONObject): ResolveMobileWorkSessionResult =
    ResolveMobileWorkSessionResult(
        created = json.optBoolean("created", false),
        view = parseMobileWorkSessionConversation(json.getJSONObject("view"))
    )

private fun parseMobileWorkSessionConversation(json: JSONObject): MobileWorkSessionConversation {
    val view = json.optJSONObject("view") ?: json
    val session = view.getJSONObject("session")
    val latestRun = view.optJSONObject("latestRun")
    val turns = json.optJSONArray("recentTurns") ?: view.optJSONArray("recentTurns") ?: JSONArray()
    return MobileWorkSessionConversation(
        session = parseMobileWorkSession(session),
        runInProgress = view.optBoolean("runInProgress", false),
        canCreateTurn = view.optBoolean("canCreateTurn", false),
        latestRun = latestRun?.let {
            MobileAgentRun(
                id = it.getLong("id"),
                status = it.optString("status", ""),
                startedAt = it.optNullableString("startedAt"),
                finishedAt = it.optNullableString("finishedAt"),
                outputSummary = it.optNullableString("outputSummary"),
                errorSummary = it.optNullableString("errorSummary")
            )
        },
        lastError = view.optNullableString("lastError"),
        lastAgentResponse = view.optNullableString("lastAgentResponse"),
        recentTurns = List(turns.length()) { index -> parseMobileConversationTurn(turns.getJSONObject(index)) }
    )
}

private fun parseMobileWorkSession(json: JSONObject): MobileWorkSession =
    MobileWorkSession(
        id = json.getLong("id"),
        projectId = json.optNullableLong("projectId"),
        title = json.optString("title", ""),
        status = json.optString("status", ""),
        operationalState = json.optString("operationalState", ""),
        baseBranch = json.optNullableString("baseBranch"),
        workspaceBranch = json.optNullableString("workspaceBranch"),
        pullRequestUrl = json.optNullableString("pullRequestUrl"),
        pullRequestStatus = json.optNullableString("pullRequestStatus"),
        finalCommitSha = json.optNullableString("finalCommitSha"),
        openedAt = json.optNullableString("openedAt"),
        lastActivityAt = json.optNullableString("lastActivityAt"),
        publishedAt = json.optNullableString("publishedAt"),
        closedAt = json.optNullableString("closedAt"),
        closeBlockedState = json.optNullableString("closeBlockedState"),
        closeBlockedReason = json.optNullableString("closeBlockedReason"),
        closeBlockedAction = json.optNullableString("closeBlockedAction"),
        closeRetryable = json.optBoolean("closeRetryable", false)
    )

private fun parseMobileSessionSummary(json: JSONObject): MobileSessionSummary =
    MobileSessionSummary(
        conversation = parseMobileWorkSessionConversation(json.getJSONObject("conversation")),
        approvedDeliverables = parseSessionDeliverablesView(
            json.optJSONObject("approvedDeliverables") ?: JSONObject()
        ),
        approvedPriceEstimate = json.optJSONObject("approvedPriceEstimate")?.let(::parseApprovedPriceEstimateSummary),
        actions = parseMobileSessionActions(json.optJSONObject("actions") ?: JSONObject()),
        insights = parseMobileSessionInsights(json.optJSONObject("insights") ?: JSONObject())
    )

private fun parseMobileSessionActions(json: JSONObject): MobileSessionActions =
    MobileSessionActions(
        canCreateTurn = json.optBoolean("canCreateTurn", false),
        canPublish = json.optBoolean("canPublish", false),
        canSyncPullRequest = json.optBoolean("canSyncPullRequest", false),
        canClose = json.optBoolean("canClose", false),
        canGenerateDeliverables = json.optBoolean("canGenerateDeliverables", false),
        canApproveDeliverables = json.optBoolean("canApproveDeliverables", false),
        canMarkApprovedPriceEstimateBilled = json.optBoolean("canMarkApprovedPriceEstimateBilled", false)
    )

private fun parseMobileSessionInsights(json: JSONObject): MobileSessionInsights =
    MobileSessionInsights(
        latestProgress = json.optNullableString("latestProgress"),
        currentBlocker = json.optJSONObject("currentBlocker")?.let {
            MobileSessionBlocker(
                category = it.optNullableString("category"),
                summary = it.optNullableString("summary")
            )
        },
        nextStepRecommended = json.optNullableString("nextStepRecommended")
    )

private fun parseSessionDeliverablesView(json: JSONObject): SessionDeliverablesView {
    val deliverables = json.optJSONArray("deliverables") ?: JSONArray()
    return SessionDeliverablesView(
        sessionId = json.optLong("sessionId", 0L),
        deliverables = List(deliverables.length()) { index ->
            parseSessionDeliverableSummary(deliverables.getJSONObject(index))
        },
        allCoreDeliverablesPresent = json.optBoolean("allCoreDeliverablesPresent", false),
        allCoreDeliverablesApproved = json.optBoolean("allCoreDeliverablesApproved", false),
        lastGeneratedAt = json.optNullableString("lastGeneratedAt")
    )
}

private fun parseSessionDeliverableSummary(json: JSONObject): SessionDeliverableSummary =
    SessionDeliverableSummary(
        id = json.getLong("id"),
        type = json.optString("type", ""),
        status = json.optString("status", ""),
        version = json.optInt("version", 0),
        title = json.optString("title", ""),
        approved = json.optBoolean("approved", false),
        approvedAt = json.optNullableString("approvedAt"),
        updatedAt = json.optNullableString("updatedAt"),
        preview = json.optNullableString("preview"),
        latestApprovedDeliverableId = json.optNullableLong("latestApprovedDeliverableId")
    )

private fun parseSessionDeliverable(json: JSONObject): SessionDeliverable =
    SessionDeliverable(
        id = json.getLong("id"),
        sessionId = json.getLong("sessionId"),
        type = json.optString("type", ""),
        status = json.optString("status", ""),
        version = json.optInt("version", 0),
        title = json.optString("title", ""),
        contentMarkdown = json.optNullableString("contentMarkdown"),
        contentJson = json.optNullableString("contentJson"),
        generationNotes = json.optNullableString("generationNotes"),
        errorMessage = json.optNullableString("errorMessage"),
        approved = json.optBoolean("approved", false),
        approvedAt = json.optNullableString("approvedAt"),
        billingStatus = json.optNullableString("billingStatus"),
        billingReference = json.optNullableString("billingReference"),
        billedAt = json.optNullableString("billedAt"),
        createdAt = json.optNullableString("createdAt"),
        updatedAt = json.optNullableString("updatedAt")
    )

private fun parseApprovedPriceEstimateSummary(json: JSONObject): ApprovedPriceEstimateSummary =
    ApprovedPriceEstimateSummary(
        sessionId = json.getLong("sessionId"),
        deliverableId = json.getLong("deliverableId"),
        version = json.optInt("version", 0),
        title = json.optString("title", ""),
        currency = json.optNullableString("currency"),
        baseHourlyRate = json.optDouble("baseHourlyRate", 0.0),
        equivalentHours = json.optDouble("equivalentHours", 0.0),
        minimumPrice = json.optDouble("minimumPrice", 0.0),
        recommendedPrice = json.optDouble("recommendedPrice", 0.0),
        maximumPrice = json.optDouble("maximumPrice", 0.0),
        commercialPositioning = json.optNullableString("commercialPositioning"),
        riskLevel = json.optNullableString("riskLevel"),
        confidence = json.optNullableString("confidence"),
        assumptions = json.optJSONArray("assumptions").toStringList(),
        exclusions = json.optJSONArray("exclusions").toStringList(),
        billingStatus = json.optNullableString("billingStatus"),
        billingReference = json.optNullableString("billingReference"),
        billedAt = json.optNullableString("billedAt"),
        approvedAt = json.optNullableString("approvedAt"),
        updatedAt = json.optNullableString("updatedAt")
    )

private fun parseMobileSessionEvents(json: JSONObject): MobileSessionEvents {
    val events = json.optJSONArray("events") ?: JSONArray()
    return MobileSessionEvents(
        sessionId = json.optLong("sessionId", 0L),
        events = List(events.length()) { index ->
            val item = events.getJSONObject(index)
            MobileSessionEvent(
                type = item.optString("type", ""),
                at = item.optNullableString("at"),
                title = item.optString("title", ""),
                details = item.optNullableString("details"),
                runId = item.optNullableLong("runId"),
                turnId = item.optNullableLong("turnId"),
                deliverableId = item.optNullableLong("deliverableId")
            )
        },
        generatedAt = json.optNullableString("generatedAt")
    )
}

private fun parseResolveMobileRescueSessionResult(json: JSONObject): ResolveMobileRescueSessionResult =
    ResolveMobileRescueSessionResult(
        created = json.optBoolean("created", false),
        view = parseMobileRescueConversation(json.getJSONObject("view"))
    )

private fun parseMobileRescueConversation(json: JSONObject): MobileRescueConversation {
    val session = json.getJSONObject("session")
    val turns = json.optJSONArray("turns") ?: JSONArray()
    return MobileRescueConversation(
        session = MobileRescueSession(
            id = session.getLong("id"),
            projectId = session.getLong("projectId"),
            projectName = session.optString("projectName", ""),
            repoPath = session.optNullableString("repoPath"),
            status = session.optString("status", ""),
            title = session.optString("title", ""),
            canCreateTurn = session.optBoolean("canCreateTurn", false),
            lastActivityAt = session.optNullableString("lastActivityAt")
        ),
        turns = List(turns.length()) { index -> parseMobileConversationTurn(turns.getJSONObject(index)) }
    )
}

private fun parseMobileConversationTurn(json: JSONObject): MobileConversationTurn =
    MobileConversationTurn(
        id = json.getLong("id"),
        actor = json.optString("actor", ""),
        messageText = json.optString("messageText", ""),
        createdAt = json.optNullableString("createdAt")
    )

private fun parseManagedHost(json: JSONObject): ManagedHost = ManagedHost(
    id = json.getLong("id"),
    name = json.getString("name"),
    description = json.optNullableString("description"),
    environment = json.optNullableString("environment"),
    active = json.optBoolean("active")
)

private fun parseManagedService(json: JSONObject): ManagedService = ManagedService(
    id = json.getLong("id"),
    hostId = json.getLong("hostId"),
    name = json.getString("name"),
    serviceType = json.optNullableString("serviceType"),
    systemdUnit = json.optNullableString("systemdUnit"),
    processPattern = json.optNullableString("processPattern"),
    active = json.optBoolean("active")
)

private fun parseWebsiteCheck(json: JSONObject): WebsiteCheck = WebsiteCheck(
    websiteId = json.getLong("websiteId"),
    name = json.getString("name"),
    url = json.getString("url"),
    expectedStatus = json.getInt("expectedStatus"),
    statusCode = json.optNullableInt("statusCode"),
    durationMillis = json.optLong("durationMillis"),
    healthy = json.optBoolean("healthy"),
    error = json.optNullableString("error")
)

private fun parseOperationsActionRun(json: JSONObject): OperationsActionRun = OperationsActionRun(
    id = json.getLong("id"),
    action = json.optString("action", ""),
    status = json.optString("status", ""),
    exitCode = json.optNullableInt("exitCode"),
    stdoutSummary = json.optNullableString("stdoutSummary"),
    stderrSummary = json.optNullableString("stderrSummary"),
    report = json.optJSONObject("report")?.let(::parseOperationsExecutionReport),
    startedAt = json.optNullableString("startedAt"),
    finishedAt = json.optNullableString("finishedAt")
)

private fun parseOperationsExecutionReport(json: JSONObject): OperationsExecutionReport {
    val steps = json.optJSONArray("steps") ?: JSONArray()
    val metrics = json.optJSONObject("metrics") ?: JSONObject()
    return OperationsExecutionReport(
        action = json.optNullableString("action"),
        host = json.optNullableString("host"),
        status = json.optNullableString("status"),
        summary = json.optNullableString("summary"),
        steps = List(steps.length()) { index ->
            parseOperationsExecutionStep(steps.getJSONObject(index))
        },
        metrics = metrics.toDisplayMap()
    )
}

private fun parseOperationsExecutionStep(json: JSONObject): OperationsExecutionStep = OperationsExecutionStep(
    name = json.optNullableString("name"),
    status = json.optNullableString("status"),
    detail = json.optNullableString("detail")
)

private fun parseOperationsIncident(json: JSONObject): OperationsIncident = OperationsIncident(
    id = json.getLong("id"),
    hostId = json.optNullableLong("hostId"),
    hostName = json.optNullableString("hostName"),
    serviceId = json.optNullableLong("serviceId"),
    serviceName = json.optNullableString("serviceName"),
    status = json.optString("status", ""),
    severity = json.optString("severity", ""),
    title = json.optString("title", ""),
    summary = json.optNullableString("summary"),
    openedAt = json.optNullableString("openedAt"),
    lastActivityAt = json.optNullableString("lastActivityAt"),
    resolvedAt = json.optNullableString("resolvedAt")
)

private fun parseOperationsHostStatus(json: JSONObject): OperationsHostStatus {
    val services = json.optJSONArray("services") ?: JSONArray()
    val websiteChecks = json.optJSONArray("websiteChecks") ?: JSONArray()
    val openIncidents = json.optJSONArray("openIncidents") ?: JSONArray()
    return OperationsHostStatus(
        host = parseManagedHost(json.getJSONObject("host")),
        hostStatusRun = json.optJSONObject("hostStatusRun")?.let(::parseOperationsActionRun),
        services = List(services.length()) { index ->
            parseManagedService(services.getJSONObject(index))
        },
        websiteChecks = List(websiteChecks.length()) { index ->
            parseWebsiteCheck(websiteChecks.getJSONObject(index))
        },
        openIncidents = List(openIncidents.length()) { index ->
            parseOperationsIncident(openIncidents.getJSONObject(index))
        }
    )
}

private fun parseMobileUpload(json: JSONObject): MobileUpload = MobileUpload(
    originalFilename = json.optString("originalFilename", ""),
    storedFilename = json.optString("storedFilename", ""),
    contentType = json.optString("contentType", ""),
    sizeBytes = json.optLong("sizeBytes"),
    storedPath = json.optString("storedPath", ""),
    latestMetadataPath = json.optString("latestMetadataPath", ""),
    uploadedAt = json.optString("uploadedAt", ""),
    telemetry = json.optJSONObject("telemetry")?.let {
        MobileUploadTelemetry(
            backendTotalMs = it.optLong("backendTotalMs", 0L),
            backendEnsureDirectoryMs = it.optLong("backendEnsureDirectoryMs", 0L),
            backendCopyMs = it.optLong("backendCopyMs", 0L),
            backendPermissionsMs = it.optLong("backendPermissionsMs", 0L),
            backendMetadataMs = it.optLong("backendMetadataMs", 0L)
        )
    }
)

private fun parseMobileVoiceFocus(json: JSONObject): MobileVoiceFocus {
    val playback = json.optJSONObject("playback")
    return MobileVoiceFocus(
        operatorId = json.optNullableLong("operatorId"),
        domain = runCatching { VoiceDomain.valueOf(json.optString("domain", "NONE")) }.getOrDefault(VoiceDomain.NONE),
        projectId = json.optNullableLong("projectId"),
        projectName = json.optNullableString("projectName"),
        workSessionId = json.optNullableLong("workSessionId"),
        workSessionTitle = json.optNullableString("workSessionTitle"),
        activeCommandId = json.optNullableLong("activeCommandId"),
        latestCommandId = json.optNullableLong("latestCommandId"),
        focusUpToDate = json.optNullableBoolean("focusUpToDate"),
        managedHostId = json.optNullableLong("managedHostId"),
        managedHostName = json.optNullableString("managedHostName"),
        activity = json.optNullableString("activity"),
        playback = playback?.let {
            MobileVoicePlayback(
                sourceType = it.optNullableString("sourceType"),
                sourceId = it.optNullableString("sourceId"),
                segmentIndex = it.optNullableInt("segmentIndex"),
                segmentCount = it.optNullableInt("segmentCount")
            )
        },
        activeNoteCount = json.optInt("activeNoteCount", 0),
        updatedAt = json.optNullableString("updatedAt")
    )
}

private fun parseMobileVoiceNote(json: JSONObject): MobileVoiceNote = MobileVoiceNote(
    id = json.getLong("id"),
    text = json.optString("text", ""),
    status = json.optString("status", ""),
    focusSnapshotJson = json.optNullableString("focusSnapshotJson"),
    consumedByCommandId = json.optNullableLong("consumedByCommandId"),
    capturedAt = json.optNullableString("capturedAt"),
    consumedAt = json.optNullableString("consumedAt"),
    updatedAt = json.optNullableString("updatedAt")
)

private fun parseMobileVoiceNotesSendResult(json: JSONObject): MobileVoiceNotesSendResult {
    val notes = json.optJSONArray("consumedNotes") ?: JSONArray()
    return MobileVoiceNotesSendResult(
        command = parseCoreCommandResponse(json.getJSONObject("command")),
        consumedNotes = List(notes.length()) { index -> parseMobileVoiceNote(notes.getJSONObject(index)) }
    )
}

private fun parseMobileVoiceNotesState(json: JSONObject): MobileVoiceNotesState {
    val notes = json.optJSONArray("notes") ?: JSONArray()
    return MobileVoiceNotesState(
        focus = json.optJSONObject("focus")?.let(::parseMobileVoiceFocus),
        notes = List(notes.length()) { index -> parseMobileVoiceNote(notes.getJSONObject(index)) },
        pendingSendIntent = json.optJSONObject("pendingSendIntent")?.let(::parseMobileVoiceNoteSendIntent)
    )
}

private fun parseMobileVoiceNoteSendIntent(json: JSONObject): MobileVoiceNoteSendIntent {
    val noteIds = json.optJSONArray("noteIds") ?: JSONArray()
    return MobileVoiceNoteSendIntent(
        id = json.getLong("id"),
        status = json.optString("status", ""),
        destinationType = json.optString("destinationType", ""),
        projectId = json.optNullableLong("projectId"),
        projectName = json.optNullableString("projectName"),
        workSessionId = json.optNullableLong("workSessionId"),
        workSessionTitle = json.optNullableString("workSessionTitle"),
        noteIds = List(noteIds.length()) { index -> noteIds.optLong(index) },
        noteCount = json.optInt("noteCount", noteIds.length()),
        instruction = json.optNullableString("instruction"),
        confirmationPrompt = json.optNullableString("confirmationPrompt"),
        errorMessage = json.optNullableString("errorMessage"),
        agentRunId = json.optNullableLong("agentRunId"),
        createdAt = json.optNullableString("createdAt"),
        expiresAt = json.optNullableString("expiresAt"),
        updatedAt = json.optNullableString("updatedAt")
    )
}

private fun parseMobileVoiceNoteSendConfirmResult(json: JSONObject): MobileVoiceNoteSendConfirmResult {
    val notes = json.optJSONArray("consumedNotes") ?: JSONArray()
    return MobileVoiceNoteSendConfirmResult(
        intent = parseMobileVoiceNoteSendIntent(json.getJSONObject("intent")),
        consumedNotes = List(notes.length()) { index -> parseMobileVoiceNote(notes.getJSONObject(index)) },
        operatorTurnId = json.optNullableLong("operatorTurnId"),
        agentRunId = json.optNullableLong("agentRunId"),
        message = json.optString("message", "")
    )
}

private fun parseMobileVoiceCodexStatus(json: JSONObject): MobileVoiceCodexStatus =
    MobileVoiceCodexStatus(
        projectId = json.optNullableLong("projectId"),
        projectName = json.optNullableString("projectName"),
        workSessionId = json.optNullableLong("workSessionId"),
        workSessionTitle = json.optNullableString("workSessionTitle"),
        agentRunId = json.optNullableLong("agentRunId"),
        runStatus = json.optNullableString("runStatus"),
        responseReady = json.optBoolean("responseReady", false),
        failed = json.optBoolean("failed", false),
        message = json.optString("message", ""),
        updatedAt = json.optNullableString("updatedAt")
    )

private fun parseMobileVoiceRealtimeSession(json: JSONObject): MobileVoiceRealtimeSession =
    MobileVoiceRealtimeSession(
        provider = json.optString("provider", ""),
        sessionType = json.optString("sessionType", ""),
        model = json.optString("model", ""),
        voice = json.optString("voice", ""),
        clientSecret = json.optString("clientSecret", ""),
        expiresAt = json.optNullableLong("expiresAt"),
        status = json.optString("status", "")
    )

private fun parseMobileApiCostsOverview(json: JSONObject): MobileApiCostsOverview {
    val providers = json.optJSONArray("providers") ?: JSONArray()
    val usageSummaries = json.optJSONArray("usageSummaries") ?: JSONArray()
    val codexAuthStatuses = json.optJSONArray("codexAuthStatuses") ?: JSONArray()
    return MobileApiCostsOverview(
        generatedAt = json.optNullableString("generatedAt"),
        startAt = json.optNullableString("startAt"),
        endAt = json.optNullableString("endAt"),
        currency = json.optString("currency", "usd"),
        total = json.optDouble("total", 0.0),
        providers = List(providers.length()) { index ->
            parseMobileApiCostProvider(providers.getJSONObject(index))
        },
        usageSummaries = List(usageSummaries.length()) { index ->
            parseMobileApiUsageSummary(usageSummaries.getJSONObject(index))
        },
        codexAuthStatuses = List(codexAuthStatuses.length()) { index ->
            val status = codexAuthStatuses.getJSONObject(index)
            MobileCodexAuthStatus(
                server = status.optString("server", ""),
                configured = status.optBoolean("configured", false),
                compliant = status.optBoolean("compliant", false),
                status = status.optString("status", ""),
                requiredAuthMode = status.optString("requiredAuthMode", ""),
                authMode = status.optNullableString("authMode"),
                apiKeyPresent = status.optBoolean("apiKeyPresent", false),
                tokensPresent = status.optBoolean("tokensPresent", false)
            )
        }
    )
}

private fun parseMobileApiUsageSummary(json: JSONObject): MobileApiUsageSummary {
    val lines = json.optJSONArray("lines") ?: JSONArray()
    return MobileApiUsageSummary(
        provider = json.optString("provider", ""),
        usageType = json.optString("usageType", ""),
        status = json.optString("status", ""),
        requests = json.optLong("requests", 0L),
        inputTokens = json.optLong("inputTokens", 0L),
        cachedInputTokens = json.optLong("cachedInputTokens", 0L),
        outputTokens = json.optLong("outputTokens", 0L),
        inputAudioTokens = json.optLong("inputAudioTokens", 0L),
        outputAudioTokens = json.optLong("outputAudioTokens", 0L),
        characters = json.optLong("characters", 0L),
        lines = List(lines.length()) { index ->
            val line = lines.getJSONObject(index)
            MobileApiUsageLine(
                usageType = line.optString("usageType", json.optString("usageType", "")),
                model = line.optString("model", "Sin modelo"),
                projectId = line.optNullableString("projectId"),
                projectName = line.optNullableString("projectName"),
                apiKeyId = line.optNullableString("apiKeyId"),
                apiKeyName = line.optNullableString("apiKeyName"),
                requests = line.optLong("requests", 0L),
                inputTokens = line.optLong("inputTokens", 0L),
                cachedInputTokens = line.optLong("cachedInputTokens", 0L),
                outputTokens = line.optLong("outputTokens", 0L),
                inputAudioTokens = line.optLong("inputAudioTokens", 0L),
                outputAudioTokens = line.optLong("outputAudioTokens", 0L),
                characters = line.optLong("characters", 0L)
            )
        }
    )
}

private fun parseMobileApiCostProvider(json: JSONObject): MobileApiCostProvider {
    val lines = json.optJSONArray("lines") ?: JSONArray()
    val modelTotals = json.optJSONArray("modelTotals") ?: JSONArray()
    return MobileApiCostProvider(
        provider = json.optString("provider", ""),
        configured = json.optBoolean("configured", false),
        status = json.optString("status", ""),
        currency = json.optString("currency", "usd"),
        total = json.optDouble("total", 0.0),
        modelTotals = List(modelTotals.length()) { index ->
            val model = modelTotals.getJSONObject(index)
            MobileApiCostModelTotal(
                provider = model.optString("provider", json.optString("provider", "")),
                model = model.optString("model", "Sin modelo"),
                currency = model.optString("currency", json.optString("currency", "usd")),
                amount = model.optDouble("amount", 0.0)
            )
        },
        lines = List(lines.length()) { index ->
            val line = lines.getJSONObject(index)
            MobileApiCostLine(
                label = line.optString("label", ""),
                projectId = line.optNullableString("projectId"),
                model = line.optNullableString("model"),
                currency = line.optString("currency", json.optString("currency", "usd")),
                amount = line.optDouble("amount", 0.0)
            )
        }
    )
}

private fun HttpURLConnection.readResponseBody(): String {
    val stream = if (responseCode in 200..299) inputStream else errorStream
    if (stream == null) {
        return ""
    }
    return stream.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}

private fun buildApiException(status: Int, responseBody: String): AteneaApiException {
    if (responseBody.isBlank()) {
        return AteneaApiException(status, "Atenea ha devuelto HTTP $status.")
    }

    return try {
        val json = JSONObject(responseBody)
        AteneaApiException(
            status = status,
            message = json.optNullableString("message")
                ?.toUserFacingApiMessage()
                ?: json.optNullableString("detail")
                    ?.toUserFacingApiMessage()
                ?: json.optNullableString("reason")
                    ?.toUserFacingApiMessage()
                ?: "Atenea ha devuelto HTTP $status."
        )
    } catch (_: Exception) {
        AteneaApiException(status, responseBody)
    }
}

private fun String.toUserFacingApiMessage(): String =
    when {
        contains("could not determine a supported development intent", ignoreCase = true) ->
            "No he podido interpretar esa orden en el foco actual."
        else -> this
    }

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key) && !isNull(key)) getBoolean(key) else null

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        List(length()) { index -> optString(index).takeIf { it.isNotBlank() }.orEmpty() }
            .filter { it.isNotBlank() }
    }

private fun JSONObject.toDisplayMap(): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (!isNull(key)) {
            result[key] = get(key).toString()
        }
    }
    return result
}
