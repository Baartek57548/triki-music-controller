package pl.trikimusic.controller.service

import android.service.notification.NotificationListenerService
import pl.trikimusic.controller.TrikiMusicApplication

class TrikiNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        (application as TrikiMusicApplication).container.mediaController.refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        (application as TrikiMusicApplication).container.mediaController.refresh()
    }
}
