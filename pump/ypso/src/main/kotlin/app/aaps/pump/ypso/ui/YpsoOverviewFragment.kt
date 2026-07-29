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

/**
 * Read-only status screen.
 *
 * Its real job this session is the firmware parity check: every value shown here is meant to be
 * compared against the same value on the pump's own display. The history dump is deliberately
 * raw for the same reason.
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
            ?: PLACEHOLDER

        binding.connectionStatus.text = when {
            ypsoPlugin.isConnected()  -> rh.gs(R.string.ypso_connected)
            ypsoPlugin.isConnecting() -> rh.gs(R.string.ypso_connecting)
            else                      -> rh.gs(R.string.ypso_disconnected)
        }
    }

    companion object {

        private const val PLACEHOLDER = "---"
    }
}
