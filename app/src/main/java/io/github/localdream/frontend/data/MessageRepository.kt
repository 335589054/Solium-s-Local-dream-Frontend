package io.github.localdream.frontend.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot user-facing messages shown as a banner at the top of the screen,
 * so errors and hints are visible no matter which tab is open.
 */
class MessageRepository {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun show(text: String) {
        _message.value = text
    }

    fun dismiss() {
        _message.value = null
    }
}