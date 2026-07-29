package app.aaps.pump.ypso.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.pump.ypso.R
import app.aaps.pump.ypso.YpsoPlugin
import app.aaps.pump.ypso.YpsoPump
import app.aaps.pump.ypso.databinding.YpsoFragmentBinding
import app.aaps.pump.ypso.events.EventYpsoPumpDataChanged
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import kotlin.math.abs

/**
 * Pump status screen.
 *
 * Its real job is the firmware parity check: every value shown here is meant to be compared
 * against the same value on the pump's own display. The history dump is deliberately raw for the
 * same reason, and the pump-clock row is deliberately a skew rather than a timestamp — both
 * clocks keep running, so the offset stays true while a captured reading does not.
 *
 * "Set pump clock" is the one control here that writes to the pump, and the only write in the
 * driver. It is confirmation-gated, and what it reports back is the pump's own clock read
 * afterwards, not the result of the write.
 */
class YpsoOverviewFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var ypsoPump: YpsoPump
    @Inject lateinit var ypsoPlugin: YpsoPlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private var _binding: YpsoFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = YpsoFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.refresh.setOnClickListener {
            binding.refresh.isEnabled = false
            commandQueue.readStatus(rh.gs(R.string.ypso_requested_by_user), object : app.aaps.core.interfaces.queue.Callback() {
                override fun run() {
                    activity?.runOnUiThread { binding.refresh.isEnabled = true }
                }
            })
        }

        binding.readHistory.setOnClickListener {
            binding.readHistory.isEnabled = false
            // Runs on the command queue's thread so it shares the normal connect/disconnect cycle
            // rather than opening a second BLE session from the UI thread.
            commandQueue.customCommand(YpsoLoadHistoryCommand(), object : app.aaps.core.interfaces.queue.Callback() {
                override fun run() {
                    activity?.runOnUiThread {
                        binding.readHistory.isEnabled = true
                        showHistoryDump()
                    }
                }
            })
        }

        // The only button here that writes to the pump, so it asks first. What it reports
        // afterwards is what reading the pump's clock back said, not whether the write was
        // accepted — the pump accepts bad writes silently.
        binding.setClock.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            OKDialog.showConfirmation(
                host,
                rh.gs(R.string.ypso_set_clock_confirm_title),
                rh.gs(R.string.ypso_set_clock_confirm_message),
                Runnable { setPumpClock() }
            )
        }
    }

    private fun setPumpClock() {
        binding.setClock.isEnabled = false
        commandQueue.customCommand(YpsoSyncTimeCommand(), object : app.aaps.core.interfaces.queue.Callback() {
            override fun run() {
                activity?.runOnUiThread {
                    if (_binding != null) binding.setClock.isEnabled = true
                    showClockWriteResult()
                }
            }
        })
    }

    /**
     * Reports the outcome from [YpsoPump.lastClockWriteConfirmed] rather than from the queue
     * callback's `result`, which is a `lateinit` left unset whenever a command is dropped. A
     * dropped command therefore reads as "not confirmed", which is the truthful answer.
     */
    private fun showClockWriteResult() {
        val confirmed = ypsoPump.lastClockWriteConfirmed == true
        val message = rh.gs(if (confirmed) R.string.ypso_clock_set_ok else R.string.ypso_clock_set_failed)
        activity?.let { OKDialog.show(it, rh.gs(R.string.ypso_clock_result_title), message) }
        aapsLogger.info(LTag.PUMP, "Clock write result shown: confirmed=$confirmed")
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventYpsoPumpDataChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        updateGui()
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showHistoryDump() {
        val dump = ypsoPump.lastHistoryDump
        val text = if (dump.isEmpty()) rh.gs(R.string.ypso_no_history) else dump.joinToString("\n")
        activity?.let { OKDialog.show(it, rh.gs(R.string.ypso_history_title), text) }
        aapsLogger.debug(LTag.PUMP, "History dump shown: ${dump.size} entries")
    }

    private fun updateGui() {
        if (_binding == null) return

        binding.serialNumber.text = ypsoPump.serialNumber.ifBlank { PLACEHOLDER }
        binding.firmware.text = ypsoPump.firmwareVersion.ifBlank { PLACEHOLDER }
        binding.macAddress.text = ypsoPump.pumpMac.ifBlank { rh.gs(R.string.ypso_not_paired) }

        binding.lastConnection.text =
            if (ypsoPump.lastConnection > 0) dateUtil.minAgo(rh, ypsoPump.lastConnection) else PLACEHOLDER

        binding.reservoir.text =
            if (ypsoPump.lastConnection > 0) rh.gs(R.string.ypso_reservoir_value, ypsoPump.reservoir) else PLACEHOLDER
        binding.battery.text =
            if (ypsoPump.batteryPercent > 0) "${ypsoPump.batteryPercent}%" else PLACEHOLDER
        binding.deliveryMode.text = ypsoPump.deliveryModeName.ifBlank { PLACEHOLDER }

        binding.basalRate.text =
            if (ypsoPump.basalProfileRates.isNotEmpty())
                rh.gs(R.string.ypso_basal_rate_value, ypsoPump.baseBasalRate)
            else PLACEHOLDER

        binding.activeProfile.text = when (ypsoPump.activeProfileId) {
            0    -> "A"
            1    -> "B"
            else -> PLACEHOLDER
        }

        binding.tempBasal.text = ypsoPump.activeTbr
            ?.let { rh.gs(R.string.ypso_tbr_value, it.percentage, it.remainingMinutes) }
            ?: if (ypsoPump.isTbrRunning) rh.gs(R.string.ypso_tbr_running_no_detail) else PLACEHOLDER

        binding.pumpClock.text = ypsoPump.pumpClockSkewMs?.let { skew ->
            val magnitude = durationText(abs(skew))
            when {
                abs(skew) <= CLOCK_IN_SYNC_MS -> rh.gs(R.string.ypso_clock_in_sync, magnitude)
                skew > 0                      -> rh.gs(R.string.ypso_clock_ahead, magnitude)
                else                          -> rh.gs(R.string.ypso_clock_behind, magnitude)
            }
        } ?: PLACEHOLDER

        binding.connectionStatus.text = when {
            ypsoPlugin.isConnected()  -> rh.gs(R.string.ypso_connected)
            ypsoPlugin.isConnecting() -> rh.gs(R.string.ypso_connecting)
            else                      -> rh.gs(R.string.ypso_disconnected)
        }
    }

    /** Coarse, signed-magnitude rendering — the number that matters here is the order of it. */
    private fun durationText(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60    -> "$seconds s"
            seconds < 3600  -> "${seconds / 60} min"
            else            -> "%d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
        }
    }

    companion object {

        private const val PLACEHOLDER = "---"

        /**
         * Skew below which this screen calls the two clocks agreed.
         *
         * Deliberately the same figure as `YpsoPumpManagerImpl.CLOCK_MATCH_TOLERANCE_MS`, which
         * is what decides whether a clock write is reported as confirmed. They cannot share a
         * constant across the module boundary, so keep them in step by hand: if this one is
         * looser, the row can read "in sync" for a write the driver called a failure.
         */
        private const val CLOCK_IN_SYNC_MS = 90_000L
    }
}
