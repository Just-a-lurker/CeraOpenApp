// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of CeraLib (MIT) tests/test_codec.py, run against the real
// capture vectors.
package com.ceraopen.android.cera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CodecTest {
    private val allFrames = TX + RX + FRAGMENT_RUNS.flatten()

    @Test
    fun everyCapturedFrameValidates() {
        allFrames.forEach { decode(hexBytes(it)) }
    }

    @Test
    fun txRoundtripIsByteIdentical() {
        TX.forEach { h ->
            val f = decode(hexBytes(h))
            assertEquals(TO_MACHINE, f.direction)
            assertEquals(h, encode(f.cmd, f.payload).hexSpaced().replace(" ", ""))
        }
    }

    @Test
    fun rxRoundtripIsByteIdentical() {
        RX.forEach { h ->
            val f = decode(hexBytes(h))
            assertEquals(FROM_MACHINE, f.direction)
            val re = encode(f.cmd, f.payload, f.direction, f.flags).hexSpaced().replace(" ", "")
            assertEquals(h, re)
        }
    }

    @Test
    fun badChecksumAndLengthRejected() {
        val good = hexBytes(TX[0])
        val bad = good.copyOf()
        bad[3] = (bad[3].toInt() xor 1).toByte()
        assertThrows(FrameError::class.java) { decode(bad) }
        assertThrows(FrameError::class.java) { decode(good.copyOfRange(0, good.size - 1)) }
    }

    @Test
    fun fragmentsReassemble() {
        FRAGMENT_RUNS.forEach { run ->
            val r = Reassembler()
            var out: Frame? = null
            run.forEach { out = r.feed(decode(hexBytes(it))) }
            assertNotNull(out)
        }
    }
}

class MessageTest {
    @Test
    fun temperatureConversion() {
        assertEquals(0xCB, cToF(95.0))
        assertEquals(0xC2, cToF(90.0))
        assertEquals(95.0, Math.round(fToC(0xCB)).toDouble(), 0.001)
        assertArrayEquals(byteArrayOf(0xCB.toByte()), targetPayload(95.0))
    }

    @Test
    fun targetFrameMatchesCapture() {
        assertEquals("7f00066616cb", encode(CMD_TARGET_TEMP, targetPayload(95.0)).hexSpaced().replace(" ", ""))
        assertEquals("7f00065d16c2", encode(CMD_TARGET_TEMP, targetPayload(90.0)).hexSpaced().replace(" ", ""))
    }

    @Test
    fun brewFramesMatchCapture() {
        val cases = listOf(
            Triple(BrewMode.EXTRACT, true, "7f00078b020201"),
            Triple(BrewMode.EXTRACT, false, "7f00078a020200"),
            Triple(BrewMode.HEAT_AND_EXTRACT, true, "7f00078a020101"),
            Triple(BrewMode.HEAT_AND_EXTRACT, false, "7f000789020100"),
            Triple(BrewMode.HEAT, true, "7f000789020001"),
            Triple(BrewMode.HEAT, false, "7f000788020000"),
        )
        cases.forEach { (mode, on, want) ->
            assertEquals(want, encode(CMD_BREW, brewPayload(mode, on)).hexSpaced().replace(" ", ""))
        }
    }

    @Test
    fun settingsRoundtrip() {
        val raw = hexBytes("0800000a00000a781efa010f")
        assertEquals(12, raw.size)
        val s = Settings.parse(raw)
        assertEquals("08000078", s.writePayload().hexSpaced().replace(" ", ""))
        assertEquals(120.0, s.extractionSeconds, 0.001)
        assertEquals(130.0, Settings.parse(hexBytes("0800000a00000a8c1efa010f")).extractionSeconds, 0.001)
    }

    @Test
    fun flowRange() {
        assertEquals(Triple(1, 8, 15), Triple(FLOW_MIN, FLOW_DEFAULT, FLOW_MAX))
        val flows = listOf(1 to "01", 2 to "02", 8 to "08", 14 to "0e", 15 to "0f")
        flows.forEach { (flow, hexed) ->
            val s = Settings.parse(hexBytes(hexed + "00000a00000a781efa010f"))
            assertEquals(flow, s.flow)
            assertEquals(hexed + "000078", s.writePayload().hexSpaced().replace(" ", ""))
        }
    }

