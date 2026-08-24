package pl.trikimusic.controller

import android.app.Application

class TrikiMusicApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }
}
