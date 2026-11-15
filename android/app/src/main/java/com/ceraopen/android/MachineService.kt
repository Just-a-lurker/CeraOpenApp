// SPDX-License-Identifier: GPL-3.0-or-later
// Port of CeraOpenApp (GPL-3.0) ceraopen/service.py. Owns one CeraClient,
// serializes commands, enforces the control lock, and exposes a reactive
// state with the frame backlog. Runs inside a viewModelScope, so its two
// StateFlows are safe to collect on the UI thread.
package com.ceraopen.android

import com.ceraopen.android.cera.Beep
import com.ceraopen.android.cera.BrewMode
import com.ceraopen.android.cera.Bucket
import com.ceraopen.android.cera.CeraClient
import com.ceraopen.android.cera.CeraException
import com.ceraopen.android.cera.Found
import com.ceraopen.android.cera.ReservationMode
import com.ceraopen.android.cera.State
import com.ceraopen.android.cera.Stats
import com.ceraopen.android.cera.Status
import com.ceraopen.android.cera.hexSpaced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Raised when a write is attempted while the controls are locked off. */
class ControlLockedException(message: String) : Exception(message)

/** Mirrors the web UI's snapshot(), only the fields the app renders. */
data class ServiceState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val address: String? = null,
    val controlEnabled: Boolean = false,
    val model: String? = null,
    val versionA: String? = null,
    val versionB: String? = null,
    val waterTempC: Int? = null,
    val batteryRaw: Int? = null,
    val stateText: String = "IDLE",
    val running: Boolean = false,
    val targetC: Double? = null,
    val flow: Int? = null,
    val presoakS: Int? = null,
    val standstillS: Int? = null,
    val extractionRaw: Int? = null,
    val extractionS: Double? = null,
    val cupsToday: Int? = null,
    val cupsWeek: Int? = null,
    val cupsMonth: Int? = null,
    val cupsYear: Int? = null,
    val cupsAsOf: LocalDate? = null,
    val daysStored: Int = 0,
    val reservationEnabled: Boolean? = null,
    val reservationMode: String? = null,
    val reservationTime: String? = null,
    val reservationAlarm: Boolean? = null,
    val reservationBeep: String? = null,
    val reservationNext: LocalDateTime? = null,
)

/** One logged Bluetooth frame (enough for the developer view). */
data class FrameView(val direction: String, val hex: String, val seq: Long)

/** One bar of the cups chart, like the web UI's `/api/stats` bars. */
data class PeriodBar(
    val label: String,
    val name: String,
    val start: LocalDate,
    val cups: Int?,
    val complete: Boolean,
    val future: Boolean,
)

/** One chart window: a week, a month or a year of cup history. */
data class StatsPeriod(
    val period: String,
    val offset: Int,
    val title: String,
    val total: Int,
    val partial: Boolean,
    val canPrev: Boolean,
    val canNext: Boolean,
    val oldest: LocalDate,
    val bars: List<PeriodBar>,
)

