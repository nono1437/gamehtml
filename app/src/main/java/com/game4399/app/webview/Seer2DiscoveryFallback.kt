package com.game4399.app.webview

import java.net.URI

internal object Seer2DiscoveryFallback {
    private const val LOGIN_PORT = 1863

    fun shouldRetry(status: Int): Boolean = status in setOf(404, 408, 415, 429) || status in 500..599

    fun fromDns(officialUrl: String, resolve: (String) -> Collection<String>): ByteArray? =
        officialHosts(officialUrl).asSequence()
            .flatMap { resolve(it).asSequence() }
            .mapNotNull { Seer2SocketRelay.publicAddress(it, LOGIN_PORT) }
            .firstOrNull()?.let { endpoint(it.host) }

    private fun endpoint(host: String) = "$host:$LOGIN_PORT".toByteArray(Charsets.US_ASCII)

    // The automatic selection may use any official line. An explicit choice stays on that line.
    private fun officialHosts(url: String): List<String> {
        if (!Seer2Assets.isLoginDiscoveryUrl(url)) return emptyList()
        val host = URI(url).host
        return if (host == "sr2login.61.com") {
            listOf(host, "cncsr2login.61.com", "ctsr2login.61.com")
        } else listOf(host)
    }
}
