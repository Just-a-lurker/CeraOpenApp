// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from CeraLib (MIT) cera/messages.py: typed views of the commands
// seen in captures of a PCM03SMAX. Uses java.time (minSdk 26).
package com.ceraopen.android.cera

import java.time.LocalDate
import java.time.LocalDateTime

// Command bytes.
const val CMD_STATUS = 0x00         // heartbeat (machine pushes ~1/s); app sends it empty on connect
const val CMD_BREW = 0x02            // payload [mode, on]
const val CMD_SET_CLOCK = 0x04       // payload [year-2020, month, day, weekday, hour, minute, second]
const val CMD_STATS = 0x06           // cup counts per day; request payload [0x00]; reply in ~21 fragments
const val CMD_INFO = 0x08            // device info, fragmented
const val CMD_LIMITS = 0x15          // reply [0x64?, 0xCC, target_F]; layout partly inferred
const val CMD_TARGET_TEMP = 0x16     // payload [target in degrees Fahrenheit]; reply echoes it
const val CMD_GET_SETTINGS = 0x17    // request empty, reply 12-byte settings block
const val CMD_SET_SETTINGS = 0x18    // payload [flow, presoak_s, standstill_s, extraction_raw]
const val CMD_SET_RESERVATION = 0x23 // payload = Reservation (6 bytes); reply 01
const val CMD_GET_RESERVATION = 0x24 // request empty; reply = Reservation (6 bytes)

const val STATUS_OK = 0x01           // reply payload for accepted set commands (confirmed)

/** Heartbeat state byte (from brew-mode captures). */
enum class State(val value: Int) {
    IDLE(0),
    HEAT_AND_EXTRACT(1),
    EXTRACT(3),
    HEAT(5);

    companion object {
        fun from(value: Int): State? = entries.firstOrNull { it.value == value }
    }
}

/** First payload byte of CMD_BREW (confirmed). */
enum class BrewMode(val value: Int) {
    HEAT(0),
    HEAT_AND_EXTRACT(1),
    EXTRACT(2);
}

/**
 * Mode byte of a reservation (confirmed). Numbered differently from
 * BrewMode: here 0 is heat & extract and 1 is heating.
 */
enum class ReservationMode(val value: Int) {
    HEAT_AND_EXTRACT(0),
    HEAT(1),
    EXTRACT(2);
}

/**
 * Reminder sound chosen in the vendor app (confirmed values). The phone
 * plays it, not the machine; the machine only stores the choice.
 */
enum class Beep(val value: Int) {
    DING_DONG(0),
    DI_DI(1),
    BU_GU(2),
    BI_BI(3);
}

/** Heartbeat state while running -> the CMD_BREW mode that stops it (inferred). */
val STATE_TO_MODE: Map<State, BrewMode> = mapOf(
    State.HEAT to BrewMode.HEAT,
    State.HEAT_AND_EXTRACT to BrewMode.HEAT_AND_EXTRACT,
    State.EXTRACT to BrewMode.EXTRACT,
)

// Flow is the vendor app's slider: whole steps from 1 to 15, middle 8 is the
// default (confirmed: min=1, min+1=2, middle=8, max-1=14, max=15).
const val FLOW_MIN = 1
const val FLOW_MAX = 15
const val FLOW_DEFAULT = 8

fun cToF(celsius: Double): Int = Math.round(celsius * 9.0 / 5.0 + 32.0).toInt()

fun fToC(fahrenheit: Int): Double = (fahrenheit - 32) * 5.0 / 9.0

/** CMD_STATUS heartbeat payload: `01 <b> 03 <temp> <state> 00`. */
data class Status(
    val raw: ByteArray,
    val batteryRaw: Int,     // inferred: 0x3C (60) while the app showed 3 of 4 bars
    val waterTempC: Int,     // confirmed (rises while heating); degrees Celsius
    val state: Int,          // see State; unknown values are kept as ints
) {
    companion object {
        fun parse(payload: ByteArray): Status {
            require(payload.size >= 6) { "status payload too short: ${payload.hexSpaced()}" }
            return Status(payload.copyOf(), payload[1].unsigned(), payload[3].unsigned(),
                payload[4].unsigned())
        }
    }
}

