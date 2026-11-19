package com.game4399.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class Seer2OfficialConnectionTest {
    @Test fun distinguishesTcpConnectivityFromFailedTls() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2000
            val peer = thread(isDaemon = true) { server.accept().close() }
            val lines = Seer2OfficialConnection.probeSocket("127.0.0.1", server.localPort, 1500)
            assertEquals(listOf("TCP 已连通", "TLS 握手或证书校验失败"), lines)
            peer.join(2500)
        }
    }

    @Test fun identifiesUnreachableTcpWithoutReportingTlsFailure() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val lines = Seer2OfficialConnection.probeSocket("127.0.0.1", port, 1500)
        assertEquals(listOf("TCP 连接被拒绝或不可达"), lines)
    }

    @Test fun explicitProbeCanBeExportedWithResourceRecordingOff() {
        val diagnostics = FlashDiagnostics()
        diagnostics.officialConnectionReport = "ctsr2login.61.com:1863 | TCP 已连通"
        assertFalse(diagnostics.enabled)
        assertTrue(diagnostics.snapshot().contains("官服连接检测"))
        assertTrue(diagnostics.snapshot().contains(diagnostics.officialConnectionReport))
    }
}
