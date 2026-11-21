package com.stevencastro.wornrun

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * The glasses' side of the BLE link: a GATT server with the Worn Run service,
 * advertised so the iPhone can find and reconnect to it. Every callback is
 * forwarded to the main thread.
 *
 * Runs on Android 9, where Bluetooth needs only install-time permissions.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onFix(bytes: ByteArray)
        fun onRouteChunk(bytes: ByteArray)
        fun onControl(json: String)
        /** Number of phones actually using the Worn Run service. */
        fun onConnectionChanged(connected: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private lateinit var status: BluetoothGattCharacteristic
    private val connected = LinkedHashSet<BluetoothDevice>()
    // Other centrals (the vendor companion app, a ring…) also connect to the
    // glasses; only devices that talk to our service count as "the phone".
    private val active = LinkedHashSet<BluetoothDevice>()
    private val subscribed = LinkedHashSet<BluetoothDevice>()
    private var advertising = false

    /** Starts the GATT server and advertising; returns a problem description, or null when OK. */
    fun start(): String? {
        val adapter = manager.adapter ?: return "No Bluetooth on this device"
        if (!adapter.isEnabled) return "Bluetooth is off"
        Log.i(TAG, "BLE: multiAdvertisement=${adapter.isMultipleAdvertisementSupported} " +
                "le2M=${adapter.isLe2MPhySupported} extendedAdv=${adapter.isLeExtendedAdvertisingSupported}")
        if (adapter.bluetoothLeAdvertiser == null) return "This device can't advertise over BLE"

        val gatt = manager.openGattServer(context, callback) ?: return "Couldn't open GATT server"
        server = gatt
        val service = BluetoothGattService(RunProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(RunProtocol.FIX,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(RunProtocol.ROUTE,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(RunProtocol.CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        status = BluetoothGattCharacteristic(RunProtocol.STATUS,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ)
        status.addDescriptor(BluetoothGattDescriptor(RunProtocol.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(status)
        // Advertising starts once the service is registered (onServiceAdded).
        gatt.addService(service)
        return null
    }

    fun stop() {
        stopAdvertising()
        server?.close()
        server = null
        connected.clear()
        active.clear()
        subscribed.clear()
    }

    /** Sends a status update to every subscribed phone. */
    fun notifyStatus(bytes: ByteArray) {
        val gatt = server ?: return
        status.value = bytes
        for (device in subscribed) gatt.notifyCharacteristicChanged(device, status, false)
    }

    private fun markActive(device: BluetoothDevice) {
        if (active.add(device)) {
            Log.i(TAG, "phone ${device.address} is using Worn Run")
            listener.onConnectionChanged(active.size)
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // A 128-bit UUID plus flags fills most of the 31-byte packet; the
        // name goes in the scan response.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(RunProtocol.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        advertising = true
    }

    private fun stopAdvertising() {
        if (!advertising) return
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising Worn Run service")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
            advertising = false
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
                else Log.e(TAG, "addService failed: $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(device)
                } else {
                    connected.remove(device)
                    subscribed.remove(device)
                    if (active.remove(device)) listener.onConnectionChanged(active.size)
                }
                Log.i(TAG, "central ${device.address} state=$newState, connected=${connected.size}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val copy = value.copyOf()
            main.post {
                markActive(device)
                when (characteristic.uuid) {
                    RunProtocol.FIX -> listener.onFix(copy)
                    RunProtocol.ROUTE -> listener.onRouteChunk(copy)
                    RunProtocol.CONTROL -> listener.onControl(copy.toString(Charsets.UTF_8))
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            main.post {
                markActive(device)
                if (descriptor.uuid == RunProtocol.CCCD) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribed.add(device)
                    else subscribed.remove(device)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    companion object {
        private const val TAG = "WornRun"
    }
}
