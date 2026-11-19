package com.game4399.app.webview

import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.Closeable

/** Exposes the relay only to the main frame of the bundled official-game player. */
internal class Seer2SocketBridge(
    private val view: WebView,
    private val diagnostics: FlashDiagnostics
) : Closeable {
    private class Page(val url: String, val relay: Seer2SocketRelay)

    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    @Volatile private var page: Page? = null

    init {
        if (supported) {
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(ORIGIN)) { _, message, source, mainFrame, reply ->
                val current = page ?: return@addWebMessageListener
                if (!mainFrame || source.scheme != "https" || source.host != "flash.local" ||
                    source.port !in setOf(-1, 443) || current.url != view.url) return@addWebMessageListener
                val payload = message.data?.takeIf { it.length <= 24000 } ?: return@addWebMessageListener
                val request = runCatching { JSONObject(payload) }.getOrNull() ?: return@addWebMessageListener
                val id = request.optInt("id")
                if (id <= 0) return@addWebMessageListener

                when (request.optString("type")) {
                    "open" -> {
                        val opened = current.relay.open(id, request.optString("host"), request.optInt("port")) { event ->
                            view.post {
                                if (page === current) {
                                    val state = when (event.type) {
                                        "open" -> "官服 TCP 已连接"
                                        "close" -> "官服 TCP 已关闭"
                                        "error" -> "官服 TCP 连接或通信失败"
                                        else -> null
                                    }
                                    state?.let { diagnostics.record(it, current.url) }
                                    val result = JSONObject().put("id", id).put("type", event.type)
                                    event.data?.let { result.put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }
                                    runCatching { reply.postMessage(result.toString()) }
                                }
                            }
                        }
                        if (!opened) {
                            diagnostics.record("已阻止未获许可的官服 TCP 连接", current.url)
                            reply.postMessage(JSONObject().put("id", id).put("type", "error").toString())
                        }
                    }
                    "data" -> {
                        val data = runCatching { Base64.decode(request.optString("data"), Base64.DEFAULT) }.getOrNull()
                        if (data == null || !current.relay.send(id, data)) {
                            current.relay.disconnect(id)
                            reply.postMessage(JSONObject().put("id", id).put("type", "error").toString())
                        }
                    }
                    "close" -> current.relay.disconnect(id)
                }
            }
        }
    }

    fun onPageStarted(url: String?) {
        val previous = page
        page = null
        previous?.relay?.close()
        if (supported && url != null && isOfficialPlayer(url)) page = Page(url, Seer2SocketRelay())
    }

    fun receiverFor(url: String?): ((ByteArray) -> Unit)? = page
        ?.takeIf { it.url == url }?.relay?.let { it::rememberLoginAddress }

    override fun close() {
        onPageStarted(null)
        if (supported) WebViewCompat.removeWebMessageListener(view, BRIDGE_NAME)
    }

    private fun isOfficialPlayer(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host == "flash.local" && uri.port == -1 && uri.userInfo == null &&
            uri.path == "/player.html" && uri.getQueryParameter("profile") == "seer2" &&
            uri.getQueryParameter("engine") == "ruffle" && uri.getQueryParameter("swf") == Seer2Assets.MOVIE &&
            uri.getQueryParameter("base") == Seer2Assets.BASE
    }

    private companion object {
        const val ORIGIN = "https://flash.local"
        const val BRIDGE_NAME = "Seer2NativeSocket"
    }
}
