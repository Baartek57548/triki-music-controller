package pl.trikimusic.controller.data.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.repository.MediaControllerGateway
import pl.trikimusic.controller.service.TrikiNotificationListenerService

class AndroidMediaControllerGateway(
    private val context: Context,
    private val logger: AppLogger,
) : MediaControllerGateway {
    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, TrikiNotificationListenerService::class.java)
    private val mutableState = MutableStateFlow(MediaSessionState())
    override val state: StateFlow<MediaSessionState> = mutableState.asStateFlow()
    private var activeController: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        selectController(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishState()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishState()
        override fun onSessionDestroyed() = refresh()
    }

    init {
        refresh()
    }

    override fun refresh() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!granted) {
            detachController()
            mutableState.value = volumeState(MediaSessionState(hasPermission = false))
            return
        }
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, listenerComponent)
            selectController(mediaSessionManager.getActiveSessions(listenerComponent))
        } catch (error: SecurityException) {
            logger.log(LogCategory.MEDIA, "Android odmówił dostępu do aktywnych sesji.", error)
            detachController()
            mutableState.value = volumeState(
                MediaSessionState(hasPermission = false, errorMessage = "Brak dostępu do sesji multimedialnych."),
            )
        }
    }

    override fun execute(action: MediaAction): Result<Unit> = runCatching {
        if (action in VOLUME_ACTIONS) {
            executeVolumeAction(action)
            publishState()
            logger.log(LogCategory.MEDIA, "Wykonano akcję ${action.name} przez AudioManager.")
            return@runCatching
        }
        val controller = requireNotNull(activeController) { "Brak aktywnego odtwarzacza multimedialnego." }
        val controls = controller.transportControls
        when (action) {
            MediaAction.PLAY -> controls.play()
            MediaAction.PAUSE -> controls.pause()
            MediaAction.PLAY_PAUSE -> if (isPlaying(controller.playbackState)) controls.pause() else controls.play()
            MediaAction.NEXT -> controls.skipToNext()
            MediaAction.PREVIOUS -> controls.skipToPrevious()
            MediaAction.STOP -> controls.stop()
            MediaAction.NONE -> Unit
            MediaAction.VOLUME_UP,
            MediaAction.VOLUME_DOWN,
            MediaAction.MUTE,
            MediaAction.UNMUTE,
            -> executeVolumeAction(action)
        }
        logger.log(LogCategory.MEDIA, "Wysłano ${action.name} do ${controller.packageName}.")
    }.onFailure { error -> logger.log(LogCategory.MEDIA, error.message ?: "Błąd sterowania multimediami.", error) }

    private fun selectController(controllers: List<MediaController>) {
        val selected = controllers.firstOrNull { isPlaying(it.playbackState) }
            ?: controllers.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
        if (selected?.sessionToken != activeController?.sessionToken) {
            detachController()
            activeController = selected
            selected?.registerCallback(controllerCallback)
        }
        publishState()
    }

    private fun publishState() {
        val controller = activeController
        if (controller == null) {
            mutableState.value = volumeState(MediaSessionState(hasPermission = hasSessionPermission()))
            return
        }
        val metadata = controller.metadata
        val packageName = controller.packageName
        val appName = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        mutableState.value = volumeState(
            MediaSessionState(
                hasPermission = true,
                hasActiveSession = true,
                isPlaying = isPlaying(controller.playbackState),
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                artworkUri = resolveArtworkUri(metadata),
                packageName = packageName,
                appName = appName,
            ),
        )
    }

    private fun volumeState(base: MediaSessionState): MediaSessionState = base.copy(
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC),
    )

    private fun executeVolumeAction(action: MediaAction) {
        val direction = when (action) {
            MediaAction.VOLUME_UP -> AudioManager.ADJUST_RAISE
            MediaAction.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            MediaAction.MUTE -> AudioManager.ADJUST_MUTE
            MediaAction.UNMUTE -> AudioManager.ADJUST_UNMUTE
            else -> error("Akcja $action nie jest akcją głośności.")
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    @Suppress("DEPRECATION")
    private fun resolveArtworkUri(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        val providedUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (!providedUri.isNullOrBlank()) return providedUri
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return null
        return runCatching {
            val directory = File(context.cacheDir, "media_artwork").apply { mkdirs() }
            val identity = listOf(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                bitmap.generationId,
            ).hashCode()
            val target = File(directory, "artwork-${identity.toUInt().toString(16)}.png")
            if (!target.exists()) {
                val scaled = scaleArtwork(bitmap)
                FileOutputStream(target).use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Nie udało się zakodować okładki." }
                }
                if (scaled !== bitmap) scaled.recycle()
                directory.listFiles()
                    ?.sortedByDescending(File::lastModified)
                    ?.drop(MAX_CACHED_ARTWORK_FILES)
                    ?.forEach(File::delete)
            }
            target.toURI().toString()
        }.onFailure { error ->
            logger.log(LogCategory.MEDIA, "Nie udało się przygotować okładki MediaSession.", error)
        }.getOrNull()
    }

    private fun scaleArtwork(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_ARTWORK_EDGE_PX) return bitmap
        val scale = MAX_ARTWORK_EDGE_PX.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun detachController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    private fun hasSessionPermission(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun isPlaying(state: PlaybackState?): Boolean =
        state?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)

    private companion object {
        const val MAX_ARTWORK_EDGE_PX = 512
        const val MAX_CACHED_ARTWORK_FILES = 8
        val VOLUME_ACTIONS = setOf(
            MediaAction.VOLUME_UP,
            MediaAction.VOLUME_DOWN,
            MediaAction.MUTE,
            MediaAction.UNMUTE,
        )
    }
}
