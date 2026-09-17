package com.atenea.android.coreconsole

import com.atenea.android.api.NewDevelopmentChangeRequestKeys
import com.atenea.android.api.NewDevelopmentChangeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class NewDevelopmentChangeUiState(
    val projectId: Long? = null,
    val title: String = "",
    val visible: Boolean = false,
    val submitting: Boolean = false,
    val attemptStarted: Boolean = false,
    val error: String? = null
) {
    val canSubmit: Boolean
        get() = !submitting && title.isNotBlank() && title.trim().length <= 200
}

internal data class NewDevelopmentChangeNavigation(
    val projectId: Long,
    val sessionId: Long
)

internal class NewDevelopmentChangeViewModel(
    private val startChange: suspend (
        projectId: Long,
        title: String,
        keys: NewDevelopmentChangeRequestKeys
    ) -> NewDevelopmentChangeResult,
    private val keysFactory: () -> NewDevelopmentChangeRequestKeys =
        NewDevelopmentChangeRequestKeys::create
) {
    private val mutableState = MutableStateFlow(NewDevelopmentChangeUiState())
    val state: StateFlow<NewDevelopmentChangeUiState> = mutableState

    private var attempt: Attempt? = null

    fun open(projectId: Long) {
        val currentAttempt = attempt
        mutableState.value = if (currentAttempt?.projectId == projectId) {
            mutableState.value.copy(visible = true)
        } else {
            attempt = null
            NewDevelopmentChangeUiState(projectId = projectId, visible = true)
        }
    }

    fun updateTitle(value: String) {
        if (!mutableState.value.submitting && attempt == null) {
            mutableState.value = mutableState.value.copy(title = value, error = null)
        }
    }

    fun dismiss() {
        if (!mutableState.value.submitting) {
            mutableState.value = mutableState.value.copy(visible = false)
        }
    }

    suspend fun submit(): NewDevelopmentChangeNavigation? {
        val projectId = mutableState.value.projectId ?: return null
        val normalizedTitle = mutableState.value.title.trim()
        if (normalizedTitle.isBlank()) {
            mutableState.value = mutableState.value.copy(error = "Indica un titulo para el nuevo cambio.")
            return null
        }
        if (normalizedTitle.length > 200) {
            mutableState.value = mutableState.value.copy(error = "El titulo no puede superar 200 caracteres.")
            return null
        }
        val currentAttempt = attempt ?: Attempt(
            projectId = projectId,
            title = normalizedTitle,
            keys = keysFactory()
        ).also { attempt = it }
        mutableState.value = mutableState.value.copy(
            title = currentAttempt.title,
            submitting = true,
            attemptStarted = true,
            error = null
        )
        return try {
            val result = startChange(
                currentAttempt.projectId,
                currentAttempt.title,
                currentAttempt.keys
            )
            val navigation = NewDevelopmentChangeNavigation(
                projectId = currentAttempt.projectId,
                sessionId = result.sessionId
            )
            attempt = null
            mutableState.value = NewDevelopmentChangeUiState()
            navigation
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                submitting = false,
                error = error.message ?: "No se pudo iniciar el nuevo cambio."
            )
            null
        }
    }

    private data class Attempt(
        val projectId: Long,
        val title: String,
        val keys: NewDevelopmentChangeRequestKeys
    )
}
