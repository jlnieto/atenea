package com.atenea.android.voiceruntime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Debug
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

object AteneaDiagnostics {
    private const val MAX_EVENT_FILES = 8
    private const val MAX_EVENT_BYTES = 256_000L
    private const val MAX_REPORT_EVENTS = 220
    private const val MAX_PROCESS_EXIT_REASONS = 8
    private const val MAX_EXIT_TRACE_BYTES = 64 * 1024
    private const val MAX_EXIT_TRACE_CHARS = 64_000
    private val lock = Any()
    private var appContext: Context? = null
    private var versionName: String = "unknown"
    private var versionCode: Int = 0
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context, versionName: String, versionCode: Int) {
        synchronized(lock) {
            appContext = context.applicationContext
            this.versionName = versionName
            this.versionCode = versionCode
            diagnosticsDir().mkdirs()
        }
        info("diagnostics", "initialized")
    }

    fun installCrashHandler(context: Context, versionName: String, versionCode: Int) {
        initialize(context, versionName, versionCode)
        synchronized(lock) {
            if (previousHandler != null) {
                return
            }
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeCrash(thread, throwable)
                previousHandler?.uncaughtException(thread, throwable)
                    ?: kotlin.system.exitProcess(2)
            }
        }
    }

    fun info(area: String, event: String, details: Map<String, Any?> = emptyMap()) {
        writeEvent("INFO", area, event, details)
    }

    fun warn(area: String, event: String, details: Map<String, Any?> = emptyMap()) {
        writeEvent("WARN", area, event, details)
    }

    fun error(area: String, event: String, throwable: Throwable? = null, details: Map<String, Any?> = emptyMap()) {
        val enriched = details.toMutableMap()
        if (throwable != null) {
            enriched["exception"] = throwable.javaClass.name
            enriched["message"] = throwable.message
            enriched["stacktrace"] = throwable.stackTraceToString().take(12_000)
        }
        writeEvent("ERROR", area, event, enriched)
    }

    fun createReport(reason: String = "manual"): DiagnosticReport {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val context = synchronized(lock) { appContext }
        val payload = JSONObject()
            .put("generatedAt", now)
            .put("reason", reason)
            .put("app", appJson())
            .put("device", deviceJson())
            .put("runtime", runtimeJson(context))
            .put("lastCrash", readLastCrash())
            .put("processExits", JSONArray(readProcessExits()))
            .put("events", JSONArray(readRecentEvents()))
        val bytes = payload.toString(2).toByteArray(Charsets.UTF_8)
        val name = "atenea-diagnostics-${now.replace(':', '-')}.json"
        return DiagnosticReport(
            fileName = name,
            contentType = "application/json",
            bytes = bytes
        )
    }

    fun runtimeSnapshot(context: Context): RuntimeDiagnosticsSnapshot {
        val app = context.applicationContext
        val runtime = Runtime.getRuntime()
        val heapTotal = runtime.totalMemory()
        val heapFree = runtime.freeMemory()
        val heapUsed = heapTotal - heapFree
        val manager = app.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memoryInfo)
        return RuntimeDiagnosticsSnapshot(
            generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            versionName = synchronized(lock) { versionName },
            versionCode = synchronized(lock) { versionCode },
            heapMaxBytes = runtime.maxMemory(),
            heapTotalBytes = heapTotal,
            heapFreeBytes = heapFree,
            heapUsedBytes = heapUsed,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            nativeHeapSizeBytes = Debug.getNativeHeapSize(),
            nativeHeapFreeBytes = Debug.getNativeHeapFreeSize(),
            systemAvailableBytes = memoryInfo.availMem,
            systemTotalBytes = memoryInfo.totalMem,
            systemThresholdBytes = memoryInfo.threshold,
            systemLowMemory = memoryInfo.lowMemory,
            memoryClassMb = manager?.memoryClass ?: 0,
            largeMemoryClassMb = manager?.largeMemoryClass ?: 0,
            activeThreads = Thread.activeCount(),
            availableProcessors = runtime.availableProcessors(),
            processCpuTimeMs = android.os.Process.getElapsedCpuTime(),
            deviceUptimeMs = SystemClock.elapsedRealtime(),
            cacheUsableBytes = app.cacheDir.usableSpace,
            filesUsableBytes = app.filesDir.usableSpace
        )
    }

    fun lastCrashSnapshot(context: Context): LastCrashSnapshot? {
        val file = File(diagnosticsDir(context.applicationContext), "last-crash.json")
        if (!file.isFile) {
            return null
        }
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            LastCrashSnapshot(
                time = json.optString("time").ifBlank { "sin fecha" },
                thread = json.optString("thread").ifBlank { "sin hilo" },
                exception = json.optString("exception").ifBlank { "sin excepcion" },
                message = json.optString("message").ifBlank { null }
            )
        }.getOrNull()
    }

    fun latestProcessExitSnapshot(context: Context): ProcessExitSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        val app = context.applicationContext
        val manager = app.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(app.packageName, 0, 1)
                .firstOrNull()
                ?.let { exit ->
                    ProcessExitSnapshot(
                        timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(exit.timestamp)),
                        reason = exitReasonLabel(exit.reason),
                        status = exit.status,
                        importance = exit.importance,
                        pssBytes = exit.pss,
                        rssBytes = exit.rss,
                        description = exit.description
                    )
                }
        }.getOrNull()
    }

    private fun writeEvent(level: String, area: String, event: String, details: Map<String, Any?>) {
        synchronized(lock) {
            val context = appContext ?: return
            val dir = diagnosticsDir(context).apply { mkdirs() }
            val target = File(dir, "events.jsonl")
            val json = JSONObject()
                .put("time", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .put("level", level)
                .put("area", area)
                .put("event", event)
                .put("app", appJson())
                .put("device", deviceJson())
                .put("details", JSONObject(details.filterValues { it != null }))
            target.appendText(json.toString() + "\n", Charsets.UTF_8)
            rotateIfNeeded(target)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        synchronized(lock) {
            val context = appContext ?: return
            val crash = JSONObject()
                .put("time", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .put("thread", thread.name)
                .put("exception", throwable.javaClass.name)
                .put("message", throwable.message)
                .put("stacktrace", throwable.stackTraceToString().take(24_000))
                .put("app", appJson())
                .put("device", deviceJson())
            diagnosticsDir(context).mkdirs()
            File(diagnosticsDir(context), "last-crash.json").writeText(crash.toString(2), Charsets.UTF_8)
            writeEvent("ERROR", "app", "uncaught_exception", mapOf(
                "thread" to thread.name,
                "exception" to throwable.javaClass.name,
                "message" to throwable.message
            ))
        }
    }

    private fun readLastCrash(): Any {
        val context = synchronized(lock) { appContext } ?: return JSONObject.NULL
        val file = File(diagnosticsDir(context), "last-crash.json")
        if (!file.isFile) {
            return JSONObject.NULL
        }
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse {
            JSONObject().put("unreadable", true).put("message", it.message)
        }
    }

    private fun readProcessExits(): List<JSONObject> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        val context = synchronized(lock) { appContext } ?: return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_PROCESS_EXIT_REASONS
            ).map { exit ->
                JSONObject()
                    .put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(exit.timestamp)))
                    .put("reason", exit.reason)
                    .put("reasonLabel", exitReasonLabel(exit.reason))
                    .put("status", exit.status)
                    .put("importance", exit.importance)
                    .put("processName", exit.processName)
                    .put("description", exit.description ?: JSONObject.NULL)
                    .put("pssBytes", exit.pss)
                    .put("rssBytes", exit.rss)
                    .put("trace", readExitTrace(exit))
            }
        }.getOrElse { error ->
            listOf(
                JSONObject()
                    .put("unreadable", true)
                    .put("message", error.message)
            )
        }
    }

    private fun readExitTrace(exit: ApplicationExitInfo): Any {
        val stream = runCatching { exit.traceInputStream }.getOrNull() ?: return JSONObject.NULL
        return runCatching {
            stream.use { input ->
                String(readAtMost(input, MAX_EXIT_TRACE_BYTES), Charsets.UTF_8)
                    .take(MAX_EXIT_TRACE_CHARS)
            }
        }.getOrElse { error ->
            JSONObject()
                .put("unreadable", true)
                .put("message", error.message)
        }
    }

    private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = ArrayList<Byte>(maxBytes)
        while (output.size < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size))
            if (read <= 0) {
                break
            }
            for (index in 0 until read) {
                output.add(buffer[index])
            }
        }
        return output.toByteArray()
    }

    private fun exitReasonLabel(reason: Int): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (reason) {
                ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
                ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                ApplicationExitInfo.REASON_SIGNALED -> "signaled"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
                ApplicationExitInfo.REASON_OTHER -> "other"
                ApplicationExitInfo.REASON_FREEZER -> "freezer"
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
                ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
                else -> "reason_$reason"
            }
        } else {
            "unsupported"
        }

    private fun readRecentEvents(): List<JSONObject> {
        val context = synchronized(lock) { appContext } ?: return emptyList()
        val dir = diagnosticsDir(context)
        val files = listOf(File(dir, "events.jsonl")) +
            (1..MAX_EVENT_FILES).map { File(dir, "events.$it.jsonl") }
        return files
            .filter { it.isFile }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).mapNotNull { line ->
                    runCatching { JSONObject(line) }.getOrNull()
                }
            }
            .takeLast(MAX_REPORT_EVENTS)
    }

    private fun rotateIfNeeded(target: File) {
        if (target.length() <= MAX_EVENT_BYTES) {
            return
        }
        for (index in MAX_EVENT_FILES downTo 1) {
            val source = if (index == 1) target else File(target.parentFile, "events.${index - 1}.jsonl")
            val destination = File(target.parentFile, "events.$index.jsonl")
            if (source.exists()) {
                source.renameTo(destination)
            }
        }
    }

    private fun appJson(): JSONObject =
        JSONObject()
            .put("versionName", versionName)
            .put("versionCode", versionCode)

    private fun deviceJson(): JSONObject =
        JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)

    private fun runtimeJson(context: Context?): JSONObject {
        if (context == null) {
            return JSONObject().put("unavailable", true)
        }
        val snapshot = runtimeSnapshot(context)
        return JSONObject()
            .put("generatedAt", snapshot.generatedAt)
            .put("heapMaxBytes", snapshot.heapMaxBytes)
            .put("heapTotalBytes", snapshot.heapTotalBytes)
            .put("heapFreeBytes", snapshot.heapFreeBytes)
            .put("heapUsedBytes", snapshot.heapUsedBytes)
            .put("nativeHeapAllocatedBytes", snapshot.nativeHeapAllocatedBytes)
            .put("nativeHeapSizeBytes", snapshot.nativeHeapSizeBytes)
            .put("nativeHeapFreeBytes", snapshot.nativeHeapFreeBytes)
            .put("systemAvailableBytes", snapshot.systemAvailableBytes)
            .put("systemTotalBytes", snapshot.systemTotalBytes)
            .put("systemThresholdBytes", snapshot.systemThresholdBytes)
            .put("systemLowMemory", snapshot.systemLowMemory)
            .put("memoryClassMb", snapshot.memoryClassMb)
            .put("largeMemoryClassMb", snapshot.largeMemoryClassMb)
            .put("activeThreads", snapshot.activeThreads)
            .put("availableProcessors", snapshot.availableProcessors)
            .put("processCpuTimeMs", snapshot.processCpuTimeMs)
            .put("deviceUptimeMs", snapshot.deviceUptimeMs)
            .put("cacheUsableBytes", snapshot.cacheUsableBytes)
            .put("filesUsableBytes", snapshot.filesUsableBytes)
    }

    private fun diagnosticsDir(): File =
        diagnosticsDir(appContext ?: throw IllegalStateException("Diagnostics not initialized"))

    private fun diagnosticsDir(context: Context): File =
        File(context.filesDir, "diagnostics")
}

