package io.github.weiyongzenqi.unuplayer.ui.mediaserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerUrlValidation
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import io.github.weiyongzenqi.unuplayer.mediaserver.validateMediaServerBaseUrl

internal data class AddMediaServerConnectionSubmission(
    val vendor: MediaServerVendor,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val allowCleartext: Boolean,
) {
    override fun toString(): String =
        "AddMediaServerConnectionSubmission(vendor=$vendor, name=$name, baseUrl=<redacted>, " +
            "username=$username, password=<redacted>, allowCleartext=$allowCleartext)"
}

internal class AddMediaServerConnectionState(
    private val vendor: MediaServerVendor,
) {
    var name by mutableStateOf("")
    var baseUrl by mutableStateOf("https://")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isSubmitting by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var awaitingCleartextConfirmation by mutableStateOf(false)
        private set

    val urlValidation: MediaServerUrlValidation
        get() = validateMediaServerBaseUrl(baseUrl, vendor)

    val canSubmit: Boolean
        get() = !isSubmitting && name.isNotBlank() && username.isNotBlank() && urlValidation.isValid

    fun requestSubmit(): AddMediaServerConnectionSubmission? {
        val validation = urlValidation
        val normalizedUrl = validation.normalizedApiBaseUrl ?: return null
        if (name.isBlank() || username.isBlank() || isSubmitting) return null
        errorMessage = null
        return if (validation.requiresCleartextConfirmation) {
            awaitingCleartextConfirmation = true
            null
        } else {
            submission(normalizedUrl, allowCleartext = false)
        }
    }

    fun confirmCleartext(): AddMediaServerConnectionSubmission? {
        if (!awaitingCleartextConfirmation || isSubmitting) return null
        val normalizedUrl = urlValidation.normalizedApiBaseUrl ?: return null
        awaitingCleartextConfirmation = false
        return submission(normalizedUrl, allowCleartext = true)
    }

    fun returnToForm() {
        awaitingCleartextConfirmation = false
    }

    private fun submission(baseUrl: String, allowCleartext: Boolean) =
        AddMediaServerConnectionSubmission(
            vendor = vendor,
            name = name.trim(),
            baseUrl = baseUrl,
            username = username.trim(),
            password = password,
            allowCleartext = allowCleartext,
        )
}
