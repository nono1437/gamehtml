package com.game4399.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Seer2DiscoveryFallbackTest {
    private val defaultUrl = "http://sr2login.61.com/ip.txt?v=0.42"
    private val telecomUrl = "http://ctsr2login.61.com/ip.txt"
    private val networkUrl = "http://cncsr2login.61.com/ip.txt"
    private val default = "118.89.150.43"
    private val network = "118.89.150.23"
    private val resolve: (String) -> List<String> = { host ->
        when (host) {
            "sr2login.61.com", "ctsr2login.61.com" -> listOf(default)
            "cncsr2login.61.com" -> listOf(network)
            else -> emptyList()
        }
    }

    @Test fun fallbackOnlyUsesPublicAddressQueriesAndRecoverableErrors() {
        assertEquals("https://o1.733702.xyz/seer2/config/ip/sr2login.61.com",
            Seer2DiscoveryFallback.mirrorUrl(defaultUrl))
        assertNull(Seer2DiscoveryFallback.mirrorUrl("http://sr2login.61.com/login"))
        assertNull(Seer2DiscoveryFallback.mirrorUrl("http://sr2login.61.com/ip.txt?token=secret"))
        assertNull(Seer2DiscoveryFallback.mirrorUrl("http://example.com/ip.txt"))
        assertTrue(Seer2DiscoveryFallback.shouldRetry(503))
        assertTrue(Seer2DiscoveryFallback.shouldRetry(415))
        assertFalse(Seer2DiscoveryFallback.shouldRetry(200))
        assertFalse(Seer2DiscoveryFallback.shouldRetry(403))
    }

    @Test fun fallbackAddressMustMatchTheSelectedOfficialLineAndPort() {
        fun verified(url: String, value: String) = Seer2DiscoveryFallback.verify(url, value.toByteArray(), resolve)
            ?.toString(Charsets.US_ASCII)
        assertEquals("$network:1863", verified(defaultUrl, "  $network:1863\r\n"))
        assertEquals("$network:1863", verified(networkUrl, "$network:1863"))
        assertEquals("$default:1863", verified(telecomUrl, "$default:1863"))
        assertNull(verified(telecomUrl, "$network:1863"))
        assertNull(verified(defaultUrl, "$network:80"))
        assertNull(verified(defaultUrl, "<html>unavailable</html>"))
        assertNull(Seer2DiscoveryFallback.verify(defaultUrl, "127.0.0.1:1863".toByteArray()) { listOf("127.0.0.1") })
    }
}
