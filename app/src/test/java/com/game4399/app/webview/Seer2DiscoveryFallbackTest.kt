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
        assertEquals("$default:1863", Seer2DiscoveryFallback.fromDns(defaultUrl, resolve)?.decodeToString())
        assertEquals("$network:1863", Seer2DiscoveryFallback.fromDns(networkUrl, resolve)?.decodeToString())
        assertNull(Seer2DiscoveryFallback.fromDns("http://sr2login.61.com/login", resolve))
        assertNull(Seer2DiscoveryFallback.fromDns("http://sr2login.61.com/ip.txt?token=discarded", resolve))
        assertNull(Seer2DiscoveryFallback.fromDns("http://example.com/ip.txt", resolve))
        val availableLine: (String) -> List<String> = { host ->
            if (host == "cncsr2login.61.com") listOf("127.0.0.1", "::1", network) else emptyList()
        }
        assertEquals("$network:1863", Seer2DiscoveryFallback.fromDns(defaultUrl, availableLine)?.decodeToString())
        assertNull(Seer2DiscoveryFallback.fromDns(telecomUrl, availableLine))
        assertTrue(Seer2DiscoveryFallback.shouldRetry(503))
        assertTrue(Seer2DiscoveryFallback.shouldRetry(415))
        assertFalse(Seer2DiscoveryFallback.shouldRetry(200))
        assertFalse(Seer2DiscoveryFallback.shouldRetry(403))
    }
}
