// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of CeraLib (MIT) tests/test_client.py: CeraClient against a
// scripted fake [BleLink], no Bluetooth.
package com.ceraopen.android.cera

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val SETTINGS = hexBytes("0800000a00000a781efa010f")
private val INFO = hexBytes("18" + "05302e302e33" + "05302e302e39" + "0950434d3033534d4158" + "060202020202")

private fun reply(cmd: Int, payload: ByteArray, flags: Int = 0): ByteArray =
    encode(cmd, payload, FROM_MACHINE, flags)

/**
 * Scripted fake machine. Each built link answers only when its index is not
 * "silent", letting tests model the machine ignoring the first few attempts.
 */
private class FakeMachine {
    var silent = 0
    var settings = SETTINGS
    val links = mutableListOf<FakeLink>()

    fun build(onN: (ByteArray) -> Unit): FakeLink {
        val l = FakeLink(this, links.size)
        l.onNotification = onN
        links += l
        return l
    }

    fun repliesFor(cmd: Int, payload: ByteArray): List<ByteArray> = when (cmd) {
        CMD_SET_CLOCK -> listOf(reply(CMD_SET_CLOCK, byteArrayOf(STATUS_OK.toByte())))
        CMD_LIMITS -> listOf(reply(CMD_LIMITS, hexBytes("64cccb")))
        CMD_GET_SETTINGS -> listOf(reply(CMD_GET_SETTINGS, settings))
        CMD_INFO -> listOf(
            reply(CMD_INFO, INFO.copyOfRange(0, 10), FLAG_FIRST),
            reply(CMD_INFO, INFO.copyOfRange(10, 20), FLAG_MIDDLE),
            reply(CMD_INFO, INFO.copyOfRange(20, INFO.size), FLAG_LAST),
        )
        CMD_STATUS -> listOf(reply(CMD_STATUS, hexBytes("013c031e0000")))
        CMD_STATS -> emptyList() // not exercised here
        CMD_BREW -> listOf(reply(CMD_BREW, byteArrayOf(STATUS_OK.toByte())))
        CMD_TARGET_TEMP -> listOf(reply(CMD_TARGET_TEMP, payload))
        else -> emptyList()
    }
}

private class FakeLink(
    private val machine: FakeMachine,
    private val index: Int,
) : BleLink() {
    override var isConnected: Boolean = false
        private set

    private val silent get() = index < machine.silent

    override suspend fun connect() {
        isConnected = true
    }

    override suspend fun startNotify() = Unit

    override suspend fun write(data: ByteArray) {
        if (!isConnected) throw CeraException("not connected")
        if (silent) return
        val frame = decode(data)
        machine.repliesFor(frame.cmd, frame.payload).forEach { onNotification?.invoke(it) }
    }

    override suspend fun disconnect() {
        if (isConnected) {
            isConnected = false
            onLost?.invoke()
        }
    }

    override fun close() {
        isConnected = false
    }
}

private fun shrink(c: CeraClient) {
    c.settleAfterConnectMs = 0
    c.preHandshakePauseMs = 0
    c.retryPauseMs = 0
    c.replyTimeoutMs = 200
    c.heartbeatWaitMs = 10
    c.retries = 0
}

class CeraClientTest {
    @Test
    fun handshakeReadsInfoSettingsAndStatus() = runTest {
        val machine = FakeMachine()
        val client = CeraClient(newLink = { machine.build { } })
        shrink(client)
        client.connect(attempts = 1)
        assertTrue(client.isConnected)
        assertEquals("PCM03SMAX", client.info?.model)
        assertEquals(8, client.settings?.flow)
        assertEquals(95.0, client.targetC ?: 0.0, 0.01)
        assertEquals(0, client.status?.state)
    }

    @Test
    fun silentFirstConnectionsAreRetriedWithFreshLinks() = runTest {
        val machine = FakeMachine().apply { silent = 2 }
        val client = CeraClient(newLink = { machine.build { } })
        shrink(client)
        client.connect(attempts = 3)
        assertEquals(3, machine.links.size) // one link per attempt
        assertFalse(machine.links[0].isConnected)
        assertFalse(machine.links[1].isConnected)
        assertTrue(machine.links[2].isConnected)
        assertTrue(client.isConnected)
    }

    @Test
    fun alwaysSilentRaisesAndCloses() = runTest {
        val machine = FakeMachine().apply { silent = 99 }
        val client = CeraClient(newLink = { machine.build { } })
        shrink(client)
        var raised = false
        try {
            client.connect(attempts = 2)
        } catch (_: CeraException) {
            raised = true
        }
        assertTrue(raised)
        assertFalse(client.isConnected)
        assertFalse(machine.links.any { it.isConnected })
    }

    @Test
    fun targetTemperatureIsWrittenAsFahrenheit() = runTest {
        val machine = FakeMachine()
        val written = mutableListOf<Int>()
        val client = CeraClient(
            newLink = { machine.build { } },
            onFrame = { _, raw ->
                val f = decode(raw)
                if (f.direction == TO_MACHINE && f.cmd == CMD_TARGET_TEMP) {
                    written.add(f.payload[0].unsigned())
                }
            },
        )
        shrink(client)
        client.connect(attempts = 1)
        client.setTargetTempC(90.0)
        client.setTargetTempC(95.0)
        assertEquals(listOf(0xC2, 0xCB), written)
    }

    @Test
    fun outOfRangeTargetIsRejectedBeforeSending() = runTest {
        val machine = FakeMachine()
        val client = CeraClient(newLink = { machine.build { } })
        shrink(client)
        client.connect(attempts = 1)
        var raised = false
        try {
            client.setTargetTempC(99.0)
        } catch (_: IllegalArgumentException) {
            raised = true
        }
        assertTrue(raised)
    }
}