/** CMD_GET_SETTINGS reply (12 bytes). The write layout (4 bytes) differs. */
data class Settings(
    val raw: ByteArray,
    val flow: Int,           // confirmed: slider step FLOW_MIN..FLOW_MAX (1-15), default 8
    val presoakS: Int,       // confirmed: presoak seconds
    val standstillS: Int,    // confirmed: standstill seconds
    val extractionRaw: Int,  // confirmed to change with extraction time
) {
    companion object {
        fun parse(payload: ByteArray): Settings {
            require(payload.size == 12) { "settings payload must be 12 bytes, got ${payload.size}" }
            return Settings(payload.copyOf(), payload[0].unsigned(), payload[1].unsigned(),
                payload[4].unsigned(), payload[7].unsigned())
        }
    }

    /** INFERRED from two points only: raw 120 -> 120 s, raw 140 -> 130 s. */
    val extractionSeconds: Double get() = extractionRaw / 2.0 + 60.0

    fun writePayload(): ByteArray = byteArrayOf(flow.toByte(), presoakS.toByte(),
        standstillS.toByte(), extractionRaw.toByte())
}

data class DeviceInfo(
    val versionA: String,   // "0.0.3" in the capture (hardware or firmware; unresolved)
    val versionB: String,  // "0.0.9"
    val model: String,     // "PCM03SMAX"
    val tail: ByteArray,
) {
    companion object {
        fun parse(payload: ByteArray): DeviceInfo {
            var pos = 1 // first byte meaning unknown (0x18 in the capture)
            val strings = ArrayList<String>(3)
            repeat(3) {
                val n = payload[pos].toInt() and 0xFF
                strings.add(String(payload, pos + 1, n, Charsets.US_ASCII))
                pos += 1 + n
            }
            return DeviceInfo(strings[0], strings[1], strings[2], payload.copyOfRange(pos, payload.size))
        }
    }
}

/**
 * A scheduled start stored on the machine (CMD_SET/GET_RESERVATION).
 * The machine starts by itself at hour:minute on its own clock, runs once,
 * then switches the reservation off.
 */
data class Reservation(
    val mode: Int,      // ReservationMode
    val hour: Int,
    val minute: Int,
    val alarm: Boolean, // False = silent reminder
    val beep: Int,      // Beep, ignored when alarm is False
    val enabled: Boolean,
) {
    companion object {
        fun parse(payload: ByteArray): Reservation {
            require(payload.size == 6) { "reservation payload must be 6 bytes, got ${payload.size}" }
            return Reservation(payload[0].unsigned(), payload[1].unsigned(), payload[2].unsigned(),
                payload[3].unsigned() != 0, payload[4].unsigned(), payload[5].unsigned() != 0)
        }
    }

    fun payload(): ByteArray {
        require(hour in 0..23 && minute in 0..59) { "time must be 00:00-23:59" }
        require(ReservationMode.entries.any { it.value == mode }) { "unknown reservation mode $mode" }
        require(beep in 0..255) { "beep out of range" }
        return byteArrayOf(mode.toByte(), hour.toByte(), minute.toByte(),
            (if (alarm) 1 else 0).toByte(), beep.toByte(), (if (enabled) 1 else 0).toByte())
    }

    val time: String get() = "%02d:%02d".format(hour, minute)

    /**
     * When it will run: today at hour:minute if still ahead, otherwise
     * tomorrow (both confirmed on a PCM03SMAX: a time already passed today
     * runs the next day).
     */
    fun nextRun(now: LocalDateTime): LocalDateTime {
        val at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (at > now) at else at.plusDays(1)
    }
}

/** One bar of a stats chart: a day (week/month view) or a month (year view). */
data class Bucket(
    val start: LocalDate,
    val cups: Int?,   // null = no data for the whole bucket
    val complete: Boolean,
)

private fun addMonths(year: Int, month: Int, delta: Int): Pair<Int, Int> {
    val index = year * 12 + (month - 1) + delta
    return index / 12 to index % 12 + 1
}

private fun monthDays(year: Int, month: Int): Int = LocalDate.of(year, month, 1).lengthOfMonth()

/**
 * CMD_STATS reply: cups brewed per day, newest first. Confirmed against the
 * vendor app (week, month and year totals match). "Today" is the machine's
 * clock, which the client sets on connect.
 */
