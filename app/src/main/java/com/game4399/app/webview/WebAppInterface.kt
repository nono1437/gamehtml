package com.game4399.app.webview

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.game4399.app.R
import com.game4399.app.autoplay.GameAutoConfig
import com.game4399.app.autoplay.SwfCandidate
import com.game4399.app.data.FavoriteStore
import com.game4399.app.data.PrefsManager
import com.game4399.app.input.ActionButtonView

/**
 * 注入到 WebView 的 JS 接口（window.Android）。
 * 提供给网页调用原生的能力：Toast、收藏、震动、获取当前 URL 等。
 */
class WebAppInterface(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** SWF 提取结果回调（由 GameActivity 设置） */
    @Volatile
    var swfExtractCallback: ((String) -> Unit)? = null

    @JavascriptInterface
    fun toast(msg: String?) {
        handler.post { Toast.makeText(context, msg ?: "", Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs.coerceIn(1, 500).toLong())
    }

    @JavascriptInterface
    fun addFavorite(url: String?, title: String?) {
        if (url.isNullOrEmpty()) return
        FavoriteStore.add(url, title ?: url)
        handler.post { Toast.makeText(context, "已加入收藏", Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun log(tag: String?, msg: String?) {
        Log.d("WebApp:${tag ?: "JS"}", msg ?: "")
    }

    /**
     * 打开 SWF 播放器（WAFlash 检测脚本调用）。
     * 根据当前 Flash 引擎设置跳转到对应的播放器页面。
     */
    @JavascriptInterface
    fun openSwf(swfUrl: String?, pageUrl: String?) {
        if (swfUrl.isNullOrEmpty()) return
        Log.d("WebApp:WAFlash", "openSwf: $swfUrl (from: $pageUrl)")
        handler.post { openSwfOnMainThread(swfUrl, pageUrl) }
    }

    /**
     * nono 二改：若这个 4399 游戏以前已经成功识别过 SWF，就跳过网页检测页直接播放。
     * 返回 true 表示命中缓存，JS 不需要再次扫描。
     */
    @JavascriptInterface
    fun openCachedGame(pageUrl: String?): Boolean {
        val page = pageUrl ?: return false
        val gameId = GameAutoConfig.gameIdFromUrl(page) ?: return false
        val prefix = "nono_game_${gameId}_"
        val swfUrl = PrefsManager.sp.getString(prefix + "swf", null)?.takeIf { it.isNotBlank() }
            ?: return false
        val storedKeys = PrefsManager.sp.getString(prefix + "keys", "")
            .orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // 已知游戏配置优先，可自动修正旧版本误缓存的其他玩家按键。
        val knownKeys = GameAutoConfig.actionKeysForGame(gameId, "")
        val keys = if (knownKeys.isNotEmpty()) knownKeys else storedKeys
        if (keys != storedKeys) {
            PrefsManager.sp.edit().putString(prefix + "keys", keys.joinToString(",")).apply()
        }
        if (keys.isNotEmpty()) applyDetectedKeys(keys)
        Log.d("WebApp:AutoPlay", "缓存命中 game=$gameId swf=$swfUrl keys=$keys")
        handler.post { openSwfOnMainThread(swfUrl, page) }
        return true
    }

    /**
     * nono 二改：接收网页自动扫描到的 SWF 候选，过滤 4399 的 cell/objtest/评论组件，
     * 高置信度时直接打开真正游戏 SWF，并从“操作说明”里自动映射玩家 1 的按键。
     */
    @JavascriptInterface
    fun autoPlaySwf(json: String?, pageUrl: String?, pageText: String?): Boolean {
        if (json.isNullOrBlank() || pageUrl.isNullOrBlank()) return false
        val candidates: List<SwfCandidate> = try {
            val arr = org.json.JSONArray(json)
            val parsed = mutableListOf<SwfCandidate>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim()
                if (url.isNotEmpty()) {
                    parsed.add(
                        SwfCandidate(
                            url = url,
                            title = obj.optString("title", ""),
                            size = obj.optString("size", "")
                        )
                    )
                }
            }
            parsed
        } catch (e: Exception) {
            Log.w("WebApp:AutoPlay", "候选解析失败: ${e.message}")
            return false
        }

        val best = GameAutoConfig.selectBestSwf(candidates) ?: return false
        val gameId = GameAutoConfig.gameIdFromUrl(pageUrl)
        val keys = GameAutoConfig.actionKeysForGame(gameId, pageText.orEmpty())
        if (gameId != null) {
            val prefix = "nono_game_${gameId}_"
            PrefsManager.sp.edit()
                .putString(prefix + "swf", best.url)
                .putString(prefix + "keys", keys.joinToString(","))
                .apply()
        }
        if (keys.isNotEmpty()) applyDetectedKeys(keys)

        Log.d("WebApp:AutoPlay", "自动识别 game=$gameId swf=${best.url} keys=$keys")
        handler.post {
            Toast.makeText(context, "已自动识别游戏，正在进入…", Toast.LENGTH_SHORT).show()
            openSwfOnMainThread(best.url, pageUrl)
        }
        return true
    }

    private fun applyDetectedKeys(keys: List<String>) {
        val selected = keys.distinct().take(8)
        if (selected.isEmpty()) return
        val count = selected.size.coerceAtLeast(2)
        val editor = PrefsManager.sp.edit().putInt("gamepad_key_count", count)
        for (i in 0 until count) {
            if (i < selected.size) {
                editor.putString("gamepad_key_${i + 1}", selected[i])
                editor.putBoolean("gamepad_key_${i + 1}_visible", true)
            } else {
                editor.putBoolean("gamepad_key_${i + 1}_visible", false)
            }
        }
        for (i in count until 18) {
            editor.putBoolean("gamepad_key_${i + 1}_visible", false)
        }
        editor.apply()

        handler.post {
            (context as? Activity)
                ?.findViewById<ActionButtonView>(R.id.actionButtons)
                ?.apply {
                    visibility = android.view.View.VISIBLE
                    requestLayout()
                    invalidate()
                }
        }
    }

    private fun openSwfOnMainThread(swfUrl: String, pageUrl: String?) {
        val playerUrl = NavHelper.playerUrl(swfUrl, pageUrl, null)
        if (context is com.game4399.app.GameActivity) {
            context.loadSwfInWebView(playerUrl)
        }
    }

    @JavascriptInterface
    fun finish() {
        if (context is Activity) handler.post { context.finish() }
    }

    /**
     * JS 嗅探器回调：报告在页面中发现的 SWF URL 列表。
     * @param json JSON 数组字符串，如 [{"url":"...","title":"...","size":"12MB"}]
     */
    @JavascriptInterface
    fun onSwfFound(json: String?) {
        if (json.isNullOrEmpty()) return
        Log.d("WebApp:SwfExtract", "发现 SWF: $json")
        swfExtractCallback?.let { cb ->
            handler.post { cb(json) }
        }
    }

    /**
     * 读取本地 SWF 文件内容（Base64），供 JS 创建 Blob URL。
     * 绕过 WebView 对 content:// URI 的跨域限制。
     */
    @JavascriptInterface
    fun readLocalSwf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        return try {
            Log.d("WebApp:LocalSwf", "读取本地文件: $uri")
            val parsed = android.net.Uri.parse(uri)
            val data = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw java.io.IOException("无法打开文件流")
            Log.d("WebApp:LocalSwf", "读取完成: ${data.size} bytes")
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("WebApp:LocalSwf", "读取失败: ${e.message}")
            null
        }
    }
}
