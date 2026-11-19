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
    const val DISCOVERY_PREFIX = "${PREFIX}login-discovery/"
    const val DISCOVERY_MAX_BYTES = 128
    const val DISCOVERY_TIMEOUT_MILLIS = 4000

    private val safePath = Regex("""/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""")
    private val cacheKey = Regex("""(?:[0-9.]{1,32}|(?:v|ver|version|t|r|_)=\w{1,32})""")
    private val timestampCacheKey = Regex("""([0-9a-z]{8,9})(?:\?(?:0(?:\.\d{1,24})?|[0-9](?:\.\d{1,24})?e-\d{1,2})|&\d{1,4})?""")
    private val assetTimestampRange = 946684800000L..4102444800000L // 2000–2100, epoch milliseconds
    private val versionPath = Regex("""/version/version[0-9]{1,12}\.swf""")
    private val packedDlls = setOf("/dll/TaomeeCoreDLL.swf", "/dll/ClientCoreDLL.swf", "/dll/ClientAppDLL.swf")
    private val assetDirectories = setOf("config", "version", "dll", "res")
    private val assetExtensions = setOf("swf", "xml", "txt", "json", "png", "jpg", "jpeg", "gif", "mp3", "ogg", "dat", "bin")
    private val pngHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
    private val pngEnd = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
    val discoveryHosts = setOf("sr2login", "ctsr2login", "cncsr2login")
    private val discoveryQuery = Regex("""v=(?:0(?:\.\d{1,24})?|[0-9](?:\.\d{1,24})?e-\d{1,2})""")
    private val endpoint = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""")

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
        return staticAsset(uri.rawPath.orEmpty(), uri.rawQuery)
    }

    fun upstream(rawPath: String, rawQuery: String?): String? {
        if (!rawPath.startsWith(PREFIX)) return null
        if (rawPath.startsWith(DISCOVERY_PREFIX)) {
            val host = rawPath.removePrefix(DISCOVERY_PREFIX).removeSuffix("/ip.txt")
            if (host !in discoveryHosts || rawPath != "$DISCOVERY_PREFIX$host/ip.txt" ||
                !validDiscoveryQuery(rawQuery)) return null
            // Server.xml lists these three public GET endpoints on HTTP.
            return "http://$host.61.com/ip.txt" + (rawQuery?.let { "?$it" } ?: "")
        }
        return staticAsset("/" + rawPath.removePrefix(PREFIX), rawQuery)
    }

    fun isLoginDiscoveryUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.removeSuffix(".61.com")
        return uri.scheme in setOf("http", "https") && host in discoveryHosts && uri.host == "$host.61.com" &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.rawPath == "/ip.txt" && validDiscoveryQuery(uri.rawQuery)
    }

    private fun validDiscoveryQuery(query: String?): Boolean = query == null || discoveryQuery.matches(query)

    private fun staticAsset(path: String, rawQuery: String?): String? {
        if (!safePath.matches(path) || path.split('/').any { it == "." || it == ".." }) return null
        val extension = path.substringAfterLast('.').lowercase()
        val allowed = when {
            path == "/Client.swf" -> true
            path.startsWith("/module/") -> extension == "swf"
            else -> path.split('/')[1] in assetDirectories && extension in assetExtensions
        }
        if (!allowed) return null
        if (rawQuery != null && !isCacheKey(rawQuery)) return null
        return ORIGIN + path + (rawQuery?.let { "?$it" } ?: "")
    }

    private fun isCacheKey(query: String): Boolean {
        return cacheKey.matches(query) || resourceVersion(query) != null
    }

    private fun resourceVersion(query: String): String? {
        // The version manager emits a base-36 timestamp; AssetsLoader appends '?' and Math.random().
        val version = timestampCacheKey.matchEntire(query)?.groupValues?.get(1) ?: return null
        val timestamp = version.toLong(36)
        return version.takeIf { timestamp in assetTimestampRange && timestamp % 1000L == 0L }
    }

    fun versionedResourceKey(address: String): String? {
        val canonical = publicAsset(address) ?: return null
        val path = canonical.removePrefix(ORIGIN).substringBefore('?')
        if (!path.startsWith("/module/") && !path.startsWith("/res/")) return null
        val version = resourceVersion(canonical.substringAfter('?', "")) ?: return null
        return "$path?$version"
    }

    fun validPayload(path: String, data: ByteArray): Boolean = when {
        path == "/ip.txt" -> {
            val match = if (data.size in 1..DISCOVERY_MAX_BYTES) {
                endpoint.matchEntire(data.toString(Charsets.UTF_8).trim())
            } else null
            match != null && match.groupValues[1].split('.').all { it.toInt() in 0..255 } &&
                match.groupValues[2].toInt() in 1..65535
        }
        versionPath.matches(path) -> data.size > 8 &&
            data.copyOfRange(0, 4).contentEquals(byteArrayOf(1, 51, 83, 5)) && isZlib(data, 8)
        path in packedDlls -> FlashPayload.isSwf(data, data.size.toLong()) || isZlib(data, 7, true)
        path.endsWith(".swf", true) -> FlashPayload.isSwf(data, data.size.toLong()) ||
            (path.startsWith("/res/") && isFrameSequence(data))
        path == "/version/zzz_config.txt" -> data.size in 1..32 &&
            data.toString(Charsets.UTF_8).trim().matches(Regex("[0-9]{1,12}"))
        else -> data.isNotEmpty() && !FlashPayload.looksLikeHtml(data)
    }

    // FrameSequence stores a header, a frame table and a PNG sheet under a .swf resource name.
    private fun isFrameSequence(data: ByteArray): Boolean {
        if (data.size < 2) return false
        fun word(offset: Int): Int = (data[offset].toInt() shl 8) or (data[offset + 1].toInt() and 255)

        val headerEnd = 2 + word(0)
        if (headerEnd !in 16..data.size - 8 || word(2) <= 0 || word(4) <= 0 || word(6) <= 0) return false
        // The header ends with an AMF3 anonymous dynamic object containing animation labels.
        if (data[12] != 10.toByte() || data[13] != 11.toByte() || data[14] != 1.toByte() ||
            data[headerEnd - 1] != 1.toByte()) return false

        val tableBytes = word(headerEnd)
        val table = headerEnd + 2
        val frameCount = word(table + 4)
        if (word(table) <= 0 || word(table + 2) <= 0 || frameCount <= 0 || tableBytes != 6 + frameCount * 12) return false

        val image = table + tableBytes
        return image <= data.size - 33 - pngEnd.size &&
            pngHeader.indices.all { data[image + it] == pngHeader[it] } &&
            pngEnd.indices.all { data[data.size - pngEnd.size + it] == pngEnd[it] }
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
