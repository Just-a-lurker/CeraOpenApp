// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from CeraLib (MIT) cera/codec.py: frame codec for the PCM03 family
// BLE protocol. Pure functions, no Bluetooth.
//
// Frame layout, both directions:
//   [dir] [flags] [length] [checksum] [cmd] [payload ...]
//
// * dir      0x7F = app -> machine, 0x7E = machine -> app.
// * flags    0x00 single frame; 0x40 first, 0x80 middle, 0xC0 last fragment.
// * length   total bytes in this frame, header included.
// * checksum (sum of every other byte in the frame) mod 256.
// * Fragments are individual frames, each with its own header and checksum.
//   A fragmented reply is the concatenation of the fragment payloads.
package com.ceraopen.android.cera

const val TO_MACHINE = 0x7F
const val FROM_MACHINE = 0x7E

const val FLAG_SINGLE = 0x00
const val FLAG_FIRST = 0x40
const val FLAG_MIDDLE = 0x80
const val FLAG_LAST = 0xC0

const val HEADER_LEN = 5
const val MAX_FRAME = 255

class FrameError(message: String) : Exception(message)

data class Frame(val direction: Int, val flags: Int, val cmd: Int, val payload: ByteArray) {
    val isFragment: Boolean get() = flags != FLAG_SINGLE

    override fun equals(other: Any?): Boolean =
        other is Frame && direction == other.direction && flags == other.flags &&
            cmd == other.cmd && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * (31 * direction + flags) + cmd) + payload.contentHashCode()

    fun encode(): ByteArray = encode(cmd, payload, direction, flags)
}

/** Checksum of a frame, ignoring the checksum byte itself (index 3). */
fun checksum(frame: ByteArray): Int {
    var sum = 0
    for (i in frame.indices) {
        if (i != 3) sum += frame[i].toInt() and 0xFF
    }
    return sum and 0xFF
}

fun encode(
    cmd: Int,
    payload: ByteArray = ByteArray(0),
    direction: Int = TO_MACHINE,
    flags: Int = FLAG_SINGLE,
): ByteArray {
    val total = HEADER_LEN + payload.size
    if (total > MAX_FRAME) throw FrameError("frame too long: $total")
    val raw = ByteArray(total)
    raw[0] = direction.toByte()
    raw[1] = flags.toByte()
    raw[2] = total.toByte()
    raw[4] = cmd.toByte()
    payload.copyInto(raw, HEADER_LEN)
    raw[3] = checksum(raw).toByte()
    return raw
}

fun decode(raw: ByteArray): Frame {
    if (raw.size < HEADER_LEN) throw FrameError("frame too short: ${raw.size}")
    val direction = raw[0].toInt() and 0xFF
    if (direction != TO_MACHINE && direction != FROM_MACHINE) {
        throw FrameError("bad direction byte: 0x%02x".format(direction))
    }
    if ((raw[2].toInt() and 0xFF) != raw.size) {
        throw FrameError("length byte ${raw[2].toInt() and 0xFF} != actual ${raw.size}")
    }
    if ((raw[3].toInt() and 0xFF) != checksum(raw)) {
        throw FrameError(
            "bad checksum: got 0x%02x, want 0x%02x".format(raw[3].toInt() and 0xFF, checksum(raw))
        )
    }
    return Frame(direction, raw[1].toInt() and 0xFF, raw[4].toInt() and 0xFF,
        raw.copyOfRange(HEADER_LEN, raw.size))
}

/**
 * Collects fragments of one multi-notification reply. Feed every decoded
 * frame; [feed] returns a complete single-frame equivalent when one is
 * ready, otherwise null. Not thread-safe: one consumer, like the Python
 * original inside a single event loop.
 */
class Reassembler {
    private var parts: MutableList<ByteArray> = mutableListOf()
    private var cmd: Int? = null

    fun feed(frame: Frame): Frame? {
        if (!frame.isFragment) return frame
        if (frame.flags == FLAG_FIRST) {
            parts = mutableListOf(frame.payload)
            cmd = frame.cmd
            return null
        }
        if (cmd == null || frame.cmd != cmd) {
            parts = mutableListOf()
            cmd = null
            throw FrameError("fragment without matching first fragment")
        }
        parts.add(frame.payload)
        if (frame.flags == FLAG_LAST) {
            val size = parts.sumOf { it.size }
            val joined = ByteArray(size)
            var at = 0
            for (p in parts) {
                p.copyInto(joined, at)
                at += p.size
            }
            val done = Frame(frame.direction, FLAG_SINGLE, cmd!!, joined)
            parts = mutableListOf()
            cmd = null
            return done
        }
        return null
    }
}

// -- small byte helpers (java.util.HexFormat needs API 33) --------------------

/** Unsigned byte value, 0-255. */
fun Byte.unsigned(): Int = toInt() and 0xFF

fun ByteArray.hexSpaced(): String = joinToString(" ") { "%02x".format(it) }

fun hexBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
