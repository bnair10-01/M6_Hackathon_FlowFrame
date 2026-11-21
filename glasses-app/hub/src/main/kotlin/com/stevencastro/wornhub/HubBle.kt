package com.stevencastro.wornhub

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
 * GATT server for the Worn Hub service. Commands arrive chunked on CMD;
 * events go out chunked as EVT notifications, one at a time per phone
 * (Android drops notifications sent before the previous one is acknowledged).
 * The access token is behind an encrypted read, so the phone must pair.
 * All callbacks are forwarded to the main thread.
 */
@SuppressLint("MissingPermission")
class HubBle(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onCommand(device: BluetoothDevice, json: String)
        fun onPhonesChanged(count: Int)
        fun token(): String
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var evt: BluetoothGattCharacteristic? = null
    private var advertising = false

    private val subscribed = LinkedHashSet<BluetoothDevice>()
    private val mtu = HashMap<String, Int>()
    private val assemblers = HashMap<String, ChunkAssembler>()
    private val queues = HashMap<String, ArrayDeque<ByteArray>>()
    private val inFlight = HashSet<String>()

    val phoneCount: Int get() = subscribed.size

    fun start(): String? {
        val adapter = manager.adapter ?: return "No Bluetooth"
        if (!adapter.isEnabled) return "Bluetooth is off"
        if (adapter.bluetoothLeAdvertiser == null) return "BLE advertising not supported"
        val gatt = manager.openGattServer(context, callback) ?: return "Couldn't open GATT server"
        server = gatt
        val service = BluetoothGattService(HubProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(HubProtocol.CMD,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val events = BluetoothGattCharacteristic(HubProtocol.EVT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        events.addDescriptor(BluetoothGattDescriptor(HubProtocol.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(events)
        evt = events
        service.addCharacteristic(BluetoothGattCharacteristic(HubProtocol.TOKEN,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        gatt.addService(service)
        return null
    }

    fun stop() {
        if (advertising) manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
        server?.close()
        server = null
        subscribed.clear()
        queues.clear()
        inFlight.clear()
    }

    /** Sends an event to every subscribed phone. */
    fun broadcast(json: String) = subscribed.toList().forEach { send(it, json) }

    fun send(device: BluetoothDevice, json: String) {
        if (device !in subscribed) return
        val chunks = HubProtocol.split(json.toByteArray(), (mtu[device.address] ?: 23) - 3)
        queues.getOrPut(device.address) { ArrayDeque() }.addAll(chunks)
        pump(device)
    }

    private fun pump(device: BluetoothDevice) {
        val gatt = server ?: return
        val ch = evt ?: return
        if (device.address in inFlight) return
        val next = queues[device.address]?.removeFirstOrNull() ?: return
        ch.value = next
        if (gatt.notifyCharacteristicChanged(device, ch, false)) inFlight.add(device.address)
        else queues[device.address]?.clear() // link is gone
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return
        if (advertising) return
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(HubProtocol.SERVICE)).setIncludeDeviceName(false).build(),
            AdvertiseData.Builder().setIncludeDeviceName(true).build(),
            advertiseCallback)
        advertising = true
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = Log.i(TAG, "advertising Worn Hub").let { }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
            advertising = false
        }
    }

    private fun forget(device: BluetoothDevice) {
        val wasSubscribed = subscribed.remove(device)
        mtu.remove(device.address)
        assemblers.remove(device.address)
        queues.remove(device.address)
        inFlight.remove(device.address)
        if (wasSubscribed) listener.onPhonesChanged(subscribed.size)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post { if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else Log.e(TAG, "addService: $status") }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post { if (newState != BluetoothProfile.STATE_CONNECTED) forget(device) }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            main.post { mtu[device.address] = newMtu }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                inFlight.remove(device.address)
                pump(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid != HubProtocol.CMD) return
            val copy = value.copyOf()
            main.post {
                val message = assemblers.getOrPut(device.address) { ChunkAssembler() }.accept(copy) ?: return@post
                listener.onCommand(device, message.toString(Charsets.UTF_8))
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = if (characteristic.uuid == HubProtocol.TOKEN) listener.token().toByteArray() else ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            main.post {
                if (descriptor.uuid != HubProtocol.CCCD) return@post
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    if (subscribed.add(device)) {
                        Log.i(TAG, "phone ${device.address} subscribed")
                        listener.onPhonesChanged(subscribed.size)
                    }
                } else {
                    forget(device)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    companion object {
        private const val TAG = "WornHub"
    }
}
