package com.game4399.app.autoplay

data class SwfCandidate(
    val url: String,
    val title: String = "",
    val size: String = ""
)

object GameAutoConfig {
    private val gameIdRegex = Regex("/flash/(\\d+)(?:\\.html?)?(?:[?#]|$)", RegexOption.IGNORE_CASE)
    private val junkNames = setOf("cell.swf", "objtest.swf", "a4399dv_base.swf")

    /** 已实机确认过的少量游戏配置，优先于页面文案自动识别。 */
    private val knownActionKeys = mapOf(
        // 新黄金矿工：玩家1 W=投雷/上拉，S=下钩/种植
        "182762" to listOf("W", "S")
    )

    fun gameIdFromUrl(url: String): String? = gameIdRegex.find(url)?.groupValues?.getOrNull(1)

    /**
     * 4399 某些页面不会直接暴露 SWF，而是给出 /flash/swf.htm?gamepath=... 包装页。
     * Ruffle 若加载包装页，实际拿到的是 HTML，最终会报“不是合法 SWF”。
     * 这里在候选进入打分/缓存前就解出真正的 gamepath。
     */
    fun normalizeSwfUrl(rawUrl: String): String {
        val original = rawUrl.trim()
        val pathLower = original.substringBefore('?').substringBefore('#').lowercase()
        val is4399Wrapper = pathLower.endsWith("/flash/swf.htm") || pathLower.endsWith("/flash/swf.html")
        if (!is4399Wrapper) return original

        val query = original.substringAfter('?', "").substringBefore('#')
        val encodedGamePath = query
            .split('&')
            .firstOrNull { it.substringBefore('=').equals("gamepath", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
            ?: return original

        val decoded = runCatching {
            java.net.URLDecoder.decode(encodedGamePath, Charsets.UTF_8.name())
        }.getOrDefault(encodedGamePath).trim()

        val resolved = when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true) -> decoded
            decoded.startsWith("/") -> "https://www.4399.com$decoded"
            else -> "https://$decoded"
        }

        return if (resolved.contains(".swf", ignoreCase = true)) resolved else original
    }

    fun selectBestSwf(candidates: List<SwfCandidate>): SwfCandidate? {
        data class Scored(val candidate: SwfCandidate, val score: Int, val canonical: String)

        val scored = candidates.mapNotNull { c ->
            val normalized = normalizeSwfUrl(c.url)
            if (!normalized.contains(".swf", ignoreCase = true)) return@mapNotNull null
            val score = scoreSwf(normalized)
            if (score < 0) return@mapNotNull null
            Scored(c.copy(url = normalized), score, canonicalKey(normalized))
        }
        if (scored.isEmpty()) return null

        val groups = scored.groupBy { it.canonical }.map { (_, entries) ->
            entries.maxWithOrNull(
                compareBy<Scored> { it.score }
                    .thenBy { if (it.candidate.url.contains("sxiao.4399.com", ignoreCase = true)) 1 else 0 }
            )!!
        }.sortedByDescending { it.score }

        val top = groups.firstOrNull() ?: return null
        if (top.score < 70) return null
        val second = groups.getOrNull(1)
        if (second != null && top.score - second.score < 25) return null
        return top.candidate
    }

    fun actionKeysForGame(gameId: String?, pageText: String): List<String> {
        return knownActionKeys[gameId] ?: detectActionKeys(pageText)
    }

    fun detectActionKeys(pageText: String): List<String> {
        if (pageText.isBlank()) return emptyList()
        var section = pageText
        val operationIndex = section.indexOf("操作说明")
        if (operationIndex >= 0) section = section.substring(operationIndex)
        section = section.take(900)

        val player1 = Regex("玩家\\s*1", RegexOption.IGNORE_CASE).find(section)
        if (player1 != null) {
            section = section.substring(player1.range.first)
            val nextPlayer = Regex("玩家\\s*[23]", RegexOption.IGNORE_CASE).find(section, player1.value.length)
            if (nextPlayer != null) section = section.substring(0, nextPlayer.range.first)
            section = section.take(350)
        } else {
            section = section.take(500)
        }

        val out = linkedSetOf<String>()
        Regex("(?<![A-Z0-9])([A-Z])(?![A-Z0-9])").findAll(section.uppercase()).forEach { m ->
            out += m.groupValues[1]
        }

        val lower = section.lowercase()
        if ("空格" in section || "space" in lower) out += "SPACE"
        if ("回车" in section || "enter" in lower) out += "ENTER"
        if (Regex("(?<![a-z])ctrl(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(section)) out += "CTRL"
        if (Regex("(?<![a-z])shift(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(section)) out += "SHIFT"
        if (Regex("(?<![a-z])alt(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(section)) out += "ALT"

        return out.take(8)
    }

    private fun scoreSwf(url: String): Int {
        val lower = url.lowercase()
        val cleanPath = lower.substringBefore('?').substringBefore('#')
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName in junkNames) return -1000
        if ("comment.4399pk.com" in lower) return -1000
        if ("/jss/" in lower) return -900
        if ("/control/" in lower && "4399" in lower) return -800

        var score = 0
        if ("/upload_swf/" in lower) score += 80
        if ("sxiao.4399.com" in lower) score += 25
        if ("/4399swf/" in lower) score += 10
        if (Regex("/ftp\\d+/", RegexOption.IGNORE_CASE).containsMatchIn(lower)) score += 8
        if (Regex("/20\\d{6}/").containsMatchIn(lower)) score += 8
        if (fileName.matches(Regex("\\d+\\.swf"))) score += 4
        return score
    }

    private fun canonicalKey(url: String): String {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        val marker = "upload_swf/"
        val i = lower.indexOf(marker)
        return if (i >= 0) lower.substring(i) else lower
    }
}
