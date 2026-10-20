package pl.trikimusic.controller.core.bluetooth

class WakeAdvertisementGate(
    private val requiredSilenceMillis: Long = DEFAULT_REQUIRED_SILENCE_MILLIS,
    private val maximumWaitMillis: Long = DEFAULT_MAXIMUM_WAIT_MILLIS,
) {
    private var lastAdvertisementMillis: Long? = null
    private var waitingSinceMillis: Long? = null
    private var armed = false

    init {
        require(requiredSilenceMillis > 0L)
        require(maximumWaitMillis >= requiredSilenceMillis)
    }

    @Synchronized
    fun reset(nowMillis: Long, waitForSilence: Boolean = true) {
        lastAdvertisementMillis = nowMillis.takeIf { it >= 0L }
        waitingSinceMillis = lastAdvertisementMillis
        armed = !waitForSilence && nowMillis >= 0L
    }

    @Synchronized
    fun observeAdvertisement(nowMillis: Long): Boolean {
        if (nowMillis < 0L) {
            reset(nowMillis)
            return false
        }
        val previous = lastAdvertisementMillis
        if (previous == null || nowMillis < previous) {
            reset(nowMillis)
            return false
        }
        // Firmware may keep advertising after GATT closes; always permit a bounded retry.
        val waitedLongEnough = waitingSinceMillis?.let { nowMillis - it >= maximumWaitMillis } == true
        val mayConnect = armed || nowMillis - previous >= requiredSilenceMillis || waitedLongEnough
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
        val waitedLongEnough = waitingSinceMillis?.let { nowMillis - it >= maximumWaitMillis } == true
        if (armed || (nowMillis - previous < requiredSilenceMillis && !waitedLongEnough)) return false
        armed = true
        return true
    }

    @get:Synchronized
    val isArmed: Boolean
        get() = armed

    companion object {
        const val DEFAULT_REQUIRED_SILENCE_MILLIS = 5_000L
        const val DEFAULT_MAXIMUM_WAIT_MILLIS = 15_000L
    }
}
