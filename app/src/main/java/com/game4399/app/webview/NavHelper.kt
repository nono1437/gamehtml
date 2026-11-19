package com.game4399.app.webview

import android.net.Uri
import com.game4399.app.data.GameType
import com.game4399.app.data.PrefsManager

/**
 * 导航与 URL 工具：构造 4399 各类页面地址、判断 SWF、生成内置播放器地址。
 */
object NavHelper {

    /** 4399 移动版主页 */
    const val URL_4399_MOBILE = "https://h.4399.com/"
    /** 4399 PC 版（含 Flash 游戏） */
    const val URL_4399_PC = "https://www.4399.com/"
    /** 全部分类 */
    const val URL_4399_CATEGORY = "https://h.4399.com/wap/allcategory.htm"
    /** 热门小游戏榜 */
    const val URL_4399_HOT = "https://h.4399.com/wap/xyxRank.htm"

    /** 根据 ID 与类型构造 4399 游戏页 */
    fun gameUrl(id: String, type: GameType): String = when (type) {
        GameType.FLASH -> "https://www.4399.com/flash/$id.htm"
        GameType.H5    -> "https://h.4399.com/play/$id.htm"
        GameType.URL   -> id
        GameType.LOCAL_SWF -> id
    }

    /** 判断是否为 SWF 资源 */
    fun isSwf(url: String): Boolean =
        Uri.parse(url).path?.endsWith(".swf", ignoreCase = true) == true

    /** 判断是否为本地文件 URI（content:// 或 file://） */
    fun isLocalFile(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")

    /** 构造内置 Flash 播放器地址（根据引擎选择不同播放器页面） */
    fun playerUrl(swfUrl: String, base: String? = null, title: String? = null): String {
        val seer2 = Seer2Assets.isLauncher(swfUrl)
        val engine = if (seer2) seer2Engine else PrefsManager.flashEngine
        val playback = when {
            seer2 -> Seer2Assets.MOVIE
            engine == "waflash" -> swfUrl
            else -> RemoteSwfProxy.wrap(swfUrl)
        }
        val resourceBase = when {
            seer2 -> Seer2Assets.BASE
            swfUrl.startsWith(GameWebViewClient.LOCAL_MOVIE_URL) -> "https://flash.local/_local/"
            else -> base?.takeUnless { Uri.parse(it).host == "flash.local" }
                ?: runCatching { java.net.URL(java.net.URL(swfUrl), ".").toString() }.getOrNull()
        }
        val player = if (engine == "waflash") "waflash.html" else "player.html"
        return Uri.parse("https://flash.local/$player").buildUpon()
            .appendQueryParameter("swf", playback)
            .apply {
                resourceBase?.let { appendQueryParameter("base", it) }
                base?.let { appendQueryParameter("referer", it) }
                (title ?: if (seer2) "约瑟传说" else null)?.let { appendQueryParameter("title", it) }
                if (seer2) appendQueryParameter("profile", "seer2")
                if (engine != "waflash") {
                    appendQueryParameter("engine", engine)
                    appendQueryParameter("autoplay", if (seer2 || PrefsManager.isFlashAutoplay) "on" else "off")
                    if (engine != "swf2js" && !seer2) {
                        appendQueryParameter("cdn", RuffleInjector.scriptUrl())
                        appendQueryParameter("path", RuffleInjector.publicPath())
                    }
                }
            }.build().toString()
    }

    internal val seer2Engine: String
        get() = PrefsManager.sp.getString("seer2_flash_engine", "ruffle") ?: "ruffle"

    internal fun isSeer2Player(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        return uri.scheme == "https" && uri.host == "flash.local" &&
            uri.path in setOf("/player.html", "/waflash.html") && uri.getQueryParameter("profile") == "seer2"
    }

    /** 规范化用户输入：自动补 https://、识别纯数字为 4399 游戏 ID */
    fun normalizeInput(input: String): Pair<String, GameType> {
        val trimmed = input.trim()
        // 纯数字 → 4399 游戏 ID（默认 Flash）
        if (trimmed.matches(Regex("^\\d+$"))) {
            return gameUrl(trimmed, GameType.FLASH) to GameType.FLASH
        }
        // 已带协议
        var url = trimmed
        if (!url.startsWith("http://") && !url.startsWith("https://") &&
            !url.startsWith("file://")) {
            url = "https://$url"
        }
        return url to GameType.URL
    }
}
