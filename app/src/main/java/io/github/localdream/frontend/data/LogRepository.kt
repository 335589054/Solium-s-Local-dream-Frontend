package io.github.localdream.frontend.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel(val label: String, val priority: Int) {
    DEBUG("DEBUG", android.util.Log.DEBUG),
    INFO("INFO", android.util.Log.INFO),
    WARN("WARN", android.util.Log.WARN),
    ERROR("ERROR", android.util.Log.ERROR),
}

data class LogEntry(
    val id: Long,
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun formattedTime(): String = timeFormat.format(Date(time))

    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}

/**
 * Front-end self-maintained operation log. The backend's native logs are not
 * exposed over the control protocol, so this records everything *this app*
 * does (connections, model switches, generation requests, errors) in one
 * place for the user to inspect.
 */
class LogRepository(private val maxEntries: Int = 1000) {
    private val counter = AtomicLong(0)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        // Mirror to logcat so the same trail is observable over adb.
        android.util.Log.println(level.priority, "LocalDream/$tag", message)

        val entry = LogEntry(
            id = counter.incrementAndGet(),
            time = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        _entries.update { current ->
            val next = current + entry
            if (next.size > maxEntries) next.subList(next.size - maxEntries, next.size) else next
        }
    }

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun exportText(): String = entries.value.joinToString("\n") { entry ->
        "${entry.formattedTime()}  ${entry.level.label.padEnd(5)}  [${entry.tag}]  ${entry.message}"
    }
}