data class DiagnosticReport(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray
)

data class RuntimeDiagnosticsSnapshot(
    val generatedAt: String,
    val versionName: String,
    val versionCode: Int,
    val heapMaxBytes: Long,
    val heapTotalBytes: Long,
    val heapFreeBytes: Long,
    val heapUsedBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val nativeHeapSizeBytes: Long,
    val nativeHeapFreeBytes: Long,
    val systemAvailableBytes: Long,
    val systemTotalBytes: Long,
    val systemThresholdBytes: Long,
    val systemLowMemory: Boolean,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val activeThreads: Int,
    val availableProcessors: Int,
    val processCpuTimeMs: Long,
    val deviceUptimeMs: Long,
    val cacheUsableBytes: Long,
    val filesUsableBytes: Long
) {
    val heapUsageRatio: Float
        get() = if (heapMaxBytes <= 0L) 0f else (heapUsedBytes.toFloat() / heapMaxBytes.toFloat()).coerceIn(0f, 1f)

    val systemUsageRatio: Float
        get() = if (systemTotalBytes <= 0L) 0f else (1f - systemAvailableBytes.toFloat() / systemTotalBytes.toFloat()).coerceIn(0f, 1f)
}

data class LastCrashSnapshot(
    val time: String,
    val thread: String,
    val exception: String,
    val message: String?
)

data class ProcessExitSnapshot(
    val timestamp: String,
    val reason: String,
    val status: Int,
    val importance: Int,
    val pssBytes: Long,
    val rssBytes: Long,
    val description: String?
)
