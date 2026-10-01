package com.game4399.app.webview

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 把远程 SWF 包装成 flash.local 同源地址。
 *
 * Ruffle 运行在 https://flash.local/player.html。若让它直接 fetch 4399 CDN，
 * Android WebView 仍可能在 CORS/重定向阶段阻断请求。通过这个同源代理地址，
 * 浏览器只访问 flash.local，真实网络下载由 GameWebViewClient 原生完成。
 */
object RemoteSwfProxy {
    private const val PROXY_PREFIX = "https://flash.local/remote.swf?url="

    fun wrap(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("https://flash.local/", ignoreCase = true)) return trimmed
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return PROXY_PREFIX + encoded
    }

    fun unwrap(proxyUrl: String): String? {
        if (!proxyUrl.startsWith(PROXY_PREFIX, ignoreCase = true)) return null
        val encoded = proxyUrl.substring(PROXY_PREFIX.length)
        if (encoded.isBlank()) return null
        return runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
}
