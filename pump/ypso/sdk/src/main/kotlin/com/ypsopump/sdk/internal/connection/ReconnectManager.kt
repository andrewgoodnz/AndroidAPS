package com.ypsopump.sdk.internal.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reconnects to the pump after a BLE link is lost, backing off as attempts fail.
 *
 * Vendored from the upstream SDK, where it was the one piece of connection management left behind.
 * Delays run 5 s → 10 → 20 → 40 → 80 → 160 → 300, then hold at 300; a successful connect resets them.
 *
 * ⚠ **It must never run after a disconnect AAPS asked for.** AAPS drives this driver from its command
 * queue and calls `disconnect()` the moment the queue empties — that is the normal idle state, many
 * times an hour. Reconnecting after it would fight the command queue, hold the link open permanently
 * and drain both batteries, which is why the caller enables this only for a link that dropped on its
 * own. [enable] and [disable] bracket the period where a drop is unexpected.
 *
 * The case this exists for: the pump owns a bolus once it has been told, and delivery continues
 * whether or not the phone is still there (`PORTING_NOTES` §4g). If the link drops mid-delivery, the
 * amount actually given is sitting in the pump's event log and can be read the moment the link comes
 * back. Without this, nothing tries until AAPS happens to issue its next command.
 *
 * @param scope coroutine scope the retry job runs in.
 * @param onReconnect invoked when an attempt is due.
 * @param onStatusUpdate progress for the log and the UI.
 */
internal class ReconnectManager(
    private val scope: CoroutineScope,
    private val onReconnect: suspend () -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {

    companion object {

        private const val INITIAL_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS = 300_000L
        private const val BACKOFF_FACTOR = 2.0
    }

    private var currentDelayMs = INITIAL_DELAY_MS
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var enabled = false

    /**
     * Called when the link drops. Schedules an attempt after the current backoff, and lengthens it.
     *
     * Does nothing while disabled, which is what keeps a deliberate disconnect from triggering one.
     */
    fun onDisconnect() {
        if (!enabled) return
        attempt++
        onStatusUpdate("Link lost; reconnecting in ${currentDelayMs / 1000}s (attempt $attempt)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(currentDelayMs)
            currentDelayMs = (currentDelayMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_DELAY_MS)
            onReconnect()
        }
    }

    /** Called once the link is back. Cancels anything pending and resets the backoff. */
    fun onConnected() {
        reconnectJob?.cancel()
        reconnectJob = null
        currentDelayMs = INITIAL_DELAY_MS
        attempt = 0
    }

    /** Start treating a dropped link as unexpected. */
    fun enable() {
        enabled = true
    }

    /** Stop doing so, and abandon any pending attempt. */
    fun disable() {
        enabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentDelayMs = INITIAL_DELAY_MS
        attempt = 0
    }

    val isEnabled: Boolean get() = enabled
    val currentAttempt: Int get() = attempt
}