class MachineService(
    private val scope: CoroutineScope,
    /** Builds a CeraClient for a given machine address, wiring the logging
     *  callbacks into this service (which is how frames reach the UI). */
    private val newClient: (address: String) -> CeraClient,
    private val scanner: suspend (Long) -> List<Found>,
) {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _frames = MutableStateFlow<List<FrameView>>(emptyList())
    val frames: StateFlow<List<FrameView>> = _frames.asStateFlow()

    private val mutex = Mutex()
    private var client: CeraClient? = null
    private var frameSeq = 0L

    private var stats: Stats? = null
    private var statsDate: LocalDate? = null
    private var reservation: com.ceraopen.android.cera.Reservation? = null

    /** Whether the machine's write commands may be sent (the control lock). */
    private var controlEnabled = false
    private var wasRunning = false
    private var rereadJob: Job? = null

    // -- frames ------------------------------------------------------------

    fun reportFrame(direction: String, raw: ByteArray) {
        frameSeq++
        _frames.value = (_frames.value + FrameView(direction, raw.hexSpaced(), frameSeq)).takeLast(400)
    }

    // -- state -------------------------------------------------------------

    /** Re-derive the snapshot from the client and publish it. */
    fun publish() {
        val c = client
        val connected = c?.isConnected == true
        val status = if (connected) c?.status else null
        val settings = if (connected) c?.settings else null
        val info = if (connected) c?.info else null
        val day = statsDate
        _state.value = _state.value.copy(
            connected = connected,
            controlEnabled = controlEnabled && connected,
            model = info?.model,
            versionA = info?.versionA,
            versionB = info?.versionB,
            waterTempC = status?.waterTempC,
            batteryRaw = status?.batteryRaw,
            stateText = status?.state?.let { State.from(it)?.name ?: "UNKNOWN_$it" } ?: "IDLE",
            running = status != null && status.state != 0,
            targetC = if (connected) c?.targetC else null,
            flow = settings?.flow,
            presoakS = settings?.presoakS,
            standstillS = settings?.standstillS,
            extractionRaw = settings?.extractionRaw,
            extractionS = settings?.extractionSeconds,
            cupsToday = stats?.today,
            cupsWeek = stats?.let { if (day != null) it.week(day) else null },
            cupsMonth = stats?.let { if (day != null) it.month(day) else null },
            cupsYear = stats?.let { if (day != null) it.year(day) else null },
            cupsAsOf = day,
            daysStored = stats?.daily?.size ?: 0,
            reservationEnabled = reservation?.enabled,
            reservationMode = reservation?.mode?.let { m ->
                ReservationMode.entries.firstOrNull { it.value == m }?.name ?: "UNKNOWN_$m"
            },
            reservationTime = reservation?.time,
            reservationAlarm = reservation?.alarm,
            reservationBeep = reservation?.beep?.let { b ->
                Beep.entries.firstOrNull { it.value == b }?.name ?: "UNKNOWN_$b"
            },
            reservationNext = reservation?.takeIf { it.enabled }?.nextRun(LocalDateTime.now()),
        )
    }

    /** Client callback: a heartbeat arrived (any thread). */
    fun onStatus(status: Status) {
        val running = status.state != State.IDLE.value
        if (wasRunning && !running) {
            // A run just ended: the cup count went up and a reservation that
            // ran has switched itself off. Read both again after a moment.
            rereadJob?.cancel()
            rereadJob = scope.launch { rereadAfterRun() }
        }
        wasRunning = running
        publish()
    }

    /** Client callback: the handed-out connection dropped (any thread). */
    fun onLost() {
        wasRunning = false
        controlEnabled = false
        publish()
    }

    private suspend fun rereadAfterRun() {
        delay(REREAD_DELAY_MS)
        mutex.withLock {
            val c = client
            if (c?.isConnected == true) {
                readStats(c)
                readReservation(c)
            }
        }
        publish()
    }

    private suspend fun readStats(c: CeraClient) {
        // A failure here never breaks the connection, like the web service.
        try {
            stats = c.getStats()
            statsDate = LocalDate.now()
        } catch (_: Exception) {
        }
    }

    private suspend fun readReservation(c: CeraClient) {
        try {
            reservation = c.getReservation()
        } catch (_: Exception) {
        }
    }

    // -- connection --------------------------------------------------------

    suspend fun scan(timeoutMs: Long): List<Found> = scanner(timeoutMs)

    suspend fun connect(address: String) {
        mutex.withLock {
            if (client?.isConnected == true) client?.disconnect()
            // Nothing read from a previous machine may be shown for this one.
            stats = null
            statsDate = null
            reservation = null
            wasRunning = false
            controlEnabled = false
            _state.value = ServiceState(connecting = true, address = address)
            val nc = newClient(address)
            client = nc
            try {
                nc.connect()
                readStats(nc)
                readReservation(nc)
            } finally {
                _state.value = _state.value.copy(connecting = false)
                publish()
            }
        }
    }

    suspend fun disconnect() {
        mutex.withLock {
            client?.disconnect()
            controlEnabled = false
            wasRunning = false
            _state.value = _state.value.copy(connected = false, controlEnabled = false)
        }
    }

    fun setControl(enabled: Boolean) {
        controlEnabled = enabled
        publish()
    }

    private fun requireClient(write: Boolean): CeraClient {
        val c = client
        if (c == null || !c.isConnected) throw CeraException("not connected")
        if (write && !controlEnabled) {
            throw ControlLockedException("control is off: enable it before sending commands")
        }
        return c
    }

    suspend fun refresh() {
        mutex.withLock {
            val c = requireClient(write = false)
            c.getSettings()
            readStats(c)
            readReservation(c)
        }
        publish()
    }

    // -- commands ----------------------------------------------------------

    suspend fun setTargetC(celsius: Double) {
        mutex.withLock { requireClient(write = true).setTargetTempC(celsius) }
        publish()
    }

    suspend fun updateSettings(fields: Map<String, Int>) {
        mutex.withLock {
            requireClient(write = true).updateSettings(
                fields["flow"], fields["presoak_s"], fields["standstill_s"], fields["extraction_raw"],
            )
        }
        publish()
    }

    suspend fun start(mode: BrewMode) {
        mutex.withLock { requireClient(write = true).start(mode) }
        publish()
    }

    suspend fun stop() {
        // Stop is always allowed while connected, even with control off.
        mutex.withLock { requireClient(write = false).stop() }
        publish()
    }

    suspend fun setReservation(mode: ReservationMode, time: String, alarm: Boolean, beep: Beep) {
        val parts = time.split(":")
        require(parts.size == 2) { "time must be HH:MM" }
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        require(hour in 0..23 && minute in 0..59) { "time must be 00:00-23:59" }
        mutex.withLock {
            reservation = requireClient(write = true).setReservation(mode, hour, minute, alarm, beep)
        }
        publish()
    }

    suspend fun cancelReservation() {
        // Like STOP, cancelling only removes risk, so control may stay off.
        mutex.withLock { reservation = requireClient(write = false).cancelReservation() }
        publish()
    }

    /** Stop anything running, then drop the link (used when the app closes). */
    suspend fun shutdown() {
        if (_state.value.running) {
            try {
                stop()
            } catch (_: Exception) {
            }
        }
        try {
            disconnect()
        } catch (_: Exception) {
        }
    }

    // -- cups chart --------------------------------------------------------

    /**
     * Chart data for one week, month or year of the cup history.
     * `offset` 0 is the current period, -1 the one before, and so on.
     */
    fun statsPeriod(period: String, offset: Int): StatsPeriod {
        val s = stats ?: throw CeraException("no cup stats yet: connect to the machine first")
        val day = statsDate ?: throw CeraException("no cup stats yet: connect to the machine first")
        val off = minOf(offset, 0)
        val buckets: List<Bucket> = when (period) {
            "week" -> s.weekSeries(day, off)
            "month" -> s.monthSeries(day, off)
            "year" -> s.yearSeries(day, off)
            else -> throw IllegalArgumentException("period must be week, month or year")
        }
        val first = buckets.first().start
        val last = buckets.last().start
        val title: String
        val labels: List<String>
        val names: List<String>
        when (period) {
            "week" -> {
                title = "${first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} – " +
                    last.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                labels = buckets.map { it.start.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)) }
                names = buckets.map { it.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)) }
            }
            "month" -> {
                title = first.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
                labels = buckets.map { it.start.dayOfMonth.toString() }
                names = buckets.map { it.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)) }
            }
            else -> {
                title = first.year.toString()
                labels = buckets.map { it.start.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)) }
                names = buckets.map { it.start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) }
            }
        }
        val previous = when (period) {
            "week" -> s.weekSeries(day, off - 1)
            "month" -> s.monthSeries(day, off - 1)
            else -> s.yearSeries(day, off - 1)
        }
        return StatsPeriod(
            period = period,
            offset = off,
            title = title,
            total = buckets.sumOf { it.cups ?: 0 },
            partial = buckets.any { !it.complete },
            canPrev = previous.any { it.cups != null },
            canNext = off < 0,
            oldest = s.oldest(day),
            bars = buckets.mapIndexed { i, b ->
                PeriodBar(
                    label = labels[i],
                    name = names[i],
                    start = b.start,
                    cups = b.cups,
                    complete = b.complete,
                    future = b.start.isAfter(day),
                )
            },
        )
    }

    private companion object {
        /** Delay after a run ends before the cup counts are read again. */
        const val REREAD_DELAY_MS = 1_000L
    }
}
