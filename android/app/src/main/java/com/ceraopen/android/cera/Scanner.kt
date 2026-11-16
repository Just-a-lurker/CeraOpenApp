// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from CeraLib (MIT) cera/scan.py: find nearby machines without
// leaking platform BLE types into the client.
package com.ceraopen.android.cera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Advertised names observed so far: the PCM03SMAX tested advertised as
// "Hi-t-<id>_<last 4 hex of address>". Please report other names.
val KNOWN_NAME_PREFIXES = listOf("Hi-t-", "PCM03")

data class Found(val address: String, val name: String, val rssi: Int) {
    val likelyMachine: Boolean get() = KNOWN_NAME_PREFIXES.any { name.startsWith(it) }
}

/** Scan for machines; strongest signal first. The machine does not advertise
 *  its FFF0 service, so filtering is by name only. */
interface MachineScanner {
    suspend fun discover(timeoutMs: Long = 8_000): List<Found>
}

class AndroidScanner(context: Context) : MachineScanner {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission") // the caller checks permissions first
    override suspend fun discover(timeoutMs: Long): List<Found> {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw CeraException("Bluetooth is switched off")
        val seen = LinkedHashMap<String, Found>()
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Prefer the name from the advertisement: device.name can do
                // extra work and is null until a later lookup.
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                seen[result.device.address] =
                    Found(result.device.address, name.trim(), result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                callback,
            )
            var left = timeoutMs
            while (left > 0) {
                val step = minOf(200L, left)
                // Poll the failure flag without blocking past the slice.
                withTimeoutOrNull(step) { failed.await() }?.let {
                    throw CeraException("scan failed: error $it")
                }
                left -= step
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        return seen.values.sortedByDescending { it.rssi }
    }
}
