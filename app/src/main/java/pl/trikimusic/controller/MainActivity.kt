package pl.trikimusic.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.TrikiMusicApp
import pl.trikimusic.controller.ui.theme.TrikiMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TrikiMusicApplication
        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(application, app.container))
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemState()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(state.userMessage) {
                val message = state.userMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(message)
                viewModel.dismissMessage()
            }

            TrikiMusicTheme(state.settings.theme) {
                TrikiMusicApp(state, viewModel, snackbarHostState)
            }
        }
    }
}
