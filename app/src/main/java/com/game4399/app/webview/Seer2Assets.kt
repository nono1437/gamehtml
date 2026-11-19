package com.game4399.app.webview

import java.net.URI
import java.util.zip.Inflater

/** Only public game files use the official legacy HTTP asset origin. */
internal object Seer2Assets {
    const val ORIGIN = "http://seer2.61.com"
    const val ENTRY = "$ORIGIN/index_open.html"
    const val PREFIX = "/_seer2/"
    const val BASE = "https://flash.local$PREFIX"
    const val MOVIE = "${BASE}Client.swf"

    private val safePath = Regex("""/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""")
    private val cacheKey = Regex("""(?:[0-9.]{1,32}|(?:v|ver|version|t|r|_)=\w{1,32})""")
    private val versionPath = Regex("""/version/version[0-9]{1,12}\.swf""")
    private val packedDlls = setOf("/dll/TaomeeCoreDLL.swf", "/dll/ClientCoreDLL.swf", "/dll/ClientAppDLL.swf")
    private val assetDirectories = setOf("config", "version", "dll", "res")
    private val assetExtensions = setOf("swf", "xml", "txt", "json", "png", "jpg", "jpeg", "gif", "mp3", "ogg", "dat", "bin")

    fun isLauncher(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && uri.host.equals("seer2.61.com", true) &&
            uri.port == -1 && uri.rawUserInfo == null &&
            uri.path in setOf("", "/", "/index_open.html", "/Client.swf")
    }

    fun publicAsset(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || !uri.host.equals("seer2.61.com", true) ||
            uri.port != -1 || uri.rawUserInfo != null) return null
        return upstream(PREFIX + uri.rawPath.orEmpty().removePrefix("/"), uri.rawQuery)
    }

    fun upstream(rawPath: String, rawQuery: String?): String? {
        if (!rawPath.startsWith(PREFIX)) return null
        val path = "/" + rawPath.removePrefix(PREFIX)
        if (!safePath.matches(path) || path.split('/').any { it == "." || it == ".." }) return null
        if (path != "/Client.swf" && (path.split('/')[1] !in assetDirectories ||
                path.substringAfterLast('.').lowercase() !in assetExtensions)) return null
        if (rawQuery != null && !cacheKey.matches(rawQuery)) return null
        return ORIGIN + path + (rawQuery?.let { "?$it" } ?: "")
    }

    fun validPayload(path: String, data: ByteArray): Boolean = when {
        versionPath.matches(path) -> data.size > 8 &&
            data.copyOfRange(0, 4).contentEquals(byteArrayOf(1, 51, 83, 5)) && isZlib(data, 8)
        path in packedDlls -> FlashPayload.isSwf(data, data.size.toLong()) || isZlib(data, 7, true)
        path.endsWith(".swf", true) -> FlashPayload.isSwf(data, data.size.toLong())
        path == "/version/zzz_config.txt" -> data.size in 1..32 &&
            data.toString(Charsets.UTF_8).trim().matches(Regex("[0-9]{1,12}"))
        else -> data.isNotEmpty() && !FlashPayload.looksLikeHtml(data)
    }

    private fun isZlib(data: ByteArray, offset: Int, requireSwf: Boolean = false): Boolean {
        if (data.size <= offset) return false
        val decoder = Inflater()
        return try {
            decoder.setInput(data, offset, data.size - offset)
            val sample = ByteArray(8)
            val length = decoder.inflate(sample)
            length > 0 && (decoder.finished() || !decoder.needsInput()) &&
                (!requireSwf || (length == sample.size && FlashPayload.hasSignature(sample)))
        } catch (_: java.util.zip.DataFormatException) {
            false
        } finally {
            decoder.end()
        }
    }
}

internal object FlashPayload {
    fun hasSignature(header: ByteArray): Boolean =
        header.size >= 3 && header[0].toInt().toChar() in "FCZ" &&
            header[1] == 'W'.code.toByte() && header[2] == 'S'.code.toByte()

    fun isSwf(header: ByteArray, size: Long): Boolean {
        if (header.size < 8 || size < 9 || !hasSignature(header) || header[3] == 0.toByte()) return false
        val declared = (0..3).sumOf { (header[it + 4].toLong() and 255) shl (8 * it) }
        return declared >= 9 && when (header[0].toInt().toChar()) {
            'F' -> declared == size
            'C' -> size >= 10
            else -> size >= 17
        }
    }

    fun looksLikeHtml(data: ByteArray): Boolean {
        val prefix = data.copyOf(minOf(data.size, 256)).toString(Charsets.UTF_8)
            .trimStart { it.isWhitespace() || it == '\uFEFF' }.lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
            prefix.startsWith("<head") || prefix.startsWith("<body")
    }
}
