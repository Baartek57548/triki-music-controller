package pl.trikimusic.controller

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import pl.trikimusic.controller.core.bluetooth.TrikiBleManager
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.core.permissions.PermissionManager
import pl.trikimusic.controller.data.bluetooth.FakeTrikiDataSource
import pl.trikimusic.controller.data.media.AndroidMediaControllerGateway
import pl.trikimusic.controller.data.repository.DataStoreSettingsRepository
import pl.trikimusic.controller.data.update.GitHubUpdateManager
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.repository.SettingsRepository
import pl.trikimusic.controller.domain.usecase.ActionMapper
import pl.trikimusic.controller.runtime.TrikiRuntime

class AppContainer(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val logger = AppLogger()
    val permissionManager = PermissionManager(application)
    val updateManager = GitHubUpdateManager(application, logger)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(application, logger)
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
    val bleManager = TrikiBleManager(application, scope, permissionManager, logger)
    val mediaController = AndroidMediaControllerGateway(application, logger)
    val actionMapper = ActionMapper(mediaController)
    val runtime = TrikiRuntime(scope, bleManager, settingsRepository, actionMapper, logger)
    val fakeTrikiDataSource = FakeTrikiDataSource()
}
