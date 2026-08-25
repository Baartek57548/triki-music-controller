package pl.trikimusic.controller.data.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
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
        if (action in RATING_ACTIONS) {
            executeRatingAction(action)
            publishState()
            logger.log(LogCategory.MEDIA, "Wysłano ocenę ${action.name} do aktywnej sesji.")
            return@runCatching
        }
        val controller = activeController
        if (controller != null) {
            val controls = controller.transportControls
            when (action) {
                MediaAction.PLAY -> controls.play()
                MediaAction.PAUSE -> controls.pause()
                MediaAction.PLAY_PAUSE -> if (isPlaying(controller.playbackState)) controls.pause() else controls.play()
                MediaAction.NEXT -> controls.skipToNext()
                MediaAction.PREVIOUS -> controls.skipToPrevious()
                MediaAction.STOP -> controls.stop()
                MediaAction.NONE -> Unit
                MediaAction.LIKE,
                MediaAction.DISLIKE,
                -> executeRatingAction(action)
                MediaAction.VOLUME_UP,
                MediaAction.VOLUME_DOWN,
                MediaAction.MUTE,
                MediaAction.UNMUTE,
                -> executeVolumeAction(action)
            }
            logger.log(LogCategory.MEDIA, "Wysłano ${action.name} do ${controller.packageName}.")
        } else {
            dispatchMediaButton(action)
            logger.log(LogCategory.MEDIA, "Wysłano ${action.name} jako systemowy przycisk multimedialny.")
        }
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
                canLike = supportsRatingAction(controller, MediaAction.LIKE),
                canDislike = supportsRatingAction(controller, MediaAction.DISLIKE),
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
    private fun executeRatingAction(action: MediaAction) {
        require(action in RATING_ACTIONS) { "Akcja $action nie jest oceną utworu." }
        val controller = activeController
            ?: throw UnsupportedOperationException("Brak aktywnej sesji multimedialnej obsługującej ocenę utworu.")
        val customAction = findCustomRatingAction(controller, action)
        if (customAction != null) {
            controller.transportControls.sendCustomAction(customAction, customAction.extras)
            return
        }

        val playbackState = controller.playbackState
        val supportsSetRating = playbackState != null &&
            playbackState.actions and PlaybackState.ACTION_SET_RATING != 0L
        if (supportsSetRating) {
            val rating = when (controller.ratingType) {
                Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(action == MediaAction.LIKE)
                Rating.RATING_HEART -> if (action == MediaAction.LIKE) Rating.newHeartRating(true) else null
                else -> null
            }
            if (rating != null) {
                controller.transportControls.setRating(rating)
                return
            }
        }
        throw UnsupportedOperationException(
            "${mutableState.value.appName ?: controller.packageName} nie udostępnia akcji ${action.displayName.lowercase()}.",
        )
    }

    @Suppress("DEPRECATION")
    private fun supportsRatingAction(controller: MediaController, action: MediaAction): Boolean {
        if (findCustomRatingAction(controller, action) != null) return true
        val playbackState = controller.playbackState ?: return false
        if (playbackState.actions and PlaybackState.ACTION_SET_RATING == 0L) return false
        return when (controller.ratingType) {
            Rating.RATING_THUMB_UP_DOWN -> true
            Rating.RATING_HEART -> action == MediaAction.LIKE
            else -> false
        }
    }

    private fun findCustomRatingAction(
        controller: MediaController,
        action: MediaAction,
    ): PlaybackState.CustomAction? = controller.playbackState
        ?.customActions
        .orEmpty()
        .map { customAction ->
            customAction to RatingActionMatcher.score(customAction.action, customAction.name, action)
        }
        .filter { (_, score) -> score > 0 }
        .maxByOrNull { (_, score) -> score }
        ?.first

    /**
     * Public Android fallback for phones that block Notification Listener access
     * for sideloaded applications. It controls the current media-key consumer,
     * while metadata and artwork remain unavailable without session access.
     */
    private fun dispatchMediaButton(action: MediaAction) {
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaAction.NONE -> return
            MediaAction.LIKE,
            MediaAction.DISLIKE,
            -> error("Ocena utworu wymaga aktywnej sesji MediaSession z obsługą ratingu.")
            MediaAction.VOLUME_UP,
            MediaAction.VOLUME_DOWN,
            MediaAction.MUTE,
            MediaAction.UNMUTE,
            -> error("Akcja $action powinna zostać obsłużona przez AudioManager.adjustStreamVolume().")
        }
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
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
        val RATING_ACTIONS = setOf(MediaAction.LIKE, MediaAction.DISLIKE)
    }
}
