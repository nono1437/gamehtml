package com.game4399.app.webview

import java.net.URI
import java.util.ArrayDeque

/** A bounded, opt-in session log. Never retain request bodies, headers or raw console text. */
class FlashDiagnostics {
    @Volatile var enabled = false
    @Volatile var officialConnectionReport = ""
    private val entries = ArrayDeque<String>()
    private var revision = 0
    private var lastEvent: String? = null

    @Synchronized
    fun record(state: String, url: String?, status: Int? = null, bytes: Long? = null, millis: Long? = null) {
        if (!enabled) return
        val event = buildString {
            append(state)
            append(" | ")
            append(safeUrl(url))
            if (status != null) append(" | HTTP $status")
            if (bytes != null) append(" | $bytes B")
            if (millis != null) append(" | $millis ms")
        }
        if (event == lastEvent) return
        lastEvent = event
        if (entries.size >= 400) entries.removeFirst()
        entries.addLast("${++revision} | $event")
    }

    fun console(level: String, message: String, source: String?, line: Int) {
        if (!enabled) return
        val blockedPrefix = "FlashDiagnostic blocked "
        if (message.startsWith(blockedPrefix)) {
            record("WebView 已阻止连接", message.removePrefix(blockedPrefix))
            return
        }
        val name = when {
            message == "FlashDiagnostic seer2.discovery.timeout" -> "登录服务器地址查询超时（12 秒）"
            message == "FlashDiagnostic seer2.discovery.error" -> "登录服务器地址查询连接失败"
            message == "FlashDiagnostic ruffle.root.ready" -> "Ruffle 已加载启动 SWF"
            message == "FlashDiagnostic ruffle.root.error" -> "Ruffle 启动 SWF 加载失败"
            message == "FlashDiagnostic waflash.runtime.ready" -> "WAFlash 引擎已初始化"
            message == "WAFLASH> Playing..." -> "WAFlash 已播放启动 SWF"
            message.startsWith("WAFLASH> WAFLASH_NOTIFY_ABORT") -> "WAFlash 引擎中止"
            message.startsWith("FlashDiagnostic waflash.") -> "WAFlash JavaScript 异常 (${errorType(message)})"
            message.contains("Missing WebSocket proxy for host ") -> "Ruffle Socket 连接失败（未配置 WebSocket 代理）"
            message.contains("Encountered stub: AVM2 flash.net.Socket") -> "Ruffle 的 AS3 Socket API 受限"
            message.contains("Error during AVM2") || message.contains("Error running AVM2") ->
                "Ruffle AS3 异常 (${Regex("Error #[0-9]{3,5}").find(message)?.value ?: "AVM2"})"
            message.startsWith("%cERROR%c") -> "Ruffle 引擎错误 (${errorType(message)})"
            level == "ERROR" -> "JavaScript / 控制台错误 (${errorType(message)})"
            else -> return
        }
        record(if (line > 0) "$name · 行 $line" else name, source)
    }

    @Synchronized fun snapshot(): String = buildString {
        appendLine("Flash 资源诊断")
        val report = officialConnectionReport
        if (report.isNotEmpty()) {
            appendLine("官服连接检测")
            appendLine(report)
            appendLine()
        }
        appendLine("交付表示 WebView 已收到响应，Flash 内部执行状态由引擎事件报告。")
        append(entries.joinToString("\n"))
    }

    @Synchronized fun clear() {
        entries.clear()
        revision = 0
        lastEvent = null
    }

    private fun errorType(message: String): String =
        listOf("TypeError", "ReferenceError", "SyntaxError", "RangeError", "RuntimeError",
            "SecurityError", "NetworkError", "Mixed Content", "CORS").firstOrNull(message::contains)
            ?: "Error"

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { URI(raw ?: return "—") }.getOrNull() ?: return "—"
        if (uri.scheme !in setOf("http", "https") || uri.host == null) return "[本地文件]"
        val path = uri.rawPath.orEmpty()
        val accountPath = path.split('/').getOrNull(1)?.lowercase() in
            setOf("login", "account", "auth", "session", "passport", "sso", "signin")
        val safePath = if (!accountPath && path.length <= 240 && path.split('/').all {
                it.length <= 64 && it.matches(Regex("[A-Za-z0-9_.-]*")) &&
                    !it.matches(Regex("[0-9A-Fa-f]{16,}"))
            } && (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in publicExtensions ||
                path.isEmpty() || path == "/")) path else "/[路径隐藏]"
        return "${uri.scheme}://${uri.host}" + (if (uri.port != -1) ":${uri.port}" else "") + safePath
    }

    private companion object {
        val publicExtensions = setOf("swf", "xml", "txt", "json", "html", "htm", "js", "wasm", "css",
            "data", "png", "jpg", "jpeg", "gif", "mp3", "ttf", "bin", "dat")
    }
}
