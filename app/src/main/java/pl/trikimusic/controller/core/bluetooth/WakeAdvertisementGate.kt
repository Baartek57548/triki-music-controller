package pl.trikimusic.controller.core.bluetooth

class WakeAdvertisementGate(
    private val requiredSilenceMillis: Long = DEFAULT_REQUIRED_SILENCE_MILLIS,
) {
    private var lastAdvertisementMillis: Long? = null
    private var armed = false

    init {
        require(requiredSilenceMillis > 0L)
    }

    @Synchronized
    fun reset(nowMillis: Long) {
        lastAdvertisementMillis = nowMillis.takeIf { it >= 0L }
        armed = false
    }

    @Synchronized
    fun observeAdvertisement(nowMillis: Long): Boolean {
        if (nowMillis < 0L) {
            reset(nowMillis)
            return false
        }
        val previous = lastAdvertisementMillis
        if (previous == null || nowMillis < previous) {
            lastAdvertisementMillis = nowMillis
            armed = false
            return false
        }
        val mayConnect = armed || nowMillis - previous >= requiredSilenceMillis
        lastAdvertisementMillis = nowMillis
        return mayConnect
    }

    @Synchronized
    fun tryArm(nowMillis: Long): Boolean {
        val previous = lastAdvertisementMillis ?: return false
        if (nowMillis < previous) {
            reset(nowMillis)
            return false
        }
        if (armed || nowMillis - previous < requiredSilenceMillis) return false
        armed = true
        return true
    }

    @get:Synchronized
    val isArmed: Boolean
        get() = armed

    companion object {
        const val DEFAULT_REQUIRED_SILENCE_MILLIS = 5_000L
    }
}