    @Test
    fun statsParse() {
        val run = FRAGMENT_RUNS.first { decode(hexBytes(it[0])).cmd == CMD_STATS }
        val r = Reassembler()
        var out: Frame? = null
        run.forEach { out = r.feed(decode(hexBytes(it))) }
        val s = Stats.parse(out!!.payload)
        val today = LocalDate.of(2026, 11, 14) // Saturday, the capture date
        assertEquals(313, s.daily.size)
        assertEquals(1, s.today)
        assertEquals(2, s.week(today))               // default: Sunday start, like the app
        assertEquals(2, s.week(today, 6))            // Sunday start
        assertEquals(1, s.week(today, 0))            // Monday start
        assertEquals(3, s.month(today))
        assertEquals(208, s.year(today))
        assertEquals(1, s.byDate(today)[LocalDate.of(2026, 11, 8)])
    }

    @Test
    fun statsPeriods() {
        val today = LocalDate.of(2026, 11, 14)
        val daily = intArrayOf(2, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 5, 1, 1, 1, 1, 1)
        val s = Stats.of(daily)
        assertEquals(LocalDate.of(2026, 10, 26), s.oldest(today))

        val week = s.weekSeries(today)
        assertEquals(LocalDate.of(2026, 11, 8), week[0].start)      // Sunday
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 2), week.map { it.cups })
        val lastWeek = s.weekSeries(today, offset = -1)
        assertEquals(LocalDate.of(2026, 11, 1), lastWeek[0].start)
        assertEquals(s.daily[7] + s.daily.slice(8 until 14).sum(), lastWeek.sumOf { it.cups ?: 0 })

        val nov = s.monthSeries(today)
        assertEquals(30, nov.size)
        assertEquals(s.month(today), nov.sumOf { it.cups ?: 0 })
        assertEquals(null, nov[20].cups)                              // 21 Nov: future
        assertEquals(true, nov[20].complete)
        val oct = s.monthSeries(today, offset = -1)
        assertEquals(31, oct.size)
        assertEquals(null, oct[0].cups)                               // 1 Oct: not stored
        assertEquals(false, oct[0].complete)
        assertEquals(1, oct[25].cups)                                 // 26 Oct: oldest stored
    }

    @Test
    fun reservationsMatchCaptures() {
        val cases = listOf(
            Triple("000723000001", Triple(ReservationMode.HEAT_AND_EXTRACT, 7, 35), Triple(false, 0, true)),
            Triple("02150a000001", Triple(ReservationMode.EXTRACT, 21, 10), Triple(false, 0, true)),
            Triple("02150a000000", Triple(ReservationMode.EXTRACT, 21, 10), Triple(false, 0, false)),
            Triple("01131e000001", Triple(ReservationMode.HEAT, 19, 30), Triple(false, 0, true)),
            Triple("01131e010001", Triple(ReservationMode.HEAT, 19, 30), Triple(true, Beep.DING_DONG.value, true)),
            Triple("01131e010101", Triple(ReservationMode.HEAT, 19, 30), Triple(true, Beep.DI_DI.value, true)),
            Triple("01131e010201", Triple(ReservationMode.HEAT, 19, 30), Triple(true, Beep.BU_GU.value, true)),
            Triple("01131e010301", Triple(ReservationMode.HEAT, 19, 30), Triple(true, Beep.BI_BI.value, true)),
        )
        cases.forEach { (hexed, mode, flags) ->
            val r = Reservation.parse(hexBytes(hexed))
            assertEquals(mode.first.value, r.mode)
            assertEquals(mode.second, r.hour)
            assertEquals(mode.third, r.minute)
            assertEquals(flags.first, r.alarm)
            assertEquals(flags.second, r.beep)
            assertEquals(flags.third, r.enabled)
            assertEquals(hexed, r.payload().hexSpaced().replace(" ", ""))
            assertEquals(CMD_SET_RESERVATION, encode(CMD_SET_RESERVATION, r.payload())[4].unsigned())
        }
    }

    @Test
    fun reservationValidationAndNextRun() {
        assertThrows(IllegalArgumentException::class.java) {
            Reservation(0, 24, 0, false, 0, true).payload()
        }
        assertThrows(IllegalArgumentException::class.java) {
            Reservation(9, 7, 0, false, 0, true).payload()
        }
        val r = Reservation(2, 19, 30, false, 0, true)
        assertEquals("19:30", r.time)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 30),
            r.nextRun(LocalDateTime.of(2026, 11, 14, 19, 29, 48)))
        assertEquals(LocalDateTime.of(2026, 11, 15, 19, 30),
            r.nextRun(LocalDateTime.of(2026, 11, 14, 19, 34)))
        assertEquals(LocalDateTime.of(2027, 1, 1, 19, 30),
            r.nextRun(LocalDateTime.of(2026, 12, 31, 23, 0)))
    }

    @Test
    fun statusParse() {
        val s = Status.parse(hexBytes("013c032d0000"))
        assertEquals(Triple(0x3C, 0x2D, State.IDLE.value), Triple(s.batteryRaw, s.waterTempC, s.state))
    }
}