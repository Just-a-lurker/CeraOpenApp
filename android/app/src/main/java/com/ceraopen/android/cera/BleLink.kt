// SPDX-License-Identifier: GPL-3.0-or-later
// One BLE connection to a machine. CeraLib builds a new bleak client for
// every connection attempt; this port mirrors that with a fresh [BleLink]
// per attempt from the factory passed to [CeraClient].
package com.ceraopen.android.cera

/**
 * A single BLE connection. Implementations:
 *
 *  - call [onNotification] with every value the machine pushes (FFF1), any
 *    thread, in arrival order;
 *  - call [onLost] when a live connection drops, any thread — including
 *    drops the user asked for, the client filters duplicates like CeraLib;
 *  - throw only [CeraException] from the suspend functions, so the client
 *    does not leak platform errors (bleak/OS exceptions are wrapped by
 *    CeraLib too).
 */
abstract class BleLink {
    /** Raw notification values from the machine, in arrival order. */
    var onNotification: ((ByteArray) -> Unit)? = null

    /** Called when the link drops; may be invoked more than once. */
    var onLost: (() -> Unit)? = null

    /** True while the link can carry writes. */
    abstract val isConnected: Boolean

    /** Connect the GATT link and discover services. */
    abstract suspend fun connect()

    /** Subscribe to the machine's notify characteristic (FFF1). */
    abstract suspend fun startNotify()

    /** Write without response. */
    abstract suspend fun write(data: ByteArray)

    /** Graceful disconnect; fires [onLost] when the drop is reported. */
    abstract suspend fun disconnect()

    /** Hard teardown for a failed attempt; never reports, never throws. */
    abstract fun close()
}
