// SPDX-License-Identifier: GPL-3.0-or-later
// Sample data for looking at the dashboard without a machine attached.
// Only reachable in debuggable builds, from:
//   adb shell am start -n com.ceraopen.android/.MainActivity --ez demo true
package com.ceraopen.android

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A plausible connected machine: warm, idle, unlocked, with a week of cups.
 * Every figure here is invented; it exists so the layout can be reviewed on
 * an emulator, which has no Bluetooth.
 */
object DemoMode {

    private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
    private val RANGE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    private val DAILY = intArrayOf(2, 3, 1, 4, 2)
    private val MONTHLY = intArrayOf(18, 21, 24, 19, 26, 22, 25, 28, 24, 27, 20, 12)

    /**
     * Cups on a given day. A pure function of the date, so the week chart and
     * the month chart cannot disagree about the same day.
     */
    private fun cupsOn(day: LocalDate): Int =
        DAILY[(day.dayOfMonth * 7 + day.monthValue) % DAILY.size]

    fun state(
        today: LocalDate = LocalDate.now(),
        running: Boolean = false,
        locked: Boolean = false,
    ): ServiceState {
        // The stat strip and the charts come from one machine, so they must
        // agree: derive the totals from the same bars the chart draws.
        val week = week(today)
        val month = month(today)
        val year = year(today)
        return ServiceState(
            connected = true,
            connecting = false,
            address = "E4:AA:5D:11:22:33",
            controlEnabled = !locked,
            model = "PCM03SMAX",
            versionA = "0.0.3",
            versionB = "0.0.9",
            waterTempC = 92,
            batteryRaw = 78,
            stateText = if (running) "EXTRACT" else "IDLE",
            running = running,
            targetC = 94.0,
            flow = 8,
            presoakS = 2,
            standstillS = 3,
            extractionRaw = 120,
            extractionS = 120.0,
            cupsToday = week.bars.lastOrNull { it.cups != null }?.cups,
            cupsWeek = week.total,
            cupsMonth = month.total,
            cupsYear = year.total,
            cupsAsOf = today,
            daysStored = 365,
            reservationEnabled = true,
            reservationMode = "HEAT_AND_EXTRACT",
            reservationTime = "07:00",
            reservationAlarm = true,
            reservationBeep = "DING_DONG",
            reservationNext = today.plusDays(1).atTime(7, 0),
        )
    }

    /** The current week, Sunday to Saturday, with the days after today empty. */
    fun week(today: LocalDate = LocalDate.now()): StatsPeriod {
        val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
        val bars = (0 until 7).map { i ->
            val day = sunday.plusDays(i.toLong())
            val future = day.isAfter(today)
            PeriodBar(
                label = day.format(DAY),
                name = day.format(DAY_LONG),
                start = day,
                cups = if (future) null else cupsOn(day),
                complete = true,
                future = future,
            )
        }
        val last = sunday.plusDays(6)
        return StatsPeriod(
            period = "week",
            offset = 0,
            title = "${sunday.format(RANGE)} – ${last.format(RANGE)}, ${last.year}",
            total = bars.sumOf { it.cups ?: 0 },
            partial = false,
            canPrev = true,
            canNext = false,
            oldest = today.minusDays(364),
            bars = bars,
        )
    }

    /** The cups window the chart asks for, with plausible bars. */
    fun period(p: String, today: LocalDate = LocalDate.now()): StatsPeriod = when (p) {
        "month" -> month(today)
        "year" -> year(today)
        else -> week(today)
    }

    private fun month(today: LocalDate): StatsPeriod {
        val first = today.withDayOfMonth(1)
        val days = today.lengthOfMonth()
        val bars = (1..days).map { d ->
            val day = first.withDayOfMonth(d)
            val future = day.isAfter(today)
            PeriodBar(
                label = d.toString(),
                name = day.format(DAY_LONG),
                start = day,
                cups = if (future) null else cupsOn(day),
                complete = true,
                future = future,
            )
        }
        return StatsPeriod(
            period = "month",
            offset = 0,
            title = today.format(MONTH_TITLE),
            total = bars.sumOf { it.cups ?: 0 },
            partial = false,
            canPrev = true,
            canNext = false,
            oldest = today.minusDays(364),
            bars = bars,
        )
    }

    private fun year(today: LocalDate): StatsPeriod {
        // The current month is partial, so its bar is the month-to-date figure
        // the month chart shows, not a whole-month number from the table.
        val thisMonth = month(today).total
        val bars = (1..12).map { m ->
            val day = LocalDate.of(today.year, m, 1)
            val future = m > today.monthValue
            PeriodBar(
                label = day.format(MONTH_SHORT),
                name = day.format(MONTH_TITLE),
                start = day,
                cups = when {
                    future -> null
                    m == today.monthValue -> thisMonth
                    else -> MONTHLY[m - 1]
                },
                complete = true,
                future = future,
            )
        }
        return StatsPeriod(
            period = "year",
            offset = 0,
            title = today.year.toString(),
            total = bars.sumOf { it.cups ?: 0 },
            partial = false,
            canPrev = true,
            canNext = false,
            oldest = today.minusDays(364),
            bars = bars,
        )
    }

    /** A short frame log, like the one a live session produces. */
    fun frames(): List<FrameView> = listOf(
        "TX" to "7f 00 06 16 cb",
        "RX" to "7e 00 06 16 cb",
        "RX" to "7e 00 0a 01 3c 03 5c 00 00",
        "TX" to "7f 00 06 1f 01",
        "RX" to "7e 00 0c 2a 00 00 0a 00 00 0a 78 1e",
        "RX" to "7e 00 0a 01 3c 03 5c 00 00",
    ).mapIndexed { i, (dir, hex) -> FrameView(dir, hex, i.toLong() + 1) }
}
