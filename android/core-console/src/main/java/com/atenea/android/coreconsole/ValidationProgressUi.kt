package com.atenea.android.coreconsole

import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import java.time.Duration
import java.time.Instant

internal enum class ValidationProgressStatus { PENDING, PASSED, QUEUED, RUNNING, CONFIRMING, STOPPED }

internal data class ValidationProgressStep(val operation: String, val label: String,
    val status: ValidationProgressStatus, val statusLabel: String, val timing: String?)

internal data class ValidationProgressUi(val revision: Long, val passed: Int, val steps: List<ValidationProgressStep>,
    val totalTime: String?, val confirmation: String?, val uncertain: Boolean)

private val validationSteps = linkedMapOf(
    "BACKEND_TEST" to "Pruebas de backend",
    "WEB_BUILD" to "Compilación web",
    "ANDROID_BUILD" to "Compilación Android",
    "PLAYWRIGHT_ACCEPTANCE" to "Prueba del flujo web"
)

/** Presentation only: counts and phases come from the same durable revision. */
internal fun validationProgressUi(evidence: DevelopmentChangeValidationEvidence?, sessionId: Long, now: Instant,
    validationRequested: Boolean, observationConfirmed: Boolean): ValidationProgressUi? {
    evidence ?: return null
    if (evidence.workSessionId != sessionId || evidence.sourceFingerprintSha256 == null
        || evidence.requiredOperations != 4 || evidence.operations.map { it.operation }.distinct().size != evidence.operations.size
        || evidence.operations.any { it.workSessionId != sessionId || it.sourceTreeFingerprintSha256 != evidence.sourceFingerprintSha256
            || it.operation !in validationSteps || it.status !in setOf("RUNNING", "SUCCEEDED", "FAILED", "BLOCKED") }
        || evidence.passedOperations != evidence.operations.count { it.status == "SUCCEEDED" }) return null

    val attempts = evidence.operations.associateBy { it.operation }
    val active = evidence.operations.filter { it.status == "RUNNING" }
    val observations = active.map { progressInstant(it.workerObservedAt) }
    val observed = if (observations.all { it != null }) observations.filterNotNull().minOrNull() else null
    val fresh = active.isNotEmpty() && active.all { confirmedRecently(it, now, observationConfirmed) }
    val steps = validationSteps.map { (kind, label) ->
        val attempt = attempts[kind]
        val phaseFresh = attempt != null && evidence.workflow?.running != false && evidence.workflow?.errorCode == null
            && confirmedRecently(attempt, now, observationConfirmed)
        val (status, text) = when (attempt?.status) {
            null -> ValidationProgressStatus.PENDING to "Pendiente"
            "SUCCEEDED" -> ValidationProgressStatus.PASSED to "Correcta"
            "FAILED" -> ValidationProgressStatus.STOPPED to "Fallida"
            "BLOCKED" -> ValidationProgressStatus.STOPPED to "Bloqueada"
            else -> when {
                !phaseFresh -> ValidationProgressStatus.CONFIRMING to when (attempt.workerState) {
                    "QUEUED" -> "Último estado: en cola"
                    "RUNNING" -> "Último estado: en ejecución"
                    else -> "Fase por confirmar"
                }
                attempt.workerState == "QUEUED" -> ValidationProgressStatus.QUEUED to "En cola"
                attempt.workerState == "RUNNING" -> ValidationProgressStatus.RUNNING to "En ejecución"
                attempt.workerState == "CANCELLING" -> ValidationProgressStatus.CONFIRMING to "Cancelación en curso"
                attempt.workerState == "RECONCILING" -> ValidationProgressStatus.CONFIRMING to "Comprobando resultado"
                else -> ValidationProgressStatus.CONFIRMING to "Fase por confirmar"
            }
        }
        ValidationProgressStep(kind, label, status, text, attempt?.let { timing(it, now, phaseFresh) })
    }
    val first = progressInstant(evidence.workflow?.startedAt)
        ?: evidence.operations.mapNotNull { progressInstant(it.startedAt) }.minOrNull()
    val end = progressInstant(evidence.workflow?.finishedAt) ?: if (active.isNotEmpty() || validationRequested || evidence.workflow?.running == true) now
        else evidence.operations.mapNotNull { progressInstant(it.finishedAt) }.maxOrNull()
    val total = if (first != null && end != null) "Tiempo desde la primera solicitud: ${validationElapsed(first, end)}" else null
    val confirmation = when {
        !observationConfirmed -> "No se pudo actualizar el estado. Estos son los últimos resultados guardados; no se ha repetido ninguna prueba."
        evidence.workflow?.errorCode == "VALIDATION_WORKER_UNCONFIRMED" -> "Esperando confirmación del worker. Se conserva la misma operación; no se ha duplicado ninguna prueba."
        evidence.workflow?.errorCode == "VALIDATION_SOURCE_UNCONFIRMED" -> "Esperando confirmar el código en el worker. Todavía no se admite otra prueba."
        evidence.workflow?.running == false && evidence.workflow?.state != "SUCCEEDED" ->
            "La validación del servidor está detenida. Los resultados se conservan; no se inicia ninguna prueba adicional."
        active.isEmpty() && evidence.workflow?.running == true -> "El servidor está preparando la siguiente comprobación. Puedes salir de Atenea."
        active.isEmpty() -> null
        observed == null -> "El worker todavía no ha confirmado su fase. No se presupone que esté ejecutando pruebas."
        observed.isAfter(now.plusSeconds(5)) -> "La hora de confirmación no coincide con la del dispositivo. La fase está por confirmar."
        fresh -> "Última confirmación del worker: hace ${validationElapsed(observed, now)}."
        else -> "Sin confirmación reciente del worker (última: hace ${validationElapsed(observed, now)}). Esto no demuestra un fallo; no repitas la validación."
    }
    return ValidationProgressUi(evidence.sourceRevision, evidence.passedOperations, steps, total, confirmation,
        !observationConfirmed || evidence.workflow?.errorCode != null || active.isNotEmpty() && !fresh)
}

private fun confirmedRecently(attempt: DevelopmentChangeValidationAttempt, now: Instant, observationConfirmed: Boolean): Boolean {
    val observed = progressInstant(attempt.workerObservedAt) ?: return false
    return observationConfirmed && !observed.isAfter(now.plusSeconds(5)) && Duration.between(observed, now).seconds <= 30
}

private fun timing(attempt: DevelopmentChangeValidationAttempt, now: Instant, fresh: Boolean): String? {
    if (attempt.status != "RUNNING") {
        attempt.durationMillis?.let { return "Duración: ${validationDuration(it / 1000)}" }
        val start = progressInstant(attempt.startedAt) ?: return null
        val finish = progressInstant(attempt.finishedAt) ?: return null
        return "Tiempo registrado: ${validationElapsed(start, finish)}"
    }
    if (fresh && attempt.workerState == "RUNNING") progressInstant(attempt.workerStartedAt)?.let {
        if (!it.isAfter(now)) return "En ejecución desde hace ${validationElapsed(it, now)}"
    }
    return progressInstant(attempt.startedAt)?.let { "Desde la solicitud: ${validationElapsed(it, now)}" }
}

private fun progressInstant(value: String?): Instant? = try { value?.let(Instant::parse) } catch (_: Exception) { null }

private fun validationElapsed(start: Instant, end: Instant) = validationDuration(Duration.between(start, end).seconds)

internal fun validationDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return when {
        safe >= 3600 -> "${safe / 3600} h ${(safe % 3600) / 60} min"
        safe >= 60 -> "${safe / 60} min ${safe % 60} s"
        else -> "$safe s"
    }
}
