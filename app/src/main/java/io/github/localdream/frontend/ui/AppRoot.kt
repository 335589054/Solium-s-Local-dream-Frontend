package io.github.localdream.frontend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.localdream.frontend.AppContainer
import io.github.localdream.frontend.data.AppSettings
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.ui.screens.ConnectScreen
import io.github.localdream.frontend.ui.screens.HistoryScreen
import io.github.localdream.frontend.ui.screens.ModelPanelScreen
import io.github.localdream.frontend.ui.screens.SettingsScreen
import io.github.localdream.frontend.ui.screens.StudioScreen
import io.github.localdream.frontend.ui.theme.LocalDreamTheme
import kotlinx.coroutines.delay

private enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    STUDIO("studio", "创作", Icons.Filled.Brush),
    MODELS("models", "模型", Icons.Filled.Memory),
    HISTORY("history", "历史", Icons.Filled.Collections),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

private const val ROUTE_CONNECT = "connect"
private const val EXPANDED_WIDTH_DP = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val systemDark = isSystemInDarkTheme()
    val darkTheme = if (settings.followSystemTheme) systemDark else settings.darkTheme

    LocalDreamTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
        val studio: StudioViewModel = viewModel(
            factory = StudioViewModel.factory(
                connection = container.connection,
                log = container.log,
                message = container.message,
                promptLibrary = container.promptLibrary,
                modelPresets = container.modelPresets,
                history = container.history,
                settings = container.settings,
            ),
        )

        LaunchedEffect(Unit) {
            if (settings.autoConnect && container.connection.state.value.phase == ConnPhase.DISCONNECTED) {
                container.connection.connect(settings.host, settings.controlPort)
            }
        }

        // Telemetry polling follows the master switch no matter which screen is
        // on top.
        LaunchedEffect(settings.monitorEnabled) {
            if (settings.monitorEnabled) container.deviceMonitor.start() else container.deviceMonitor.stop()
        }
        DisposableEffect(Unit) {
            onDispose { container.deviceMonitor.stop() }
        }

        // The WebUI bridge follows the settings switch (and follows a port
        // change too, restarting on the new port).
        LaunchedEffect(settings.webUiEnabled, settings.webUiPort) {
            if (settings.webUiEnabled) {
                container.webUiServer.start(settings.webUiPort)
            } else {
                container.webUiServer.stop()
            }
        }
        DisposableEffect(Unit) {
            onDispose { container.webUiServer.stop() }
        }

        val deviceStats by container.deviceMonitor.stats.collectAsStateWithLifecycle()
        val banner by container.message.message.collectAsStateWithLifecycle()
        val connectionState by container.connection.state.collectAsStateWithLifecycle()

        // Banners are informational; clear them automatically after a while.
        LaunchedEffect(banner) {
            if (banner != null) {
                delay(BANNER_MS)
                container.message.dismiss()
            }
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= EXPANDED_WIDTH_DP.dp

            val navigate: (AppTab) -> Unit = { tab ->
                if (currentRoute != tab.route) {
                    navController.navigate(tab.route) {
                        popUpTo(AppTab.STUDIO.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = { Text("LocalDream 控制台") },
                            colors = TopAppBarDefaults.topAppBarColors(),
                            actions = {
                                ConnectionChip(
                                    state = connectionState,
                                    onClick = { navController.navigate(ROUTE_CONNECT) },
                                )
                            },
                        )
                        Banner(banner) { container.message.dismiss() }
                    }
                },
                bottomBar = {
                    if (!expanded) {
                        NavigationBar {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { navigate(tab) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (expanded) {
                        NavigationRail {
                            AppTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { navigate(tab) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = AppTab.STUDIO.route,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            composable(AppTab.STUDIO.route) {
                                StudioScreen(
                                    studio = studio,
                                    settings = settings,
                                    stats = deviceStats,
                                    expanded = expanded,
                                )
                            }
                            composable(AppTab.MODELS.route) {
                                ModelPanelScreen(studio)
                            }
                            composable(AppTab.HISTORY.route) {
                                HistoryScreen(
                                    studio = studio,
                                    history = container.history,
                                    onOpenStudio = { navigate(AppTab.STUDIO) },
                                )
                            }
                            composable(AppTab.SETTINGS.route) {
                                SettingsScreen(container, settings, studio)
                            }
                            composable(ROUTE_CONNECT) {
                                ConnectScreen(
                                    container = container,
                                    settings = settings,
                                    onConnected = { navController.popBackStack() },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Attention banner shown right under the app bar. */
@Composable
private fun Banner(text: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

private const val BANNER_MS = 4_000L