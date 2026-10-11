package io.github.localdream.frontend

import android.app.Application
import android.content.Context
import io.github.localdream.frontend.data.ConnectionRepository
import io.github.localdream.frontend.data.DeviceMonitor
import io.github.localdream.frontend.data.HistoryRepository
import io.github.localdream.frontend.data.LogRepository
import io.github.localdream.frontend.data.MessageRepository
import io.github.localdream.frontend.data.ModelPresetRepository
import io.github.localdream.frontend.data.PromptLibraryRepository
import io.github.localdream.frontend.data.SettingsRepository
import io.github.localdream.frontend.data.ShellAccess
import io.github.localdream.frontend.webui.WebUiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the app-wide singletons. Repositories are deliberately long-lived:
 * they survive configuration changes and are shared by every screen.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val log = LogRepository()
    val message = MessageRepository()
    val settings = SettingsRepository(appContext)
    val promptLibrary = PromptLibraryRepository(appContext, log, scope)
    val modelPresets = ModelPresetRepository(appContext, log, scope)
    val connection = ConnectionRepository(log, scope)
    val deviceMonitor = DeviceMonitor(scope, log)
    val history = HistoryRepository(appContext, log, scope)

    /** Publishes this device as an A1111-compatible WebUI server on the LAN. */
    val webUiServer = WebUiServer(connection, promptLibrary, log, scope)

    init {
        ShellAccess.attach(appContext)
    }
}

class LocalDreamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}