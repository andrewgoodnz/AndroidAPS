package app.aaps.pump.ypso.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.BondState
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scanning and bonding for the pairing screen, and nothing else.
 *
 * Deliberately **not** an implementation of [app.aaps.core.interfaces.pump.ble.BleTransport].
 * That interface also covers the GATT connection, which this driver does not do that way - the
 * vendored SDK owns the connection through Nordic's `BleManager`. Implementing it would mean
 * stubbing eight `gatt` methods that nothing calls, and a stub that silently does nothing is worse
 * than an absent one. What the pairing screen actually needs is a list of pumps and a bond, so
 * that is what this provides. It still hands back the shared [ScannedDevice] so the shared
 * `BleScanStep` can draw it.
 *
 * Callers must have passed `BlePreCheck` first; the permission annotations are suppressed on that
 * basis, as they were in the activity this replaces.
 */
@SingleIn(AppScope::class)
@Inject
class YpsoPairingScanner(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private var scanning = false
    private var rssiThreshold = Int.MIN_VALUE

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.rssi < rssiThreshold) return
            add(result.device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Ypso pairing scan failed with code $errorCode")
        }
    }

    /**
     * @param rssiThreshold devices weaker than this are dropped, as the scan list did before.
     */
    @SuppressLint("MissingPermission")
    fun startScan(rssiThreshold: Int) {
        if (scanning) return
        this.rssiThreshold = rssiThreshold
        _devices.value = emptyList()
        val scanner = adapter?.bluetoothLeScanner ?: run {
            aapsLogger.error(LTag.PUMPBTCOMM, "No BLE scanner; cannot scan for pumps")
            return
        }
        scanning = true
        scanner.startScan(scanCallback)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Ypso pairing scan started, RSSI floor $rssiThreshold")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Ypso pairing scan stopped")
    }

    @SuppressLint("MissingPermission")
    private fun add(device: BluetoothDevice?, rssi: Int) {
        val name = device?.name ?: return
        if (!name.startsWith(YPSOPUMP_NAME_PREFIX)) return
        val address = device.address
        if (_devices.value.any { it.address == address }) return
        aapsLogger.debug(LTag.PUMPBTCOMM, "Found $name ($address) RSSI $rssi")
        _devices.value = _devices.value + ScannedDevice(name = name, address = address, rssi = rssi)
    }

    @SuppressLint("MissingPermission")
    fun bondState(address: String): BondState =
        when (adapter?.getRemoteDevice(address)?.bondState) {
            BluetoothDevice.BOND_BONDED  -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            else                         -> BondState.NONE
        }

    @SuppressLint("MissingPermission")
    fun createBond(address: String): Boolean =
        adapter?.getRemoteDevice(address)?.createBond() == true

    companion object {

        /** vicktor's `YPSOPUMP_NAME_PREFIX`; the serial follows it, e.g. `YpsoPump_10178681`. */
        const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"
    }
}
