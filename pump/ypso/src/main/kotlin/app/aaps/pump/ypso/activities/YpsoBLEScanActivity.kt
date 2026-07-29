package app.aaps.pump.ypso.activities

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.extensions.safeEnable
import app.aaps.pump.ypso.R
import app.aaps.pump.ypso.databinding.YpsoBlescannerActivityBinding
import app.aaps.pump.ypso.events.EventYpsoPumpDeviceChanged
import app.aaps.pump.ypso.keys.YpsoIntKey
import app.aaps.pump.ypso.keys.YpsoStringNonKey
import javax.inject.Inject

/**
 * Lets the user pick their pump and bond to it.
 *
 * Modelled on DanaRS's `BLEScanActivity`, because the YpsoPump needs a real Bluetooth bond —
 * Medtrum's serial-number-only model does not apply here. The user picks the device, Android
 * shows the pairing dialog, and the pump displays the code to confirm. This happens once per
 * phone; afterwards the driver reconnects to the stored MAC over the existing bond.
 *
 * Only the MAC is stored. There is no password to enter and none is kept: the session password
 * is derived from the MAC itself on every connect.
 */
class YpsoBLEScanActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var context: Context
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger

    private var listAdapter: ListAdapter? = null
    private val devices = ArrayList<BluetoothDeviceItem>()

    private var bondReceiver: BroadcastReceiver? = null
    private var bondDialog: AlertDialog? = null
    private var bondingDevice: String? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private lateinit var binding: YpsoBlescannerActivityBinding

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = YpsoBlescannerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        title = rh.gs(R.string.ypso_pairing_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        if (!blePreCheck.prerequisitesCheck(this)) {
            ToastUtils.errorToast(this, getString(app.aaps.core.ui.R.string.need_connect_permission))
            finish()
        }

        listAdapter = ListAdapter()
        binding.bleScannerListview.emptyView = binding.bleScannerNoDevice
        binding.bleScannerListview.adapter = listAdapter
        listAdapter?.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        bluetoothAdapter?.safeEnable()
        // The system pairing dialog pauses us; don't resume scanning underneath it.
        if (bondingDevice == null) startScan()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Both hold a reference to this activity; leaving either behind leaks it.
        unregisterBondReceiver()
        bondDialog?.dismiss()
        bondDialog = null
        binding.bleScannerListview.adapter = null
    }

    @SuppressLint("MissingPermission")
    private fun startScan() =
        try {
            bluetoothLeScanner?.startScan(scanCallback)
        } catch (_: IllegalStateException) {
            // Bluetooth off; onResume runs again when the user turns it on.
        }

    @SuppressLint("MissingPermission")
    private fun stopScan() =
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: IllegalStateException) {
        }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.rssi < preferences.get(YpsoIntKey.YpsoRssiThreshold)) return
            addBleDevice(result.device, result.rssi)
        }
    }

    @SuppressLint("MissingPermission")
    private fun addBleDevice(device: BluetoothDevice?, rssi: Int) {
        val name = device?.name ?: return
        if (!name.startsWith(YPSOPUMP_NAME_PREFIX)) return

        val item = BluetoothDeviceItem(device, rssi)
        if (devices.contains(item)) return
        aapsLogger.debug(LTag.PUMPBTCOMM, "Found $name (${device.address}) RSSI $rssi")
        devices.add(item)
        runOnUiThread { listAdapter?.notifyDataSetChanged() }
    }

    internal inner class ListAdapter : BaseAdapter() {

        override fun getCount(): Int = devices.size
        override fun getItem(i: Int): BluetoothDeviceItem = devices[i]
        override fun getItemId(i: Int): Long = 0

        override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
            var v = convertView
            val holder: ViewHolder
            if (v == null) {
                v = View.inflate(applicationContext, R.layout.ypso_blescanner_item, null)
                holder = ViewHolder(v)
                v.tag = holder
            } else {
                holder = v.tag as ViewHolder
            }
            holder.setData(getItem(i))
            return v!!
        }

        private inner class ViewHolder(v: View) : View.OnClickListener {

            private lateinit var item: BluetoothDeviceItem
            private val name: TextView = v.findViewById(R.id.ble_name)
            private val address: TextView = v.findViewById(R.id.ble_address)

            init {
                v.setOnClickListener(this@ViewHolder)
            }

            @SuppressLint("MissingPermission")
            override fun onClick(v: View) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
                    return
                }

                val device = item.device
                preferences.put(YpsoStringNonKey.MacAddress, device.address)
                preferences.put(YpsoStringNonKey.DeviceName, item.name)
                preferences.put(YpsoStringNonKey.SerialNumber, item.name.removePrefix(YPSOPUMP_NAME_PREFIX))

                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "${device.address} already bonded")
                    finishPairing(bonded = true)
                    return
                }

                // Bonding needs the user: Android raises a pairing dialog and the pump shows the
                // code to match. Wait for it here rather than closing straight away — the driver
                // must not be told about this pump until the bond exists, or the command queue
                // will try to connect mid-bond and fail with an opaque BLE error.
                stopScan()
                aapsLogger.debug(LTag.PUMPBTCOMM, "Bonding ${device.address}")
                awaitBondFor(device)
                if (!device.createBond()) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "createBond() refused for ${device.address}")
                    cancelBondWait()
                    ToastUtils.errorToast(context, rh.gs(R.string.ypso_bonding_failed))
                }
            }

            @SuppressLint("MissingPermission")
            fun setData(data: BluetoothDeviceItem) {
                name.text = data.name
                address.text = "${data.device.address}   ${data.rssi} dBm"
                item = data
            }
        }
    }

    // ==================== BONDING ====================

    /**
     * Blocks the screen with a progress dialog until [device] finishes bonding.
     *
     * The driver is only told about the pump once the bond exists — see the call site. A failed
     * or dismissed bond leaves the user on the scan list to try again.
     */
    @SuppressLint("MissingPermission")
    private fun awaitBondFor(device: BluetoothDevice) {
        bondingDevice = device.address
        bondDialog = AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.ypso_bonding_title))
            .setMessage(rh.gs(R.string.ypso_bonding_message))
            .setCancelable(false)
            .setNegativeButton(app.aaps.core.ui.R.string.cancel) { _, _ -> cancelBondWait() }
            .show()

        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (changed?.address != bondingDevice) return

                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED  -> {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Bonded to $bondingDevice")
                        finishPairing(bonded = true)
                    }

                    BluetoothDevice.BOND_NONE    -> {
                        aapsLogger.error(LTag.PUMPBTCOMM, "Bonding failed for $bondingDevice")
                        cancelBondWait()
                        ToastUtils.errorToast(context, rh.gs(R.string.ypso_bonding_failed))
                    }

                    else                         -> Unit // BOND_BONDING, still waiting
                }
            }
        }
        // ACTION_BOND_STATE_CHANGED is a protected system broadcast, so it still reaches a
        // non-exported receiver.
        ContextCompat.registerReceiver(
            this, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Stops waiting and leaves the user on the scan list. */
    private fun cancelBondWait() {
        unregisterBondReceiver()
        bondDialog?.dismiss()
        bondDialog = null
        bondingDevice = null
        startScan()
    }

    /** Tells the driver which pump to use, now that it is actually bonded, and closes. */
    private fun finishPairing(bonded: Boolean) {
        unregisterBondReceiver()
        bondDialog?.dismiss()
        bondDialog = null
        if (bonded) rxBus.send(EventYpsoPumpDeviceChanged())
        finish()
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        bondReceiver = null
    }

    inner class BluetoothDeviceItem internal constructor(val device: BluetoothDevice, val rssi: Int) {

        @SuppressLint("MissingPermission")
        val name: String = device.name ?: "(unknown)"

        override fun equals(other: Any?): Boolean =
            other is BluetoothDeviceItem && device.address == other.device.address

        override fun hashCode(): Int = device.address.hashCode()
    }

    companion object {

        /** vicktor's `YPSOPUMP_NAME_PREFIX`; the serial follows it, e.g. `YpsoPump_10178681`. */
        private const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"
    }
}
