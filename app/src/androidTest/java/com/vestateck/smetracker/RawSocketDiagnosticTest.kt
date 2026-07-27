package com.vestateck.smetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawSocketDiagnosticTest {

    @Test
    fun rawSocketConnectivityCheck() {
        val socket = java.net.Socket()
        val start = System.currentTimeMillis()
        try {
            socket.connect(java.net.InetSocketAddress("10.0.2.2", 9099), 5000)
            println("RAW SOCKET CONNECTED in ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            println("RAW SOCKET FAILED: $e")
        } finally {
            socket.close()
        }
    }
}