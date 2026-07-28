package app.aaps.pump.ypsopump.driver.config

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.widget.Toast
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.driver.ble.PumpBLESelector
import app.aaps.pump.common.driver.ble.PumpBLESelectorText
import app.aaps.pump.common.driver.ui.PumpBLEConfigActivity
import app.aaps.pump.ypsopump.R
import app.aaps.pump.ypsopump.event.EventPumpConnectionParametersChanged
import app.aaps.pump.ypsopump.keys.YpsopumpBooleanPreferenceKey
import app.aaps.pump.ypsopump.keys.YpsopumpStringPreferenceKey
import app.aaps.pump.ypsopump.util.YpsoPumpUtil
import javax.inject.Inject
import app.aaps.pump.common.R as Rcommon

/**
 * FORWARD-PORT NOTE
 *
 * Andy's original extended `pump-common`'s `PumpBLESelectorAbstract`, which upstream AAPS dropped
 * when it merged the generic driver framework. This implements [PumpBLESelector] directly and
 * carries over the handful of helpers the abstract base used to provide (removeBond,
 * getBondingStatusDescription, the Toast-based scan callbacks).
 *
 * Bonding is NOT wired up in this port: Andy's `BondStateReceiver` also lived in pump-common and is
 * gone. Selecting a device stores address/name/serial but does not initiate or observe bonding.
 * See PORTING_NOTES.md.
 */
