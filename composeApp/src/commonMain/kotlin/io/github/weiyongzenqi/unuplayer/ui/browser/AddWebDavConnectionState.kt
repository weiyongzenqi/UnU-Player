package io.github.weiyongzenqi.unuplayer.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.webdav.WebDavUrlValidation
import io.github.weiyongzenqi.unuplayer.webdav.validateWebDavBaseUrl

internal data class AddWebDavConnectionSubmission(
    val connection: WebDavConnection,
    val allowCleartext: Boolean,
)

/** 添加连接表单的提交状态；Compose 只负责渲染和转发动作。 */
internal class AddWebDavConnectionState(
    private val connectionId: String,
) {
    var name by mutableStateOf("")
    var baseUrl by mutableStateOf("https://")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    var pendingCleartextConnection by mutableStateOf<WebDavConnection?>(null)
        private set

    val urlValidation: WebDavUrlValidation
        get() = validateWebDavBaseUrl(baseUrl)

    val canSubmit: Boolean
        get() = name.isNotBlank() && urlValidation.isValid

    /** HTTPS 直接返回提交；HTTP 只进入风险确认状态。 */
    fun requestSubmit(): AddWebDavConnectionSubmission? {
        val validation = urlValidation
        val normalizedUrl = validation.normalizedUrl ?: return null
        if (name.isBlank()) return null
        val connection = WebDavConnection(
            id = connectionId,
            name = name.trim(),
            baseUrl = normalizedUrl,
            username = username.trim(),
            password = password,
        )
        return if (validation.requiresCleartextConfirmation) {
            pendingCleartextConnection = connection
            null
        } else {
            AddWebDavConnectionSubmission(connection, allowCleartext = false)
        }
    }

    fun confirmCleartext(): AddWebDavConnectionSubmission? {
        val connection = pendingCleartextConnection ?: return null
        pendingCleartextConnection = null
        return AddWebDavConnectionSubmission(connection, allowCleartext = true)
    }

    fun returnToForm() {
        pendingCleartextConnection = null
    }
}
