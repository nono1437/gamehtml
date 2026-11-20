package com.game4399.app.webview

import java.net.URI

internal object Seer2DiscoveryFallback {
    private const val MIRROR = "https://o1.733702.xyz/seer2/config/ip"
    private const val LOGIN_PORT = 1863

    fun shouldRetry(status: Int): Boolean = status in setOf(404, 408, 415, 429) || status in 500..599

    fun mirrorUrl(officialUrl: String): String? = officialHost(officialUrl)?.let { "$MIRROR/$it" }

    fun verify(officialUrl: String, data: ByteArray, resolve: (String) -> Collection<String>): ByteArray? {
        val host = officialHost(officialUrl) ?: return null
        if (!Seer2Assets.validPayload("/ip.txt", data)) return null
        val endpoint = data.toString(Charsets.US_ASCII).trim()
        if (endpoint.substringAfter(':').toInt() != LOGIN_PORT) return null
        val address = Seer2SocketRelay.publicAddress(endpoint.substringBefore(':'), LOGIN_PORT) ?: return null
        // The general entry can point to either official line. An explicit choice stays on that line.
        val allowedHosts = if (host == "sr2login.61.com") {
            listOf(host, "cncsr2login.61.com", "ctsr2login.61.com")
        } else listOf(host)
        if (allowedHosts.none { address.host in resolve(it) }) return null
        return "${address.host}:${address.port}".toByteArray(Charsets.US_ASCII)
    }

    private fun officialHost(url: String): String? =
        if (Seer2Assets.isLoginDiscoveryUrl(url)) URI(url).host else null
}
