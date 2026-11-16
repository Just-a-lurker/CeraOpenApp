// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from CeraLib (MIT) cera/client.py. Read-only use is verified on a
// PCM03SMAX by CeraLib's author; the framing and retry behaviour are kept
// byte- and timing-compatible with the Python original.
//
// The machine sometimes ignores every command right after connecting, or the
// link drops within seconds (about 1 connection in 6, and bad connections
// come in runs). Like CeraLib, this client waits before subscribing and
// makes several attempts, each with a fresh [BleLink], pausing a little
// longer between them. A failed connect closes the link before raising and
// raises only [CeraException].
package com.ceraopen.android.cera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

// Observed range only: the app offered 90-95 C in captures. Not a machine limit.
const val MIN_TARGET_C = 60.0
const val MAX_TARGET_C = 95.0

open class CeraException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class CommandRejected(message: String) : CeraException(message)

class CeraClient(
    /** Builds a fresh link per connection attempt (like CeraLib per-attempt
     *  bleak clients; Android only recovers with a brand-new GATT link). */
    private val newLink: () -> BleLink,
    /** Called for every heartbeat while connected (any thread). */
    private val onStatus: ((Status) -> Unit)? = null,
    /** Sees every frame, ("TX"|"RX", raw), for logging (any thread). */
    private val onFrame: ((String, ByteArray) -> Unit)? = null,
    /** Called when a handed-out connection drops (any thread, once). */
    private val onDisconnect: (() -> Unit)? = null,
) {
    // Timing knobs; internal so tests can shrink them, like the Python
    // tests patch the module constants.
    internal var settleAfterConnectMs = 4_000L   // wait before subscribing (measured)
    internal var preHandshakePauseMs = 1_000L    // the vendor app pauses ~0.7 s
    internal var retryPauseMs = 2_000L           // between connection attempts
    internal var replyTimeoutMs = 500L           // the vendor app re-sends after ~0.4 s
    internal var statsTimeoutMs = 3_000L         // the stats table arrives in ~21 fragments
    internal var heartbeatWaitMs = 1_000L        // per status request
    internal var heartbeatRequests = 3
    internal var retries = 2

    private var link: BleLink? = null
    private var ready = false          // true between a finished handshake and the drop
    private var reasm = Reassembler()

    var status: Status? = null
        private set
    var info: DeviceInfo? = null
        private set
    var settings: Settings? = null
        private set
    var targetF: Int? = null
        private set

    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    private val cmdLocks = ConcurrentHashMap<Int, Mutex>()

    val isConnected: Boolean get() = link?.isConnected == true

    val targetC: Double?
        get() = targetF?.let { Math.round(fToC(it) * 10.0) / 10.0 }

    /** Forget everything learned from the previous connection. */
    private fun reset() {
        reasm = Reassembler()
        status = null
        info = null
        settings = null
        targetF = null
        waiters.clear()
    }

    private fun lost(l: BleLink) {
        // Failed attempts and replaced links are not a lost session: only
        // report the drop of a connection that was handed out.
        if (l !== link || !ready) return
        ready = false
        onDisconnect?.invoke()
        // Release the native handle: an unexpected drop otherwise leaks the
        // GATT object (Android only recovers with a brand-new link anyway).
        l.close()
    }

    // -- connection ------------------------------------------------------

    /**
     * Connect and run the handshake, reconnecting if the machine stays
     * silent. Whatever goes wrong, the link is closed before this raises,
     * and only [CeraException] is raised.
     */
    suspend fun connect(attempts: Int = 4) {
        link?.let { closeQuietly(it) }
        for (attempt in 0 until attempts) {
            // Every attempt builds a fresh link: on Android, retrying on the
            // same GATT object does not recover, a new one does.
            reset()
            val l = newLink()
            l.onNotification = ::handleRx
            l.onLost = { lost(l) }
            link = l
            var ok = false
            try {
                connectOnce(l)
                ok = true
                ready = true
                return
            } catch (e: TimeoutCancellationException) {
                if (attempt == attempts - 1) throw CeraException("connect failed: no reply in time")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // A reply that does not parse (another model?) will not get
                // better by reconnecting.
                throw CeraException("unexpected reply during the handshake: ${e.message}")
            } catch (e: IndexOutOfBoundsException) {
                throw CeraException("unexpected reply during the handshake: ${e.message}")
            } catch (e: CeraException) {
                if (attempt == attempts - 1) throw e
            } catch (e: Exception) {
                // Do not leak platform (OS/Bluetooth) exceptions to apps.
                if (attempt == attempts - 1) throw CeraException("connect failed: $e", e)
            } finally {
                if (!ok) {
                    ready = false
                    closeQuietly(l)
                }
            }
            // Bad connections come in runs (two in a row were seen even 8 s
            // apart), so each further attempt waits a little longer.
            delay(retryPauseMs * (attempt + 1))
        }
    }

    private suspend fun closeQuietly(l: BleLink) {
        ready = false
        try {
            l.disconnect()
        } catch (_: Exception) {
        }
    }

    private suspend fun connectOnce(l: BleLink) {
        l.connect()
        // Measured on Windows: if commands follow the connection too quickly
        // the machine often stays silent; after a 4 s wait it answered. The
        // link sometimes drops during this wait: stop at once.
        var waited = 0L
        while (waited < settleAfterConnectMs) {
            val step = minOf(200L, settleAfterConnectMs - waited)
            delay(step)
            waited += step
            if (!l.isConnected) throw CeraException("the connection dropped right after connecting")
        }
        l.startNotify()
        delay(preHandshakePauseMs)
        // Handshake as the vendor app does it: clock, limits, settings,
        // info, then an empty status request which starts the heartbeat.
        val now = LocalDateTime.now()
        request(CMD_SET_CLOCK, clockPayload(
            now.year, now.monthValue, now.dayOfMonth, now.dayOfWeek.value % 7,
            now.hour, now.minute, now.second))
        val limits = request(CMD_LIMITS).payload
        if (limits.size >= 3) {
            targetF = limits[limits.size - 1].unsigned() // inferred: last byte tracks the target
        }
        getSettings()
        info = DeviceInfo.parse(request(CMD_INFO).payload)
        // The empty status request has no direct reply: it just starts the
        // ~1 Hz heartbeat. Send it and wait for the first heartbeat; frames
        // do get lost, so ask again rather than give up the whole link.
        repeat(heartbeatRequests) {
            send(l, encode(CMD_STATUS))
            repeat(maxOf(1, (heartbeatWaitMs / 100).toInt())) {
                delay(100)
                if (status != null) return
            }
        }
        throw CeraException("no heartbeat after status request")
    }

    suspend fun disconnect() {
        val l = link ?: return
        try {
            if (l.isConnected) l.disconnect()
        } finally {
            // The link normally reports the drop itself; make sure it is
            // reported exactly once even if it does not.
            lost(l)
        }
    }

    // -- frames ----------------------------------------------------------

    /** Raw notification handler: any thread, in arrival order. */
    private fun handleRx(data: ByteArray) {
        onFrame?.invoke("RX", data)
        val frame = try {
            val f = decode(data)
            if (f.direction != FROM_MACHINE) return
            reasm.feed(f) ?: return
        } catch (_: FrameError) {
            return // drop corrupt frames; a retry will recover
        }
        if (frame.cmd == CMD_STATUS && frame.payload.size >= 6) {
            status = Status.parse(frame.payload)
            // Heartbeats during the handshake are not reported: the
            // connection has not been handed to the caller yet.
            if (ready) onStatus?.invoke(status!!)
        }
        waiters[frame.cmd]?.complete(frame)
    }

    private suspend fun send(l: BleLink, data: ByteArray) {
        onFrame?.invoke("TX", data)
        l.write(data) // the link wraps platform errors in CeraException
    }

    /**
     * Send a command and return the reply with the same command byte.
     * Replies are matched by command byte only, so calls with the same
     * command take turns; different commands may overlap.
     */
    suspend fun request(cmd: Int, payload: ByteArray = ByteArray(0),
                       timeoutMs: Long = replyTimeoutMs): Frame {
        val data = encode(cmd, payload)
        val lock = cmdLocks.getOrPut(cmd) { Mutex() }
        return lock.withLock {
            repeat(retries + 1) {
                val waiter = CompletableDeferred<Frame>()
                waiters[cmd] = waiter
                try {
                    val l = link ?: throw CeraException("not connected")
                    send(l, data)
                    withTimeout(timeoutMs) { waiter.await() }
                } catch (_: TimeoutCancellationException) {
                    // fall through and re-send, like the vendor app
                    null
                } finally {
                    waiters.remove(cmd)
                }?.let { return@withLock it }
            }
            throw CeraException("no reply to command 0x%02x".format(cmd))
        }
    }

    private suspend fun setVerified(cmd: Int, payload: ByteArray) {
        val reply = request(cmd, payload)
        val ok = if (cmd == CMD_TARGET_TEMP) payload else byteArrayOf(STATUS_OK.toByte())
        if (!reply.payload.contentEquals(ok)) {
            throw CommandRejected("0x%02x -> ${reply.payload.hexSpaced()}".format(cmd))
        }
    }

    // -- settings --------------------------------------------------------

    suspend fun setTargetTempC(celsius: Double) {
        if (celsius !in MIN_TARGET_C..MAX_TARGET_C) {
            throw IllegalArgumentException("target must be $MIN_TARGET_C-$MAX_TARGET_C C")
        }
        val payload = targetPayload(celsius)
        setVerified(CMD_TARGET_TEMP, payload)
        targetF = payload[0].unsigned()
    }

    suspend fun getSettings(): Settings {
        settings = Settings.parse(request(CMD_GET_SETTINGS).payload)
        return settings!!
    }

    /** Read-modify-write, like the vendor app (which also re-sends the
     *  target temperature before each write; this does not). */
    suspend fun updateSettings(flow: Int? = null, presoakS: Int? = null,
                               standstillS: Int? = null, extractionRaw: Int? = null): Settings {
        if (flow != null && flow !in FLOW_MIN..FLOW_MAX) {
            throw IllegalArgumentException("flow must be $FLOW_MIN-$FLOW_MAX")
        }
        val cur = getSettings()
        val new = Settings(cur.raw, flow ?: cur.flow, presoakS ?: cur.presoakS,
            standstillS ?: cur.standstillS, extractionRaw ?: cur.extractionRaw)
        setVerified(CMD_SET_SETTINGS, new.writePayload())
        return getSettings()
    }

    // -- statistics ------------------------------------------------------

    /** Read the per-day cup counts. Read-only. The reply arrives in about
     *  21 notifications (~0.7 s), hence the longer timeout. */
    suspend fun getStats(): Stats {
        val frame = request(CMD_STATS, byteArrayOf(0x00), statsTimeoutMs)
        return Stats.parse(frame.payload)
    }

    // -- reservations ------------------------------------------------------

    /** Read the reservation stored on the machine (read-only). */
    suspend fun getReservation(): Reservation =
        Reservation.parse(request(CMD_GET_RESERVATION).payload)

    /**
     * Schedule a start. WARNING: the machine will then heat and/or extract
     * BY ITSELF at hour:minute (its own clock, set on connect), even with no
     * app connected. It runs once. `alarm` and `beep` default to what is
     * already stored, so the vendor app keeps the user's reminder choice.
     */
    suspend fun setReservation(mode: ReservationMode, hour: Int, minute: Int,
                               alarm: Boolean? = null, beep: Beep? = null): Reservation {
        val current = getReservation()
        val new = Reservation(mode.value, hour, minute, alarm ?: current.alarm,
            beep?.value ?: current.beep, true)
        setVerified(CMD_SET_RESERVATION, new.payload())
        return getReservation()
    }

    /** Switch the stored reservation off (the vendor app resends it with
     *  the last byte 0). Always safe to call. */
    suspend fun cancelReservation(): Reservation {
        val current = getReservation()
        val knownMode = ReservationMode.entries.firstOrNull { it.value == current.mode }?.value ?: 0
        val off = Reservation(knownMode, current.hour, current.minute,
            current.alarm, current.beep, false)
        setVerified(CMD_SET_RESERVATION, off.payload())
        return getReservation()
    }

    // -- brewing: manual starts are explicit calls (reservations above start
    //    the machine by itself at the scheduled time) ------------------------

    suspend fun start(mode: BrewMode) {
        setVerified(CMD_BREW, brewPayload(mode, true))
    }

    /** Stop whatever is running; if the state is unknown, stop every mode. */
    suspend fun stop() {
        val state = status?.state ?: -1
        val modes = State.from(state)?.let { s -> listOfNotNull(STATE_TO_MODE[s]) }
            ?: BrewMode.entries.toList()
        for (mode in modes) {
            setVerified(CMD_BREW, brewPayload(mode, false))
        }
    }
}