class Stats private constructor(
    val header: ByteArray,
    val daily: IntArray,
) {
    companion object {
        fun parse(payload: ByteArray): Stats {
            require(payload.size >= 3) { "stats payload too short: ${payload.size}" }
            return Stats(payload.copyOfRange(0, 2),
                IntArray(payload.size - 2) { payload[it + 2].unsigned() })
        }

        fun of(daily: IntArray): Stats = Stats(byteArrayOf(0x01, 0x39), daily)
    }

    val today: Int get() = daily[0]

    /** All cups in the stored days (not a lifetime total). */
    val total: Int get() = daily.sum()

    fun byDate(today: LocalDate): Map<LocalDate, Int> =
        daily.withIndex().associate { (i, n) -> today.minusDays(i.toLong()) to n }

    private fun last(days: Int): Int = daily.take(days).sum()

    /**
     * Cups since the start of this week. `weekStart` uses Python's weekday
     * numbers: 6 = Sunday (default, like the vendor app, whose week runs
     * Sunday to Saturday), 0 = Monday.
     */
    fun week(today: LocalDate, weekStart: Int = 6): Int {
        val py = today.dayOfWeek.value - 1 // Python weekday: Monday=0 .. Sunday=6
        return last(((py - weekStart) % 7 + 7) % 7 + 1)
    }

    fun month(today: LocalDate): Int = last(today.dayOfMonth)

    /** Cups since 1 January, limited to the days the machine stores. */
    fun year(today: LocalDate): Int = last(today.dayOfYear)

    fun oldest(today: LocalDate): LocalDate = today.minusDays((daily.size - 1).toLong())

    /** Cups on date; null if it is in the future or not stored. */
    fun day(today: LocalDate, date: LocalDate): Int? {
        val i = today.toEpochDay() - date.toEpochDay()
        if (i < 0 || i >= daily.size) return null
        return daily[i.toInt()]
    }

    private fun bucket(today: LocalDate, start: LocalDate, days: Int): Bucket {
        var sum = 0
        var known = false
        var missing = false
        for (k in 0 until days) {
            val d = start.plusDays(k.toLong())
            if (d > today) continue // not happened yet: neither data nor missing
            when (val n = day(today, d)) {
                null -> missing = true
                else -> { known = true; sum += n }
            }
        }
        return Bucket(start, if (known) sum else null, !missing)
    }

    /** Seven day buckets for the week `offset` weeks from this one (0 = this
     * week, -1 = last week). Sunday start by default. */
    fun weekSeries(today: LocalDate, offset: Int = 0, weekStart: Int = 6): List<Bucket> {
        val py = today.dayOfWeek.value - 1 // Python weekday: Monday=0 .. Sunday=6
        val daysBack = (((py - weekStart) % 7 + 7) % 7).toLong() - 7L * offset
        val start = today.minusDays(daysBack)
        return (0 until 7).map { bucket(today, start.plusDays(it.toLong()), 1) }
    }

    /** One bucket per date of the month `offset` months from this one. */
    fun monthSeries(today: LocalDate, offset: Int = 0): List<Bucket> {
        val (y, m) = addMonths(today.year, today.monthValue, offset)
        val first = LocalDate.of(y, m, 1)
        return (0 until monthDays(y, m)).map { bucket(today, first.plusDays(it.toLong()), 1) }
    }

    /** Twelve month buckets for the year `offset` years from this one. */
    fun yearSeries(today: LocalDate, offset: Int = 0): List<Bucket> {
        val y = today.year + offset
        return (1..12).map { mo ->
            bucket(today, LocalDate.of(y, mo, 1), monthDays(y, mo))
        }
    }
}

/** `weekday` follows the capture: Saturday 2026-11-14 was sent as 6. */
fun clockPayload(year: Int, month: Int, day: Int, weekday: Int,
                 hour: Int, minute: Int, second: Int): ByteArray =
    byteArrayOf((year - 2020).toByte(), month.toByte(), day.toByte(), weekday.toByte(),
        hour.toByte(), minute.toByte(), second.toByte())

fun brewPayload(mode: BrewMode, on: Boolean): ByteArray =
    byteArrayOf(mode.value.toByte(), (if (on) 1 else 0).toByte())

fun targetPayload(celsius: Double): ByteArray {
    val f = cToF(celsius)
    require(f in 0..255) { "target out of range" }
    return byteArrayOf(f.toByte())
}
