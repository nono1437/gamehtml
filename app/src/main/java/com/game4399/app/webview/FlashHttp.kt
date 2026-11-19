package com.game4399.app.webview

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP transport for asset GETs. Account requests stay in the browser/engine. */
internal object FlashHttp {
    private val redirectStatuses = setOf(301, 302, 303, 307, 308)

    fun sameOrigin(a: URL, b: URL): Boolean = a.protocol == b.protocol && a.host.equals(b.host, true) &&
        a.port.takeIf { it != -1 }.let { it ?: a.defaultPort } ==
        b.port.takeIf { it != -1 }.let { it ?: b.defaultPort }

    fun canRedirect(from: URL, to: URL): Boolean =
        to.protocol in setOf("http", "https") && to.userInfo == null && to.host.isNotEmpty() &&
            (from.protocol != "https" || to.protocol == "https")

    fun referrer(target: URL, pageUrl: String?): String? {
        val page = runCatching { URL(pageUrl) }.getOrNull()
            ?.takeIf { it.protocol in setOf("http", "https") && it.host != "flash.local" && it.userInfo == null }
        if (page != null && page.protocol == "https" && target.protocol == "http") return null
        if (page != null) {
            val path = if (sameOrigin(page, target)) page.path.ifBlank { "/" } else "/"
            return "${page.protocol}://${page.authority}$path"
        }
        if (target.host == "4399.com" || target.host.endsWith(".4399.com")) return "https://www.4399.com/"
        return "${target.protocol}://${target.authority}/"
    }

    fun open(
        address: String,
        pageUrl: String?,
        userAgent: String,
        accept: String? = null,
        cookies: ((String) -> String?)? = null,
        timeoutMillis: Int? = null,
        onRedirect: (String, Int, Boolean) -> Unit = { _, _, _ -> },
        allowRedirect: (URL) -> Boolean = { true }
    ): HttpURLConnection {
        var target = URL(address)
        val page = runCatching { URL(pageUrl) }.getOrNull()
        repeat(6) { hop ->
            if (target.protocol !in setOf("https", "http") || target.userInfo != null ||
                target.host.isEmpty() || target.host.equals("flash.local", true)) {
                throw IOException("Invalid asset URL")
            }
            val connection = target.openConnection() as HttpURLConnection
            var handOff = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMillis ?: 10000
                connection.readTimeout = timeoutMillis ?: 20000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", accept ?: "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                referrer(target, pageUrl)?.let { connection.setRequestProperty("Referer", it) }
                if (target.protocol == "https" && page != null && sameOrigin(page, target)) {
                    cookies?.invoke(target.toString())?.let { connection.setRequestProperty("Cookie", it) }
                }
                val status = connection.responseCode
                if (status !in redirectStatuses) {
                    handOff = true
                    return connection
                }
                val location = connection.getHeaderField("Location")
                val destination = location?.let { runCatching { URL(target, it) }.getOrNull() }
                val allowed = destination != null && hop < 5 && canRedirect(target, destination) &&
                    allowRedirect(destination)
                onRedirect(target.toString(), status, allowed)
                if (!allowed) throw IOException("Blocked asset redirect")
                target = checkNotNull(destination)
            } finally {
                if (!handOff) connection.disconnect()
            }
        }
        throw IOException("Too many asset redirects")
    }
}
