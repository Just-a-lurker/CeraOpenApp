// SPDX-License-Identifier: GPL-3.0-or-later
// The Android implementation of [BleLink], playing the part of CeraLib's
// bleak client. Android's BluetoothGatt allows one operation at a time and
// reports everything through callbacks; each suspend function here waits for
// its callback, and writes are serialized with a mutex.
//
// Frames are at most 20 bytes, so the default MTU is never negotiated away
// (the vendor app negotiated 247 but the machine still fragments at 20).
package com.ceraopen.android.cera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

private val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Connect + settle + discover + notify subscribe, GATT errors wrapped. */
@SuppressLint("MissingPermission") // the caller checks permissions first
class AndroidBleLink(
    private val context: Context,
    private val address: String,
) : BleLink() {

    @Volatile
    private var connected = false
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex() // Android allows one GATT operation at a time

    // At most one pending continuation per operation; binder thread resumes it.
    private var connectPending: CompletableDeferred<Int>? = null
    private var disconnectPending: CompletableDeferred<Int>? = null
    private var servicesPending: CompletableDeferred<Int>? = null
    private var descriptorPending: CompletableDeferred<Int>? = null
    private var writePending: CompletableDeferred<Int>? = null

    override val isConnected: Boolean get() = connected

    private fun adapter(): BluetoothAdapter = adapterOrNull()
        ?: throw CeraException("Bluetooth is not available")

    private fun adapterOrNull(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    // -- GATT callbacks (binder thread) ------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    connectPending?.complete(status)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    failPending(status)
                    disconnectPending?.complete(status)
                    onLost?.invoke()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesPending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                                        status: Int) {
            descriptorPending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt,
                                            characteristic: BluetoothGattCharacteristic,
                                            status: Int) {
            writePending?.complete(status)
        }

        // The pre-33 signature: on API 33+ the framework calls the new
        // overload, whose default implementation feeds this one, so
        // overriding just this works on every API level.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt,
                                              characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onNotification?.invoke(it.copyOf()) }
        }
    }

    /** A drop mid-operation must fail every waiter, not leave it hanging. */
    private fun failPending(status: Int) {
        connectPending?.complete(status)
        servicesPending?.complete(GATT_ERR_DROP)
        descriptorPending?.complete(GATT_ERR_DROP)
        writePending?.complete(GATT_ERR_DROP)
        disconnectPending?.complete(status)
    }

    // -- operations ----------------------------------------------------------

    override suspend fun connect() = wrap {
        checkPerms()
        connected = false
        val device: BluetoothDevice = try {
            adapter().getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw CeraException("bad Bluetooth address: $address")
        }
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw CeraException("connectGatt returned null")
        gatt = g
        try {
            // connectGatt returns before the link is up; wait for the state.
            awaitNew(connectPending, "connect") { connectPending = it }
            // Some stacks need a breath between connect and discovery
            // (bleak waits too); the client's settle wait follows anyway.
            delaySmall()
            if (!g.discoverServices()) throw CeraException("discoverServices could not start")
            awaitNew(servicesPending, "service discovery") { servicesPending = it }
            val service: BluetoothGattService = g.getService(SERVICE_UUID)
                ?: throw CeraException("the FFF0 service is missing")
            notifyChar = service.getCharacteristic(NOTIFY_UUID)
                ?: throw CeraException("the FFF1 characteristic is missing")
            writeChar = service.getCharacteristic(WRITE_UUID)
                ?: throw CeraException("the FFF2 characteristic is missing")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun startNotify() = wrap {
        val g = gatt ?: throw CeraException("not connected")
        val char = notifyChar ?: throw CeraException("not connected")
        if (!g.setCharacteristicNotification(char, true)) {
            throw CeraException("subscribing to FFF1 failed")
        }
        @Suppress("DEPRECATION")
        val cccd = char.getDescriptor(CCCD_UUID)
            ?: throw CeraException("the FFF1 client-config descriptor is missing")
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (!g.writeDescriptor(cccd)) throw CeraException("writing the FFF1 descriptor failed")
        val status = awaitNew(descriptorPending, "subscribe") { descriptorPending = it }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw CeraException("subscribing to FFF1 failed: GATT status $status")
        }
    }

    override suspend fun write(data: ByteArray) = wrap {
        val g = gatt ?: throw CeraException("not connected")
        val char = writeChar ?: throw CeraException("not connected")
        writeMutex.withLock {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(char)) throw CeraException("write could not start")
            val status = awaitNew(writePending, "write") { writePending = it }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw CeraException("write failed: GATT status $status")
            }
        }
    }

    override suspend fun disconnect() {
        val g = gatt ?: return
        try {
            if (connected) {
                g.disconnect()
                // Wait for the stack to report the drop before releasing the
                // native handle (bleak waits too); not fatal on timeout.
                if (connected) {
                    withTimeoutOrNull(1_500) {
                        awaitNew(disconnectPending, "disconnect") { disconnectPending = it }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    /** Hard teardown for a failed attempt; never reports, never throws. */
    override fun close() {
        connected = false
        failPending(GATT_ERR_DROP)
        val g = gatt
        gatt = null
        notifyChar = null
        writeChar = null
        try {
            g?.disconnect()
        } catch (_: Exception) {
        }
        try {
            g?.close()
        } catch (_: Exception) {
        }
    }

    // -- helpers --------------------------------------------------------------

    /** One fresh deferred awaited per call, resumed by a binder thread. */
    private suspend fun awaitNew(
        slot: CompletableDeferred<Int>?,
        what: String,
        set: (CompletableDeferred<Int>) -> Unit,
    ): Int {
        if (slot?.isActive == true) {
            // Should not happen (operations are serialized), but never hang
            // forever if it does.
            slot.complete(GATT_ERR_DROP)
        }
        val fresh = CompletableDeferred<Int>()
        set(fresh)
        return withContext(Dispatchers.Default) { fresh.await() }
    }

    private suspend fun delaySmall() {
        delay(300)
    }

    private fun checkPerms() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
        for (p in needed) {
            if (ContextCompat.checkSelfPermission(context, p) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw CeraException("missing Bluetooth permission: $p")
            }
        }
    }

    /** Keep [CeraException] the only thing the client sees. */
    private suspend fun wrap(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CeraException) {
            throw e
        } catch (e: SecurityException) {
            throw CeraException("Bluetooth permission denied: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CeraException("$e", e)
        }
    }

    private companion object {
        /** Synthetic status for "the link dropped mid-operation". */
        const val GATT_ERR_DROP = 0x7F7F
    }
}
