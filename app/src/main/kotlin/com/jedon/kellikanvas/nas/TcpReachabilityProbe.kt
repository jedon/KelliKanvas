package com.jedon.kellikanvas.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** True when [host] accepts a TCP connection on any of [ports] within [timeoutMillis]. */
suspend fun isTcpReachable(
    host: String,
    ports: List<Int>,
    timeoutMillis: Int = DEFAULT_TCP_PROBE_TIMEOUT_MILLIS,
): Boolean = runInterruptible(Dispatchers.IO) {
    ports.any { port ->
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

const val DEFAULT_TCP_PROBE_TIMEOUT_MILLIS: Int = 1_500
