package com.game4399.app.webview

import java.net.URI

/** Findings for one top-level page, independent of whether diagnostic logging is enabled. */
internal class SwfDiscovery {
    data class Result(val url: String, val title: String, val size: Long?) {
        val displayUrl: String get() = url.substringBefore('?')
    }

    private val downloads = LinkedHashMap<String, Result>()
    private val nonMovies = LinkedHashSet<String>()

    @Synchronized
    fun recordResponse(url: String, data: ByteArray) {
        if (!FlashPayload.hasSignature(data) && !isSwfPath(url)) return
        val candidate = candidate(url, "", data.size.toLong()) ?: return
        val key = key(candidate.url)
        if (!FlashPayload.isSwf(data, data.size.toLong())) {
            if (!isSwfPath(candidate.url)) return
            downloads.remove(key)
            nonMovies.add(key)
            if (nonMovies.size > MAX_RESULTS) nonMovies.remove(nonMovies.first())
            return
        }
        nonMovies.remove(key)
        downloads.remove(key)
        downloads[key] = candidate
        if (downloads.size > MAX_RESULTS) downloads.remove(downloads.keys.first())
    }

    @Synchronized
    fun results(pageLinks: Iterable<Pair<String, String>>): List<Result> {
        val merged = LinkedHashMap<String, Result>(downloads)
        for ((url, title) in pageLinks) {
            val candidate = candidate(url, title, null) ?: continue
            val key = key(candidate.url)
            if (key !in nonMovies) merged.putIfAbsent(key, candidate)
            if (merged.size >= MAX_RESULTS) break
        }
        return merged.values.toList()
    }

    private fun candidate(link: String, label: String, size: Long?): Result? {
        if (link.length > 4096) return null
        val source = RemoteSwfProxy.unwrap(link) ?: link
        val uri = runCatching { URI(source.substringBefore('#')) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val url = if (host.equals("flash.local", true)) {
            Seer2Assets.upstream(uri.rawPath.orEmpty(), uri.rawQuery)
                ?.takeIf { Seer2Assets.publicAsset(it) != null } ?: return null
        } else Seer2Assets.publicAsset(uri.toString()) ?: uri.toString()
        val title = label.filterNot(Char::isISOControl).take(120).trim()
            .ifEmpty { uri.path.orEmpty().substringAfterLast('/').let { it.substringBeforeLast('.', it) } }
        return Result(url, title, size)
    }

    private fun key(url: String): String {
        val official = Seer2Assets.publicAsset(url) ?: return url
        return Seer2Assets.versionedResourceKey(official) ?: official.substringBefore('?')
    }

    private fun isSwfPath(url: String) = url.substringBefore('?').endsWith(".swf", ignoreCase = true)

    companion object {
        const val MAX_RESULTS = 400
    }
}
