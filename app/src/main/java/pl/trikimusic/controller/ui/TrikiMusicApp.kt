package pl.trikimusic.controller.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.trikimusic.controller.ui.screen.AboutScreen
import pl.trikimusic.controller.ui.screen.BleInspectorScreen
import pl.trikimusic.controller.ui.screen.CalibrationScreen
import pl.trikimusic.controller.ui.screen.DeviceScreen
import pl.trikimusic.controller.ui.screen.GestureTrainerScreen
import pl.trikimusic.controller.ui.screen.GestureWizardScreen
import pl.trikimusic.controller.ui.screen.GesturesScreen
import pl.trikimusic.controller.ui.screen.HomeScreen
import pl.trikimusic.controller.ui.screen.OnboardingScreen
import pl.trikimusic.controller.ui.screen.PermissionsScreen
import pl.trikimusic.controller.ui.screen.SensorMonitorScreen
import pl.trikimusic.controller.ui.screen.SettingsScreen

private enum class MainDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    GESTURES("gestures", "Gestures", Icons.Default.Gesture),
    DEVICE("device", "Device", Icons.Default.Bluetooth),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

object Routes {
    const val CALIBRATION = "calibration"
    const val TRAINER = "trainer"
    const val GESTURE_WIZARD = "gesture-wizard"
    const val SENSOR = "sensor"
    const val INSPECTOR = "inspector"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
}

@Composable
fun TrikiMusicApp(
    state: MainUiState,
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
) {
    if (!state.settings.onboardingComplete) {
        OnboardingScreen(onComplete = viewModel::completeOnboarding)
        return
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val mainRoutes = MainDestination.entries.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in mainRoutes

    LaunchedEffect(state.settings.calibration.isValid, state.settings.gestureWizardCompleted) {
        if (
            state.settings.calibration.isValid &&
            !state.settings.gestureWizardCompleted &&
            currentDestination?.route != Routes.GESTURE_WIZARD
        ) {
            navController.navigate(Routes.GESTURE_WIZARD) { launchSingleTop = true }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    MainDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(MainDestination.HOME.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.HOME.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
            },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(240))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(240))
            },
        ) {
            composable(MainDestination.HOME.route) {
                HomeScreen(
                    state = state,
                    contentPadding = padding,
                    onMediaAction = viewModel::performMediaAction,
                    onOpenDevice = { navController.navigate(MainDestination.DEVICE.route) },
                    onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                )
            }
            composable(MainDestination.GESTURES.route) {
                GesturesScreen(
                    state = state,
                    contentPadding = padding,
                    viewModel = viewModel,
                    onOpenTrainer = { navController.navigate(Routes.TRAINER) },
                    onOpenWizard = { navController.navigate(Routes.GESTURE_WIZARD) },
                )
            }
            composable(MainDestination.DEVICE.route) {
                DeviceScreen(
                    state = state,
                    contentPadding = padding,
                    viewModel = viewModel,
                    onCalibration = { navController.navigate(Routes.CALIBRATION) },
                    onSensor = { navController.navigate(Routes.SENSOR) },
                    onInspector = { navController.navigate(Routes.INSPECTOR) },
                    onPermissions = { navController.navigate(Routes.PERMISSIONS) },
                )
            }
            composable(MainDestination.SETTINGS.route) {
                SettingsScreen(
                    state = state,
                    contentPadding = padding,
                    viewModel = viewModel,
                    onPermissions = { navController.navigate(Routes.PERMISSIONS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onSensor = { navController.navigate(Routes.SENSOR) },
                    onInspector = { navController.navigate(Routes.INSPECTOR) },
                )
            }
            composable(Routes.CALIBRATION) {
                CalibrationScreen(state, viewModel, onBack = navController::popBackStack)
            }
            composable(Routes.TRAINER) {
                GestureTrainerScreen(state, viewModel, onBack = navController::popBackStack)
            }
            composable(Routes.GESTURE_WIZARD) {
                GestureWizardScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onFinished = {
                        if (!navController.popBackStack()) {
                            navController.navigate(MainDestination.GESTURES.route) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.SENSOR) {
                SensorMonitorScreen(state, onBack = navController::popBackStack, viewModel = viewModel)
            }
            composable(Routes.INSPECTOR) {
                BleInspectorScreen(state, viewModel, onBack = navController::popBackStack)
            }
            composable(Routes.PERMISSIONS) {
                PermissionsScreen(state, viewModel, onBack = navController::popBackStack)
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = navController::popBackStack)
            }
        }
    }
}
