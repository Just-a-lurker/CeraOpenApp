// SPDX-License-Identifier: GPL-3.0-or-later
// Bridges the [MachineService] to the Compose UI and builds the CeraClient
// stack (AndroidBleLink + CeraClient) from the app context.
package com.ceraopen.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ceraopen.android.cera.AndroidBleLink
import com.ceraopen.android.cera.AndroidScanner
import com.ceraopen.android.cera.Beep
import com.ceraopen.android.cera.BrewMode
import com.ceraopen.android.cera.CeraClient
import com.ceraopen.android.cera.Found
import com.ceraopen.android.cera.ReservationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which Bluetooth runtime permissions this Android version needs.
 *
 * The two API-31 constants are plain strings; the branch on `api` is what
 * decides whether they are ever asked for, so inlining them is safe.
 */
@Suppress("InlinedApi")
fun androidRequirements(api: Int = Build.VERSION.SDK_INT): List<String> =
    if (api >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        )
    }

fun hasBluetoothPermissions(context: Context): Boolean =
    androidRequirements().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

class CeraViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CeraViewModel(app) as T
    }
}

class CeraViewModel(private val app: Application) : ViewModel() {

    lateinit var service: MachineService

    init {
        // The client callbacks point back to the service, so they reference
        // this lateinit property rather than a captured local. None of the
        // callbacks can run before init assigns it: connect()/publish() are
        // only reached from UI actions, long after construction.
        service = MachineService(
            scope = viewModelScope,
            newClient = { address ->
                CeraClient(
                    newLink = { AndroidBleLink(app, address) },
                    onStatus = { st -> service.onStatus(st) },
                    onFrame = { d, b -> service.reportFrame(d, b) },
                    onDisconnect = { service.onLost() },
                )
            },
            scanner = { timeoutMs -> AndroidScanner(app).discover(timeoutMs) },
        )
    }

    // Sample data replaces the live state only in a debuggable build, when the
    // activity is started with `--ez demo true`. Everything else is untouched.
    private val demo = MutableStateFlow<ServiceState?>(null)
    private val demoFrames = MutableStateFlow<List<FrameView>>(emptyList())

    val serviceState: StateFlow<ServiceState> =
        combine(service.state, demo) { live, sample -> sample ?: live }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ServiceState())

    val frames: StateFlow<List<FrameView>> =
        combine(service.frames, demoFrames) { live, sample ->
            if (sample.isEmpty()) live else sample
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val scanResults = MutableStateFlow<List<Found>?>(null)
    val scanning = MutableStateFlow(false)

    /** Show the sample dashboard (debuggable builds only). */
    fun enableDemo(running: Boolean = false, locked: Boolean = false) {
        demo.value = DemoMode.state(running = running, locked = locked)
        demoFrames.value = DemoMode.frames()
        period.value = "week"
        offset.value = 0
        chart.value = DemoMode.period("week")
    }

    // One-shot messages for the snackbar (errors and confirmations).
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    // Cups chart window, mirroring the web UI's period/offset pair.
    val period = MutableStateFlow("week")
    val offset = MutableStateFlow(0)
    val chart = MutableStateFlow<StatsPeriod?>(null)

    /** Run a service call, surfacing failures (and optional success) in a snackbar. */
    private fun run(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (success != null) _message.value = success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Something went wrong"
            }
        }
    }

    /** Refresh the chart from the service's cached stats. */
    fun reloadChart() {
        if (demo.value != null) {
            chart.value = DemoMode.period(period.value)
            return
        }
        chart.value = runCatching { service.statsPeriod(period.value, offset.value) }.getOrNull()
    }

    fun setPeriod(p: String) {
        period.value = p
        offset.value = 0
        reloadChart()
    }

    fun previousPeriod() {
        offset.value -= 1
        reloadChart()
    }

    fun nextPeriod() {
        if (offset.value < 0) offset.value += 1
        reloadChart()
    }

    fun scanForMachines() {
        if (scanning.value) return
        scanning.value = true
        viewModelScope.launch {
            try {
                scanResults.value = service.scan(8_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Scan failed"
            } finally {
                scanning.value = false
            }
        }
    }

    fun connect(address: String) = run {
        service.connect(address)
        reloadChart()
    }

    fun disconnect() {
        run { service.disconnect() }
        chart.value = null
        scanResults.value = null
    }

    fun setControl(enabled: Boolean) = service.setControl(enabled)

    fun refresh() = run { service.refresh(); reloadChart() }

    fun start(mode: BrewMode) = run { service.start(mode) }

    fun stop() = run { service.stop() }

    /** Save the recipe: settings first, then the target temperature. */
    fun saveRecipe(fields: Map<String, Int>, targetC: Double?) = run("Recipe saved") {
        if (fields.isNotEmpty()) service.updateSettings(fields)
        if (targetC != null) service.setTargetC(targetC)
    }

    fun setReservation(mode: ReservationMode, time: String, alarm: Boolean, beep: Beep) =
        run("Scheduled at $time") { service.setReservation(mode, time, alarm, beep) }

    fun cancelReservation() = run("Reservation cancelled") { service.cancelReservation() }

    override fun onCleared() {
        // Closing the app while a shot runs must not leave the machine
        // heating unattended: send STOP, then drop the link. This runs on a
        // scope of its own so it is not cancelled with viewModelScope.
        teardownScope.launch { runCatching { service.shutdown() } }
    }

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
