package pl.trikimusic.controller.core.bluetooth

import pl.trikimusic.controller.domain.model.ButtonClickEvent
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.TrikiSensorData

/**
 * Interprets byte 1 only after identifying the connected firmware variant.
 * Some controllers expose a 0/1 button flag, while others put a rolling 0..15
 * packet id in the same byte. Waiting for a repeated 0/1 run prevents packet ids
 * from becoming phantom media clicks.
 */
class TrikiButtonInterpreter {
    var protocolMode: TrikiButtonProtocolMode = TrikiButtonProtocolMode.UNKNOWN
        private set

    val shouldSuppressMotionControl: Boolean
        get() = protocolMode == TrikiButtonProtocolMode.BUTTON_FLAG &&
            (stablePressed || candidatePressed || pendingClickCount > 0)

    val isPressed: Boolean
        get() = protocolMode == TrikiButtonProtocolMode.BUTTON_FLAG && stablePressed

    private var lastTimestampNanos: Long? = null
    private var observedStatus: Int? = null
    private var observedRunLength = 0
    private var longestObservedRun = 0
    private var observationCount = 0

    private var stablePressed = false
    private var candidatePressed = false
    private var candidateSinceNanos = 0L
    private var pressedAtNanos: Long? = null
    private var pendingClickCount = 0
    private var clickDeadlineNanos: Long? = null
    private var currentHoldConsumed = false

    fun consumeCurrentHold(): Boolean {
        if (!isPressed) return false
        currentHoldConsumed = true
        pendingClickCount = 0
        clickDeadlineNanos = null
        return true
    }

    fun checkAndConsumeHoldDuration(now: Long, requiredDurationNanos: Long): Boolean {
        val pressedAt = pressedAtNanos
        if (!isPressed || currentHoldConsumed || pressedAt == null) {
            return false
        }
        if (now - pressedAt >= requiredDurationNanos) {
            consumeCurrentHold()
            return true
        }
        return false
    }

    fun reset() {
        protocolMode = TrikiButtonProtocolMode.UNKNOWN
        lastTimestampNanos = null
        observedStatus = null
        observedRunLength = 0
        longestObservedRun = 0
        observationCount = 0
        clearInteraction()
    }

    fun process(sample: TrikiSensorData): ButtonClickEvent? {
        val now = sample.timestampNanos
        val previousTimestamp = lastTimestampNanos
        if (
            previousTimestamp != null &&
            (now <= previousTimestamp || now - previousTimestamp > MAX_STREAM_GAP_NANOS)
        ) {
            reset()
        }
        lastTimestampNanos = now

        if (protocolMode == TrikiButtonProtocolMode.UNKNOWN) {
            observeProtocol(sample.status, now)
            return null
        }
        if (protocolMode == TrikiButtonProtocolMode.SEQUENCE_COUNTER) return null

        if (sample.status !in BUTTON_STATUS_RANGE) {
            protocolMode = TrikiButtonProtocolMode.SEQUENCE_COUNTER
            clearInteraction()
            return null
        }

        return processButtonState(sample.status == PRESSED_STATUS, now)
    }

    private fun observeProtocol(status: Int, now: Long) {
        if (status !in BUTTON_STATUS_RANGE) {
            protocolMode = TrikiButtonProtocolMode.SEQUENCE_COUNTER
            clearInteraction()
            return
        }

        observationCount++
        if (observedStatus == status) {
            observedRunLength++
        } else {
            observedStatus = status
            observedRunLength = 1
        }
        longestObservedRun = maxOf(longestObservedRun, observedRunLength)

        if (
            observationCount >= MIN_PROTOCOL_OBSERVATIONS &&
            longestObservedRun >= MIN_REPEATED_STATUS_RUN
        ) {
            protocolMode = TrikiButtonProtocolMode.BUTTON_FLAG
            stablePressed = status == PRESSED_STATUS
            candidatePressed = stablePressed
            candidateSinceNanos = now
            pressedAtNanos = now.takeIf { stablePressed }
            pendingClickCount = 0
            clickDeadlineNanos = null
            currentHoldConsumed = false
        }
    }

    private fun processButtonState(rawPressed: Boolean, now: Long): ButtonClickEvent? {
        var completed = finalizeExpiredSequence(rawPressed, now)

        if (rawPressed != candidatePressed) {
            candidatePressed = rawPressed
            candidateSinceNanos = now
        }

        if (
            candidatePressed != stablePressed &&
            now - candidateSinceNanos >= DEBOUNCE_NANOS
        ) {
            stablePressed = candidatePressed
            if (stablePressed) {
                pressedAtNanos = now
                currentHoldConsumed = false
            } else {
                val releasedEvent = registerRelease(now)
                if (completed == null) completed = releasedEvent
            }
        }

        return completed
    }

    private fun finalizeExpiredSequence(rawPressed: Boolean, now: Long): ButtonClickEvent? {
        val deadline = clickDeadlineNanos ?: return null
        if (now < deadline || stablePressed || rawPressed) return null
        return completePendingSequence(now)
    }

    private fun registerRelease(now: Long): ButtonClickEvent? {
        val pressedAt = pressedAtNanos
        pressedAtNanos = null
        if (pressedAt == null) return null

        if (currentHoldConsumed) {
            currentHoldConsumed = false
            pendingClickCount = 0
            clickDeadlineNanos = null
            return null
        }

        val duration = now - pressedAt
        if (duration !in MIN_CLICK_PRESS_NANOS..MAX_CLICK_PRESS_NANOS) {
            pendingClickCount = 0
            clickDeadlineNanos = null
            return null
        }

        pendingClickCount++
        if (pendingClickCount >= ButtonClickType.TRIPLE.clickCount) {
            return completePendingSequence(now)
        }
        clickDeadlineNanos = now + MULTI_CLICK_TIMEOUT_NANOS
        return null
    }

    private fun completePendingSequence(timestampNanos: Long): ButtonClickEvent? {
        val type = when (pendingClickCount) {
            ButtonClickType.SINGLE.clickCount -> ButtonClickType.SINGLE
            ButtonClickType.DOUBLE.clickCount -> ButtonClickType.DOUBLE
            ButtonClickType.TRIPLE.clickCount -> ButtonClickType.TRIPLE
            else -> null
        }
        pendingClickCount = 0
        clickDeadlineNanos = null
        currentHoldConsumed = false
        return type?.let { ButtonClickEvent(it, timestampNanos) }
    }

    private fun clearInteraction() {
        stablePressed = false
        candidatePressed = false
        candidateSinceNanos = 0L
        pressedAtNanos = null
        pendingClickCount = 0
        clickDeadlineNanos = null
        currentHoldConsumed = false
    }

    private companion object {
        val BUTTON_STATUS_RANGE = 0..1
        const val PRESSED_STATUS = 1
        const val MIN_PROTOCOL_OBSERVATIONS = 12
        const val MIN_REPEATED_STATUS_RUN = 4
        const val DEBOUNCE_NANOS = 18_000_000L
        const val MIN_CLICK_PRESS_NANOS = 25_000_000L
        const val MAX_CLICK_PRESS_NANOS = 2_000_000_000L
        const val MULTI_CLICK_TIMEOUT_NANOS = 450_000_000L
        const val MAX_STREAM_GAP_NANOS = 300_000_000L
    }
}

enum class TrikiButtonProtocolMode(val displayName: String) {
    UNKNOWN("Rozpoznawanie"),
    BUTTON_FLAG("Przycisk 0/1"),
    SEQUENCE_COUNTER("Licznik ramek"),
}
