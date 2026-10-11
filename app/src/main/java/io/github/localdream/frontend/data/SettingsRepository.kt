package io.github.localdream.frontend.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val host: String = "127.0.0.1",
    val controlPort: Int = 8808,
    val autoConnect: Boolean = true,
    val dynamicColor: Boolean = true,
    val followSystemTheme: Boolean = true,
    val darkTheme: Boolean = true,
    val showDiffusionProcess: Boolean = true,
    val showDiffusionStride: Int = 4,
    // Whether the temperature bar is pinned above the studio content.
    val monitorEnabled: Boolean = true,
    // Run every finished generation through the host's upscaler before showing
    // it.
    val autoUpscale: Boolean = false,
    // Publish this device on the LAN as an A1111-compatible WebUI server.
    val webUiEnabled: Boolean = false,
    val webUiPort: Int = 7860,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            host = prefs[KEY_HOST] ?: "127.0.0.1",
            controlPort = prefs[KEY_PORT] ?: 8808,
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            followSystemTheme = prefs[KEY_FOLLOW_SYSTEM] ?: true,
            darkTheme = prefs[KEY_DARK_THEME] ?: true,
            showDiffusionProcess = prefs[KEY_SHOW_PROCESS] ?: true,
            showDiffusionStride = prefs[KEY_SHOW_STRIDE] ?: 4,
            monitorEnabled = prefs[KEY_MONITOR_ENABLED] ?: true,
            autoUpscale = prefs[KEY_AUTO_UPSCALE] ?: false,
            webUiEnabled = prefs[KEY_WEBUI_ENABLED] ?: false,
            webUiPort = prefs[KEY_WEBUI_PORT] ?: 7860,
        )
    }

    suspend fun setHost(host: String) = edit { it[KEY_HOST] = host.trim() }

    suspend fun setControlPort(port: Int) = edit { it[KEY_PORT] = port }

    suspend fun setAutoConnect(enabled: Boolean) = edit { it[KEY_AUTO_CONNECT] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setFollowSystemTheme(follow: Boolean) = edit { it[KEY_FOLLOW_SYSTEM] = follow }

    suspend fun setDarkTheme(dark: Boolean) = edit { it[KEY_DARK_THEME] = dark }

    suspend fun setShowDiffusionProcess(enabled: Boolean) = edit { it[KEY_SHOW_PROCESS] = enabled }

    suspend fun setShowDiffusionStride(stride: Int) = edit { it[KEY_SHOW_STRIDE] = stride.coerceIn(1, 50) }

    suspend fun setMonitorEnabled(enabled: Boolean) = edit { it[KEY_MONITOR_ENABLED] = enabled }

    suspend fun setAutoUpscale(enabled: Boolean) = edit { it[KEY_AUTO_UPSCALE] = enabled }

    suspend fun setWebUiEnabled(enabled: Boolean) = edit { it[KEY_WEBUI_ENABLED] = enabled }

    suspend fun setWebUiPort(port: Int) = edit { it[KEY_WEBUI_PORT] = port.coerceIn(1024, 65535) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("control_port")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_theme")
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        val KEY_SHOW_PROCESS = booleanPreferencesKey("show_diffusion_process")
        val KEY_SHOW_STRIDE = intPreferencesKey("show_diffusion_stride")
        val KEY_MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val KEY_AUTO_UPSCALE = booleanPreferencesKey("auto_upscale")
        val KEY_WEBUI_ENABLED = booleanPreferencesKey("webui_enabled")
        val KEY_WEBUI_PORT = intPreferencesKey("webui_port")
    }
}