@SuppressLint("MissingPermission")
class YpsoPumpBLESelector @Inject constructor(
    val resourceHelper: ResourceHelper,
    val aapsLogger: AAPSLogger,
    val preferences: Preferences,
    val rxBus: RxBus,
    val context: Context,
    var ypsoPumpUtil: YpsoPumpUtil,
    var pumpSync: PumpSync
) : PumpBLESelector {

    private val tag = LTag.PUMPBTCOMM

    var startingAddress: String? = null

    override fun onResume() {
        ypsoPumpUtil.preventConnect = true
        rxBus.send(EventPumpConnectionParametersChanged())
        startingAddress = currentlySelectedPumpAddress()
    }

    override fun onDestroy() {
        ypsoPumpUtil.preventConnect = false
        rxBus.send(EventPumpConnectionParametersChanged())

        val selectedAddress = currentlySelectedPumpAddress()

        if (startingAddress != selectedAddress && selectedAddress.isNotEmpty()) {
            pumpSync.connectNewPump()
        }
    }

    override fun removeDevice(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            removeBond(device)
        }
    }

    override fun getScanSettings(): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

    override fun getScanFilters(): List<ScanFilter>? = null

    override fun filterDevice(device: BluetoothDevice): BluetoothDevice? {
        if (device.name?.contains("Ypso") == true) {
            aapsLogger.info(tag, "   Found YpsoPump with address: ${device.address}")
            return device
        }
        return null
    }

    override fun cleanupAfterDeviceRemoved() {
        preferences.remove(YpsopumpStringPreferenceKey.Address)
        preferences.remove(YpsopumpStringPreferenceKey.Name)
        preferences.put(YpsopumpBooleanPreferenceKey.Bonded, false)
        rxBus.send(EventPumpConnectionParametersChanged())
    }

    override fun onDeviceSelected(bluetoothDevice: BluetoothDevice, bleAddress: String, deviceName: String, activity: PumpBLEConfigActivity) {

        val bondState = bluetoothDevice.bondState

        aapsLogger.debug(tag, "Device bonding status: $bondState desc: ${getBondingStatusDescription(bondState)}")

        val bonded = bondState == BluetoothDevice.BOND_BONDED

        // DEFERRED: Andy's original registered a BondStateReceiver here and called createBond().
        // That receiver lived in pump-common and no longer exists upstream. See PORTING_NOTES.md.
        preferences.put(YpsopumpBooleanPreferenceKey.Bonded, bonded)

        val addressChanged = setSystemParameterForBT(YpsopumpStringPreferenceKey.Address, bleAddress)

        setSystemParameterForBT(YpsopumpStringPreferenceKey.Name, deviceName)

        val name = bluetoothDevice.name
        if (name != null && name.contains("_")) {
            setSystemParameterForBT(YpsopumpStringPreferenceKey.Serial, name.substring(name.indexOf("_") + 1))
        }

        if (addressChanged) {
            rxBus.send(EventPumpConnectionParametersChanged())
        }
    }

    /**
     * @return true if a value was already stored and it actually changed
     */
    private fun setSystemParameterForBT(parameter: YpsopumpStringPreferenceKey, newValue: String): Boolean {
        val current = preferences.getIfExists(parameter)
        preferences.put(parameter, newValue)
        return current != null && current != newValue
    }

    override fun onDeviceSelectedClosesActivity(): Boolean = true

    override fun getUnknownPumpName(): String = "YpsoPump (?)"

    override fun currentlySelectedPumpAddress(): String = preferences.get(YpsopumpStringPreferenceKey.Address)

    override fun currentlySelectedPumpName(): String =
        preferences.getIfExists(YpsopumpStringPreferenceKey.Name)?.takeIf { it.isNotEmpty() } ?: getUnknownPumpName()

    override fun getAdditionalPermissions(): List<String> = listOf()

    override fun getText(key: PumpBLESelectorText): String {
        val stringId = when (key) {
            PumpBLESelectorText.SCAN_TITLE          -> R.string.ypsopump_ble_config_scan_title
            PumpBLESelectorText.SELECTED_PUMP_TITLE -> R.string.ypsopump_ble_config_selected_pump_title
            PumpBLESelectorText.REMOVE_TITLE        -> R.string.ypsopump_ble_config_remove_pump_title
            PumpBLESelectorText.REMOVE_TEXT         -> R.string.ypsopump_ble_config_remove_pump_confirmation
            PumpBLESelectorText.NO_SELECTED_PUMP    -> R.string.ypsopump_ble_config_no_pump_selected
            PumpBLESelectorText.PUMP_CONFIGURATION  -> R.string.ypsopump_configuration
            PumpBLESelectorText.INSTRUCTIONS_TEXT   -> R.string.ypsopump_ble_config_scan_title
        }
        return resourceHelper.gs(stringId)
    }

    // ---- helpers carried over from the removed PumpBLESelectorAbstract ----

    override fun onScanFailed(context: Context, errorCode: Int) {
        Toast.makeText(context, resourceHelper.gs(Rcommon.string.ble_config_scan_error, errorCode), Toast.LENGTH_LONG).show()
    }

    override fun onStartLeDeviceScan(context: Context) {
        Toast.makeText(context, Rcommon.string.ble_config_scan_scanning, Toast.LENGTH_SHORT).show()
    }

    override fun onStopLeDeviceScan(context: Context) {
        Toast.makeText(context, Rcommon.string.ble_config_scan_finished, Toast.LENGTH_SHORT).show()
    }

    override fun onManualStopLeDeviceScan(context: Context) {}

    override fun onNonManualStopLeDeviceScan(context: Context) {}

    private fun removeBond(bluetoothDevice: BluetoothDevice): Boolean =
        try {
            val method = bluetoothDevice.javaClass.getMethod("removeBond")
            when (val resultObject = method.invoke(bluetoothDevice)) {
                null -> {
                    aapsLogger.error(tag, "ERROR: result object is null")
                    false
                }

                else -> (resultObject as Boolean).also {
                    if (it) aapsLogger.info(tag, "Successfully removed bond")
                    else aapsLogger.warn(tag, "Bond was not removed")
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(tag, "ERROR: could not remove bond: ${e.message}")
            false
        }

    private fun getBondingStatusDescription(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE    -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED  -> "BOND_BONDED"
        else                         -> "UNKNOWN BOND STATUS ($state)"
    }
}
