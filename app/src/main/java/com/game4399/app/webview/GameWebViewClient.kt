package com.game4399.app.webview

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.game4399.app.data.PrefsManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL

/**
 * 游戏页面 WebView 客户端：
 * 1. onPageFinished 注入 Flash 引擎（Ruffle / swf2js / WAFlash）
 * 2. 拦截 .swf 链接 → 跳转内置播放器页
 * 3. shouldInterceptRequest：拦截 flash.local 虚拟域名，从 assets 返回引擎文件
 * 4. 错误回调（仅主框架错误才显示错误页）
 */
open class GameWebViewClient(
    private val callback: Callback,
    private val diagnostics: FlashDiagnostics = FlashDiagnostics()
) : WebViewClient() {

    interface Callback {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onProgress(progress: Int)
        fun onError(url: String?, errorCode: Int, description: String?)
        fun onSwfIntercepted(swfUrl: String, pageUrl: String)
        fun shouldInjectRuffle(url: String?): Boolean
        /** 获取本地 SWF 文件的真实 URI */
        fun getLocalSwfUri(): String?
        fun getLocalResourceTree(): Uri? = null
    }

    @Volatile private var pageUrl: String? = null

    /** 常见广告域名 */
    private val adHosts = setOf(
        "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
        "ad.4399.com", "stat.4399.com", "analytics.4399.com"
    )

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (request.isForMainFrame && PrefsManager.isFlashEnabled &&
            (Seer2Assets.isLauncher(url) || NavHelper.isSwf(url))) {
            callback.onSwfIntercepted(url, view.url ?: url)
            return true
        }
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val url = uri.toString()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()

        if (PrefsManager.isBlockAds && adHosts.any { host == it || host.endsWith(".$it") }) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }

        if (uri.scheme == "https" && host == "flash.local") {
            val source = pageUrl
            if (path.startsWith(Seer2Assets.PREFIX)) {
                val upstream = Seer2Assets.upstream(uri.encodedPath.orEmpty(), uri.encodedQuery)
                if (upstream == null || !isPlayer(source) || request.isForMainFrame) {
                    diagnostics.record("已阻止资源", url, 403)
                    return assetResponse(403)
                }
                return interceptRemote(upstream, request, Seer2Assets.ENTRY, seer2 = true)
            }
            RemoteSwfProxy.unwrap(url)?.let {
                if (!isPlayer(source) || request.isForMainFrame) return assetResponse(403)
                val parent = Uri.parse(source).getQueryParameter("referer")
                    ?: Uri.parse(source).getQueryParameter("base")
                return interceptRemote(it, request, parent)
            }
            if (path == "/local.swf" || path.startsWith(LOCAL_PREFIX)) {
                if (!isPlayer(source) || request.isForMainFrame) return assetResponse(403)
                return interceptLocal(view, url, path)
            }
            return interceptAsset(view, path.removePrefix("/"))
        }

        if (url.startsWith("file:///android_asset/waflash/")) {
            return interceptAsset(view, path.removePrefix("/android_asset/"))
        }

        val is4399Asset = (host == "4399.com" || host.endsWith(".4399.com")) &&
            listOf("/dw-", "flash_tm3", "flash20").any(path::contains)
        if (uri.scheme in setOf("http", "https") && (NavHelper.isSwf(url) || is4399Asset)) {
            return interceptRemote(url, request, pageUrl)
        }
        return null
    }

    private fun isPlayer(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        return uri.scheme == "https" && uri.host == "flash.local" &&
            uri.path in setOf("/player.html", "/waflash.html")
    }

    /** 构建 Flash 支持伪造 + Ruffle/WAFlash 注入脚本（公开供 GameActivity 在 onPageFinished 兜底使用） */
    fun buildFlashInjectScript(pageUrl: String): String {
        val isWaflash = PrefsManager.flashEngine == "waflash"
        return """
        <script>
        (function(){
          if(window.__flashPolyfilled)return;window.__flashPolyfilled=true;
          // === 1. 伪造 Flash 插件支持（必须在页面 JS 之前） ===
          try {
            var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
              0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
            fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
            fp.item = function(i){ return i === 0 ? fp : null; };
            fp.refresh = function(){};
            var _plugins = navigator.plugins || {};
            // 保持原有方法
            if (_plugins.namedItem) { fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : _plugins.namedItem.call(_plugins, n); }; }
            if (_plugins.item) { fp.item = function(i){ return i === 0 ? fp : _plugins.item.call(_plugins, i); }; }
            Object.defineProperty(navigator,'plugins',{
              get:function(){
                try {
                  if (!_plugins['Shockwave Flash']) {
                    _plugins['Shockwave Flash'] = fp;
                    _plugins[0] = fp;
                  }
                  _plugins.length = Math.max(_plugins.length || 0, 1);
                } catch(e) {}
                return _plugins;
              },
              configurable: true
            });
            var fm = {type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp};
            var _mimes = navigator.mimeTypes || {};
            Object.defineProperty(navigator,'mimeTypes',{
              get:function(){
                try { if (!_mimes['application/x-shockwave-flash']) _mimes['application/x-shockwave-flash'] = fm; } catch(e) {}
                return _mimes;
              },
              configurable: true
            });
            window.ActiveXObject = function(n){if(n&&/ShockwaveFlash/i.test(n))return {SetVariable:function(){},Variable:function(){return ''}};throw new Error('x');};
          } catch(e) {}

          // === 2. 伪造 document.referrer ===
          try {
            Object.defineProperty(document,'referrer',{get:function(){return '$pageUrl';},configurable:true});
          } catch(e) {}

          // === 3. Ruffle polyfill（Ruffle 模式） ===
          ${if (!isWaflash) """
          window.RufflePlayer = window.RufflePlayer || {};
          window.RufflePlayer.config = {
            autoplay: 'on',
            unmuteOverlay: 'visible',
            backgroundColor: '#000000',
            letterbox: 'on',
            polyfills: true,
            maxExecutionDuration: 30,
            logLevel: 'warn'
          };
          var ruffleScript = document.createElement('script');
          ruffleScript.src = 'https://flash.local/ruffle/ruffle.js';
          ruffleScript.onload = function() {
            console.log('[Ruffle] 引擎加载完成');
          };
          document.head.appendChild(ruffleScript);

          // === nono 二改：4399 Flash 页自动找出真正游戏 SWF ===
          // 命中历史缓存时直接进入；第一次则扫描 DOM / Performance / HTML 源码，
          // 交给 Android 端做保守打分，避免把 cell.swf / objtest.swf / 评论组件当成游戏。
          if (/4399\.com\/flash\/[0-9]+/i.test(window.location.href)) {
            (function(){
              var tries = 0;
              function scanGameSwf(){
                tries++;
                try {
                  if (window.Android && window.Android.openCachedGame &&
                      window.Android.openCachedGame(window.location.href)) return;
                } catch(e) {}

                var seen = {}, found = [];
                function add(raw, title) {
                  if (!raw || String(raw).toLowerCase().indexOf('.swf') < 0) return;
                  try { raw = new URL(String(raw), window.location.href).href; } catch(e) { raw = String(raw); }
                  if (seen[raw]) return;
                  seen[raw] = true;
                  found.push({url: raw, title: title || ''});
                }

                try {
                  var nodes = document.querySelectorAll('object,embed,param,a,script');
                  var attrs = ['src','data','href','value'];
                  for (var i = 0; i < nodes.length; i++) {
                    for (var j = 0; j < attrs.length; j++) add(nodes[i].getAttribute(attrs[j]), nodes[i].id || nodes[i].title || '');
                  }
                } catch(e) {}
                try {
                  var entries = performance.getEntriesByType('resource');
                  for (var k = 0; k < entries.length; k++) add(entries[k].name, '');
                } catch(e) {}
                try {
                  var html = document.documentElement ? document.documentElement.innerHTML : '';
                  var matches = html.match(/(?:https?:\/\/|\/|\.\/|\.\.\/)[^\"'<>\\s]+\.swf(?:\?[^\"'<>\\s]*)?/ig) || [];
                  for (var m = 0; m < matches.length; m++) add(matches[m], '');
                } catch(e) {}

                var text = '';
                try { text = document.body ? document.body.innerText.slice(0, 120000) : ''; } catch(e) {}
                var accepted = false;
                try {
                  accepted = !!(window.Android && window.Android.autoPlaySwf &&
                    window.Android.autoPlaySwf(JSON.stringify(found), window.location.href, text));
                } catch(e) {}
                if (!accepted && tries < 3) setTimeout(scanGameSwf, 1200);
              }
              setTimeout(scanGameSwf, 650);
            })();
          }
          """ else """
          // === WAFlash 模式：hook Flash 创建，跳转到 WAFlash 播放器 ===
          var __wafRedirected = false;
          function __wafRedirect(swfUrl, baseUrl) {
            if (__wafRedirected || !swfUrl) return;
            var isSwf = /\.swf/i.test(swfUrl) || /\/dw-\d+/i.test(swfUrl) ||
                        /flash\d*\//i.test(swfUrl) || /mm\.4399\.com/i.test(swfUrl);
            if (!isSwf) return;
            __wafRedirected = true;
            try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
            console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
            if (window.Android && window.Android.openSwf) {
              window.Android.openSwf(swfUrl, baseUrl || window.location.href);
            } else {
              window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
            }
          }
          // hook swfobject.embedSWF
          if (window.swfobject && window.swfobject.embedSWF) {
            var oe = window.swfobject.embedSWF;
            window.swfobject.embedSWF = function(){__wafRedirect(arguments[0],window.location.href);return oe.apply(this,arguments);};
          } else {
            var _swo; Object.defineProperty(window,'swfobject',{configurable:true,get:function(){return _swo;},set:function(v){_swo=v;if(v&&v.embedSWF){var o=v.embedSWF;v.embedSWF=function(){__wafRedirect(arguments[0],window.location.href);return o.apply(this,arguments);};}}});
          }
          // hook createFlash (mflash-player)
          function __hookCF(obj){if(!obj||!obj.createFlash||obj.__wafH)return;obj.__wafH=true;var o=obj.createFlash;obj.createFlash=function(){var u=null;if(typeof arguments[0]==='string')u=arguments[0];else if(arguments[0]&&typeof arguments[0]==='object')u=arguments[0].url||arguments[0].src||arguments[0].swf||arguments[0].movie;if(u)__wafRedirect(u,window.location.href);return o.apply(this,arguments);};}
          if(window['mflash-player'])__hookCF(window['mflash-player']);
          var _mp;Object.defineProperty(window,'mflash-player',{configurable:true,get:function(){return _mp;},set:function(v){_mp=v;__hookCF(v);}});
          // hook AC_FL_RunContent
          if(window.AC_FL_RunContent){var oAC=window.AC_FL_RunContent;window.AC_FL_RunContent=function(){var a=Array.prototype.slice.call(arguments);for(var i=0;i<a.length-1;i++){if((a[i]==='src'||a[i]==='movie')&&a[i+1])__wafRedirect(a[i+1],window.location.href);}return oAC.apply(this,arguments);};}
          // DOM 检测
          function __checkFlash(){
            if(__wafRedirected)return;
            var sel='object[type="application/x-shockwave-flash"],embed[type="application/x-shockwave-flash"],object[data$=".swf" i],embed[src$=".swf" i],object[classid*="D27CDB6E" i]';
            var els=document.querySelectorAll(sel);
            for(var i=0;i<els.length;i++){var s=els[i].getAttribute('data')||els[i].getAttribute('src')||'';if(!s){var ps=els[i].querySelectorAll('param[name="movie"],param[name="src"]');for(var j=0;j<ps.length;j++){var v=ps[j].getAttribute('value')||'';if(v){s=v;break;}}}if(s)__wafRedirect(s,window.location.href);}
          }
          __checkFlash();
          if(window.MutationObserver){var mo=new MutationObserver(function(){if(__wafRedirected){mo.disconnect();return;}__checkFlash();});try{mo.observe(document.documentElement||document.body||document,{childList:true,subtree:true});}catch(e){}setTimeout(function(){mo.disconnect();},15000);}
          """}
        })();
        </script>
        """.trimIndent()
    }

    private fun interceptLocal(view: WebView, url: String, path: String): WebResourceResponse {
        val start = SystemClock.elapsedRealtime()
        val rootMovie = path == "/local.swf" || path == LOCAL_MOVIE_PATH
        val status: Int
        val data = try {
            val source = callback.getLocalSwfUri()?.let(Uri::parse)
            val stream = if (rootMovie) {
                source?.let(view.context.contentResolver::openInputStream)
            } else {
                val parts = path.removePrefix(LOCAL_PREFIX).split('/')
                if (parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) {
                    return assetResponse(403)
                }
                val tree = callback.getLocalResourceTree()
                if (tree != null) {
                    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(view.context, tree)
                    val file = parts.fold(root) { parent, name -> parent?.takeIf { it.isDirectory }?.findFile(name) }
                    file?.takeIf { it.isFile }?.let { view.context.contentResolver.openInputStream(it.uri) }
                } else if (source?.scheme == "file") {
                    val root = File(requireNotNull(source.path)).canonicalFile.parentFile
                    val file = File(root, parts.joinToString("/")).canonicalFile
                    file.takeIf { root != null && it.path.startsWith(root.path.trimEnd('/') + "/") && it.isFile }
                        ?.inputStream()
                } else null
            }
            stream?.use(::readAsset)
        } catch (_: Exception) {
            null
        }
        status = when {
            data == null -> 404
            (rootMovie || path.endsWith(".swf", true)) && !FlashPayload.isSwf(data, data.size.toLong()) -> 415
            FlashPayload.looksLikeHtml(data) -> 415
            else -> 200
        }
        diagnostics.record(if (status == 200) "本地资源已交付" else "本地资源失败", url,
            status, data?.size?.toLong(), SystemClock.elapsedRealtime() - start)
        return assetResponse(status, data.takeIf { status == 200 })
    }

    /** Buffer a complete, bounded asset so a truncated response never looks like a successful download. */
    private fun readAsset(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > 64 * 1024 * 1024) throw java.io.IOException("Asset too large")
            output.write(buffer, 0, count)
        }
    }

    private fun interceptRemote(url: String, request: WebResourceRequest, source: String?, seer2: Boolean = false): WebResourceResponse {
        val method = request.method
        if (method == "OPTIONS" && !seer2) return assetResponse(200, cors = "*")
        if (method != "GET") {
            diagnostics.record("已阻止非 GET 资源请求", url, 405)
            return assetResponse(405, cors = if (seer2) null else "*")
        }

        val started = SystemClock.elapsedRealtime()
        var httpStatus: Int? = null
        try {
            val cookieLookup: ((String) -> String?)? = if (seer2) null else { address ->
                CookieManager.getInstance().getCookie(address)
            }
            val conn = FlashHttp.open(
                url, source,
                request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                    ?: userAgent,
                accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value,
                cookies = cookieLookup,
                onRedirect = { from, code, followed ->
                    diagnostics.record(if (followed) "重定向" else "已阻止重定向", from, code,
                        millis = SystemClock.elapsedRealtime() - started)
                },
                allowRedirect = { target ->
                    !seer2 || (target.host.equals("seer2.61.com", true) && target.port == -1 &&
                        Seer2Assets.upstream(Seer2Assets.PREFIX + target.path.removePrefix("/"), target.query) != null)
                }
            )
            try {
                val status = conn.responseCode
                httpStatus = status
                if (status != 200) {
                    diagnostics.record("下载失败", conn.url.toString(), status,
                        millis = SystemClock.elapsedRealtime() - started)
                    return assetResponse(status.takeIf { it in 400..599 } ?: 502, cors = if (seer2) null else "*")
                }
                val data = conn.inputStream.use(::readAsset)
                val expectedSize = conn.getHeaderField("Content-Length")?.toLongOrNull()
                val complete = expectedSize == null || expectedSize == data.size.toLong()
                val valid = complete && if (seer2) Seer2Assets.validPayload(conn.url.path, data)
                    else FlashPayload.isSwf(data, data.size.toLong())
                val outcome = when {
                    !complete -> "资源下载不完整"
                    !valid -> "资源格式无效"
                    else -> "下载并交付 WebView"
                }
                diagnostics.record(outcome, conn.url.toString(),
                    status, data.size.toLong(), SystemClock.elapsedRealtime() - started)
                return assetResponse(if (valid) 200 else 415, data.takeIf { valid },
                    if (seer2) null else "*")
            } finally {
                conn.disconnect()
            }
        } catch (error: Exception) {
            val type = when (error) {
                is javax.net.ssl.SSLException -> "TLS 验证失败"
                is java.net.SocketTimeoutException -> "请求超时"
                is java.net.UnknownHostException -> "DNS 失败"
                else -> "资源请求失败"
            }
            diagnostics.record(type, url, httpStatus, millis = SystemClock.elapsedRealtime() - started)
            return assetResponse(502, cors = if (seer2) null else "*")
        }
    }

    private fun assetResponse(status: Int, data: ByteArray? = null, cors: String? = null): WebResourceResponse {
        val reason = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            415 -> "Unsupported Media Type"
            else -> "Asset Request Failed"
        }
        val bytes = data ?: ByteArray(0)
        val headers = mutableMapOf(
            "Cache-Control" to "no-store",
            "Content-Length" to bytes.size.toString(),
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "sandbox"
        )
        if (cors != null) {
            headers["Access-Control-Allow-Origin"] = cors
            headers["Access-Control-Allow-Methods"] = "GET, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "*"
        }
        val mime = if (FlashPayload.hasSignature(bytes)) "application/x-shockwave-flash" else "application/octet-stream"
        return WebResourceResponse(mime, null, status, reason, headers, ByteArrayInputStream(bytes))
    }

    /** 从 assets 读取文件并返回带 CORS 头的 WebResourceResponse */
    private fun interceptAsset(view: WebView, assetPath: String): WebResourceResponse? {
        return try {
            val input = view.context.assets.open(assetPath)
            val (mime, charset) = when {
                assetPath.endsWith(".wasm") -> "application/wasm" to null
                assetPath.endsWith(".js") -> "application/javascript" to "UTF-8"
                assetPath.endsWith(".html") -> "text/html" to "UTF-8"
                assetPath.endsWith(".css") -> "text/css" to "UTF-8"
                assetPath.endsWith(".ttf") -> "font/ttf" to null
                assetPath.endsWith(".woff") -> "font/woff" to null
                assetPath.endsWith(".woff2") -> "font/woff2" to null
                assetPath.endsWith(".otf") -> "font/otf" to null
                assetPath.endsWith(".data") -> "application/octet-stream" to null
                else -> "application/octet-stream" to null
            }
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            WebResourceResponse(mime, charset, 200, "OK", headers, input)
        } catch (e: Exception) {
            android.util.Log.w("GameWebViewClient", "asset not found: $assetPath", e)
            null
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        pageUrl = url
        callback.onPageStarted(url)

        // 注入按键安全钩子：页面失焦时自动释放所有按键（所有页面通用）
        view?.evaluateJavascript(KEY_SAFETY_SCRIPT, null)

        // 4399 页面：伪造 document.referrer 绕过防盗链检测 + IE 兼容模式伪造
        if (url != null && url.contains("4399.com")) {
            view?.evaluateJavascript(REFERER_SPOOF_SCRIPT, null)
            if (PrefsManager.uaMode == "ie_compat") {
                view?.evaluateJavascript(IE_COMPAT_SCRIPT, null)
            }
        }

        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)

        // Flash 页面：最先注入 Flash 支持伪造（在页面 JS 执行前）
        // 让 4399 检测到浏览器"有 Flash 插件"，从而创建 <object> 元素
        // 之后 Ruffle polyfill 会替换 <object> 为 Canvas 播放器
        if (isFlashPage) {
            view?.evaluateJavascript(FLASH_FAKE_SUPPORT_SCRIPT, null)
            view?.evaluateJavascript(RuffleInjector.configScript(), null)
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
            // 注入 iframe 监控：游戏可能加载在 iframe 中
            view?.evaluateJavascript(IFRAME_INJECT_SCRIPT, null)
        }

        // 所有网页注入 viewport 缩放支持（不限 4399）
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.fullInjection(), null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        if (isFlashPage) {
            view?.evaluateJavascript(CSS_INJECTION, null)
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
        callback.onPageFinished(url)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        diagnostics.record("WebView TLS 验证失败 (${error?.primaryError ?: -1})", error?.url)
        handler?.cancel()
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        diagnostics.record("WebView HTTP 错误", request.url.toString(), response.statusCode)
        super.onReceivedHttpError(view, request, response)
    }

    /**
     * 新版错误回调（API 23+）：仅主框架错误才通知 callback。
     */
    override fun onReceivedError(
        view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        diagnostics.record("WebView 错误 (${error?.errorCode ?: -1})", request?.url?.toString())
        if (request?.isForMainFrame == true) {
            callback.onError(
                request.url?.toString(),
                error?.errorCode ?: -1,
                error?.description?.toString()
            )
        }
    }

    /**
     * 废弃版错误回调：不触发 callback.onError，避免子资源错误误显示错误页。
     */
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?, errorCode: Int, description: String?, failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    /** 根据用户缩放设置构建 viewport 脚本 */
    private fun buildViewportScript(): String {
        val scale = if (PrefsManager.pageZoomMode == "manual") {
            PrefsManager.pageZoomManual / 100.0
        } else {
            -1.0
        }
        return if (scale > 0) {
            """
            (function(){
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) { meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); }
              var s = $scale;
              // 不限制缩放范围：minimum-scale=0.01 允许无限缩小，maximum-scale=1000 允许无限放大
              meta.content = 'width=device-width, initial-scale=' + s + ', minimum-scale=0.01, maximum-scale=1000.0, user-scalable=yes';
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) { meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); }
              var sw = window.screen.width || 360;
              var scale = Math.min(1, sw / 1200);
              scale = Math.max(0.25, scale);
              // 不限制缩放范围：minimum-scale=0.01 允许无限缩小，maximum-scale=1000 允许无限放大
              meta.content = 'width=device-width, initial-scale=' + scale + ', minimum-scale=0.01, maximum-scale=1000.0, user-scalable=yes';
            })();
            """.trimIndent()
        }
    }

    companion object {
        private const val LOCAL_PREFIX = "/_local/"
        private const val LOCAL_MOVIE_PATH = "${LOCAL_PREFIX}game.swf"
        const val LOCAL_MOVIE_URL = "https://flash.local$LOCAL_MOVIE_PATH"
        private const val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"


        /**
         * 按键安全钩子：在页面失焦（blur/visibilitychange）时自动释放所有按键。
         * 这是 Android 端 releaseAllKeys() 的补充保险——
         * 即使 Android 侧漏调，网页侧也能自行清理，防止 Ruffle 角色持续移动。
         */
        private const val KEY_SAFETY_SCRIPT = """
            (function(){
              if (window.__keySafetyHook) return;
              window.__keySafetyHook = true;
              // 追踪所有 keydown 的 keyCode
              var pressed = {};
              window.addEventListener('keydown', function(e){
                pressed[e.keyCode] = true;
              }, true);
              window.addEventListener('keyup', function(e){
                delete pressed[e.keyCode];
              }, true);
              // 释放所有已记录的按键
              function releaseAll() {
                Object.keys(pressed).forEach(function(kc) {
                  var code = parseInt(kc);
                  var keyMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:' ',13:'Enter',9:'Tab',27:'Escape',17:'Control',16:'Shift',18:'Alt',
                    65:'a',66:'b',67:'c',68:'d',69:'e',70:'f',71:'g',72:'h',73:'i',74:'j',
                    75:'k',76:'l',77:'m',78:'n',79:'o',80:'p',81:'q',82:'r',83:'s',84:'t',
                    85:'u',86:'v',87:'w',88:'x',89:'y',90:'z',
                    48:'0',49:'1',50:'2',51:'3',52:'4',53:'5',54:'6',55:'7',56:'8',57:'9'
                  };
                  var keyVal = keyMap[code] || '';
                  var codeMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:'Space',13:'Enter',9:'Tab',27:'Escape',
                    65:'KeyA',68:'KeyD',69:'KeyE',70:'KeyF',81:'KeyQ',82:'KeyR',
                    83:'KeyS',87:'KeyW',88:'KeyX',90:'KeyZ'
                  };
                  var codeVal = codeMap[code] || '';
                  try {
                    var evt = new KeyboardEvent('keyup', {
                      key: keyVal, code: codeVal, bubbles: true, cancelable: true
                    });
                    Object.defineProperty(evt, 'keyCode', {get: function(){return code;}});
                    Object.defineProperty(evt, 'which', {get: function(){return code;}});
                    document.dispatchEvent(evt);
                  } catch(e) {}
                  delete pressed[kc];
                });
              }
              window.addEventListener('blur', releaseAll);
              document.addEventListener('visibilitychange', function() {
                if (document.hidden) releaseAll();
              });
            })();
        """

        /**
         * 伪造 Flash 插件支持：让 4399 等页面检测到浏览器"有 Flash 插件"。
         * 必须在页面 JS 执行前注入（onPageStarted）。
         * 伪造 navigator.plugins["Shockwave Flash"] 和 navigator.mimeTypes。
         */
        private const val FLASH_FAKE_SUPPORT_SCRIPT = """
            (function(){
              if (window.__flashFaked) return;
              window.__flashFaked = true;
              try {
                // 伪造 navigator.plugins（含 namedItem/item 方法，Ruffle 需要调用）
                var fakePlugin = {
                  name: 'Shockwave Flash',
                  filename: 'libflashplayer.so',
                  description: 'Shockwave Flash 32.0 r0',
                  length: 1,
                  0: { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash' }
                };
                fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : null; };
                fakePlugin.item = function(i) { return i === 0 ? fakePlugin : null; };
                fakePlugin.refresh = function() {};
                var plugins = navigator.plugins || {};
                if (plugins.namedItem) { fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : plugins.namedItem.call(plugins, n); }; }
                if (plugins.item) { fakePlugin.item = function(i) { return i === 0 ? fakePlugin : plugins.item.call(plugins, i); }; }
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var p = plugins;
                    if (!p['Shockwave Flash']) {
                      try { p['Shockwave Flash'] = fakePlugin; p[0] = fakePlugin; } catch(e) {}
                    }
                    p.length = Math.max(p.length || 0, 1);
                    return p;
                  },
                  configurable: true
                });
                // 伪造 navigator.mimeTypes
                var fakeMime = { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash', enabledPlugin: fakePlugin };
                var mimes = navigator.mimeTypes || {};
                Object.defineProperty(navigator, 'mimeTypes', {
                  get: function() {
                    if (!mimes['application/x-shockwave-flash']) {
                      try { mimes['application/x-shockwave-flash'] = fakeMime; } catch(e) {}
                    }
                    return mimes;
                  },
                  configurable: true
                });
                // 伪造 ActiveXObject（IE 方式检测 Flash）
                window.ActiveXObject = function(name) {
                  if (name && /ShockwaveFlash/i.test(name)) return { SetVariable: function(){} };
                  throw new Error('Not supported');
                };
                console.log('[Flash] 已伪造 Flash 插件支持');
              } catch(e) { console.warn('[Flash] 伪造失败:', e); }
            })();
        """

        /**
         * iframe 注入监控：4399 游戏常加载在 iframe 中。
         * 监控 iframe 创建，将 Flash 引擎注入到 iframe 内部。
         */
        private const val IFRAME_INJECT_SCRIPT = """
            (function(){
              if (window.__iframeMonitor) return;
              window.__iframeMonitor = true;
              function injectIntoIframe(iframe) {
                try {
                  var doc = iframe.contentDocument || iframe.contentWindow.document;
                  if (!doc || doc.readyState === 'loading') {
                    setTimeout(function(){ injectIntoIframe(iframe); }, 200);
                    return;
                  }
                  // 检查是否已注入
                  if (doc.__flashFaked) return;
                  doc.__flashFaked = true;
                  // 注入 Flash 支持伪造
                  var s1 = doc.createElement('script');
                  s1.textContent = '(' + function(){
                    try {
                      var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
                        0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
                      Object.defineProperty(navigator,'plugins',{get:function(){return fp;},configurable:true});
                      Object.defineProperty(navigator,'mimeTypes',{get:function(){return {'application/x-shockwave-flash':{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp}};},configurable:true});
                      window.ActiveXObject = function(n){if(/ShockwaveFlash/i.test(n))return {SetVariable:function(){}};throw new Error('x');};
                    } catch(e){}
                  } + ')();';
                  doc.head.appendChild(s1);
                  // 注入 Ruffle 引擎
                  var engine = window.__ruffleLoaded ? 'ruffle' : (window.__swf2jsLoaded ? 'swf2js' : null);
                  if (engine === 'ruffle') {
                    var s2 = doc.createElement('script');
                    s2.src = 'https://flash.local/ruffle/ruffle.js';
                    s2.onload = function(){
                      try {
                        var r = doc.defaultView.RufflePlayer;
                        if (r && r.newest) { var inst = r.newest(); if (inst && inst.init) inst.init(); }
                      } catch(e){}
                    };
                    doc.head.appendChild(s2);
                  }
                  console.log('[Flash] 已注入 iframe');
                } catch(e) {
                  // 跨域 iframe 无法注入
                }
              }
              // 监控新创建的 iframe
              if (window.MutationObserver) {
                var observer = new MutationObserver(function(mutations){
                  mutations.forEach(function(m){
                    m.addedNodes.forEach(function(node){
                      if (node.tagName === 'IFRAME') injectIntoIframe(node);
                      if (node.querySelectorAll) {
                        var iframes = node.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) injectIntoIframe(iframes[i]);
                      }
                    });
                  });
                });
                observer.observe(document.documentElement, {childList: true, subtree: true});
              }
              // 检查已有的 iframe
              var existing = document.querySelectorAll('iframe');
              for (var i = 0; i < existing.length; i++) injectIntoIframe(existing[i]);
            })();
        """

        private const val REFERER_SPOOF_SCRIPT = """
            (function(){
              try {
                Object.defineProperty(document, 'referrer', {
                  get: function() { return 'https://www.4399.com/'; },
                  configurable: true
                });
              } catch(e) {}
            })();
        """

        /** IE 兼容模式伪造：4399 检测 IE 特有属性来判断兼容模式 */
        private const val IE_COMPAT_SCRIPT = """
            (function(){
              try {
                // 伪造 IE 的 documentMode（IE 独有属性）
                Object.defineProperty(document, 'documentMode', {
                  get: function() { return 11; }, configurable: true
                });
                // 伪造 IE 的 uniqueID
                Object.defineProperty(document, 'uniqueID', {
                  get: function() { return '_ie_id_'; }, configurable: true
                });
                // 伪造 IE 的 all 集合
                if (!document.all) {
                  document.all = document.getElementsByTagName('*');
                }
                // 伪装 navigator.userAgent 中含 Trident
                var origUA = navigator.userAgent;
                if (!/Trident/.test(origUA)) {
                  try {
                    Object.defineProperty(navigator, 'userAgent', {
                      get: function() {
                        return origUA + ' Trident/7.0; rv:11.0';
                      }, configurable: true
                    });
                  } catch(e) {}
                }
              } catch(e) {}
            })();
        """

        private const val CSS_INJECTION = """
            (function(){
              if (window.__cssInjected) return; window.__cssInjected = true;
              var s = document.createElement('style');
              s.textContent = [
                '.advertise,.ad_box,[id^="ad_"],[class*="advert"]{display:none!important;}',
                'object,embed{max-width:100%!important;}'
              ].join('\n');
              document.head.appendChild(s);
            })();
        """

        private const val FLASH_HIDE_SCRIPT = """
            (function(){
              if (window.__flashHideInjected) return; window.__flashHideInjected = true;
              try {
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var arr = [];
                    arr.namedItem = function(name) { return name === 'Shockwave Flash' ? { name: name } : null; };
                    arr.refresh = function() {};
                    arr.item = function(i) { return arr[i] || null; };
                    return arr;
                  }
                });
              } catch(e) {}
              function hideFlashTips() {
                var selectors = [
                  '[class*="noflash"]','[id*="noflash"]',
                  '[class*="no-flash"]','[id*="no-flash"]',
                  '.flash_tip','.flash-tip',
                  '#flash_tip','#flash-tip',
                  '.prompt-flash','.prompt_flash'
                ];
                document.querySelectorAll(selectors.join(',')).forEach(function(el){
                  el.style.display = 'none';
                });
              }
              hideFlashTips();
              if (window.MutationObserver) {
                var mo = new MutationObserver(function(){ hideFlashTips(); });
                try { mo.observe(document.documentElement, {childList:true, subtree:true}); } catch(e) {}
              }
            })();
        """

        /**
         * WAFlash SWF 检测脚本：
         * 1. Hook swfobject.embedSWF() — 标准 Flash 嵌入
         * 2. Hook AC_FL_RunContent() — 老式 Flash 嵌入
         * 3. Hook createFlash() — 4399 的 mflash-player API
         * 4. Hook fetch/XMLHttpRequest — 拦截非 .swf 扩展名的 SWF 加载
         * 5. MutationObserver 检测动态创建的 Flash DOM 元素
         */
        private const val WAFLASH_DETECT_SCRIPT = """
            (function(){
              if (window.__waflashDetect) return;
              window.__waflashDetect = true;
              var redirected = false;

              function redirectToPlayer(swfUrl, baseUrl) {
                if (redirected || !swfUrl) return;
                // 检测 SWF URL：支持 .swf 扩展名、4399 的 dw-XX 格式、flash 路径
                var isSwf = /\.swf/i.test(swfUrl) ||
                            /\/dw-\d+/i.test(swfUrl) ||
                            /flash\d*\//i.test(swfUrl) ||
                            /mm\.4399\.com/i.test(swfUrl);
                if (!isSwf) return;
                redirected = true;
                try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
                console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
                if (window.Android && window.Android.openSwf) {
                  window.Android.openSwf(swfUrl, baseUrl || window.location.href);
                } else {
                  window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
                }
              }

              // 1. Hook swfobject.embedSWF
              if (window.swfobject && window.swfobject.embedSWF) {
                var origEmbed = window.swfobject.embedSWF;
                window.swfobject.embedSWF = function() {
                  redirectToPlayer(arguments[0], window.location.href);
                  return origEmbed.apply(this, arguments);
                };
              } else {
                var _swfobject;
                Object.defineProperty(window, 'swfobject', {
                  configurable: true,
                  get: function() { return _swfobject; },
                  set: function(val) {
                    _swfobject = val;
                    if (val && val.embedSWF) {
                      var orig = val.embedSWF;
                      val.embedSWF = function() {
                        redirectToPlayer(arguments[0], window.location.href);
                        return orig.apply(this, arguments);
                      };
                    }
                  }
                });
              }

              // 2. Hook AC_FL_RunContent
              if (window.AC_FL_RunContent) {
                var origAC = window.AC_FL_RunContent;
                window.AC_FL_RunContent = function() {
                  var args = Array.prototype.slice.call(arguments);
                  for (var i = 0; i < args.length - 1; i++) {
                    if ((args[i] === 'src' || args[i] === 'movie') && args[i+1]) {
                      redirectToPlayer(args[i+1], window.location.href);
                    }
                  }
                  return origAC.apply(this, arguments);
                };
              }

              // 3. Hook createFlash — 4399 的 mflash-player API
              //    createFlash(swfUrl, options) 或 createFlash({url: swfUrl, ...})
              function hookCreateFlash(obj) {
                if (!obj || !obj.createFlash || obj.__waflashHooked) return;
                obj.__waflashHooked = true;
                var orig = obj.createFlash;
                obj.createFlash = function() {
                  var swfUrl = null;
                  if (typeof arguments[0] === 'string') {
                    swfUrl = arguments[0];
                  } else if (arguments[0] && typeof arguments[0] === 'object') {
                    swfUrl = arguments[0].url || arguments[0].src || arguments[0].swf ||
                             arguments[0].movie || arguments[0].data;
                  }
                  if (swfUrl) redirectToPlayer(swfUrl, window.location.href);
                  return orig.apply(this, arguments);
                };
                console.log('[WAFlash] 已 hook createFlash');
              }

              // 检查 mflash-player 是否已存在（注意：属性名含连字符，必须用方括号）
              if (window['mflash-player']) hookCreateFlash(window['mflash-player']);
              if (window.mflashplayer) hookCreateFlash(window.mflashplayer);
              if (window.MFlash) hookCreateFlash(window.MFlash);

              // 延迟 hook：4399 可能动态加载 mflash-player
              var _mflash;
              Object.defineProperty(window, 'mflash-player', {
                configurable: true,
                get: function() { return _mflash; },
                set: function(val) { _mflash = val; hookCreateFlash(val); }
              });
              var _mflash2;
              Object.defineProperty(window, 'mflashplayer', {
                configurable: true,
                get: function() { return _mflash2; },
                set: function(val) { _mflash2 = val; hookCreateFlash(val); }
              });

              // 4. 检测页面中已有的 Flash 元素
              function extractSwfUrl(el) {
                if (!el) return null;
                var src = el.getAttribute('data') || el.getAttribute('src') ||
                          el.getAttribute('movie') || el.getAttribute('url') || '';
                if (src) return src;
                var params = el.querySelectorAll('param[name="movie"], param[name="src"]');
                for (var i = 0; i < params.length; i++) {
                  var v = params[i].getAttribute('value') || '';
                  if (v) return v;
                }
                return null;
              }

              function checkExistingFlash() {
                if (redirected) return;
                var selectors = 'object[type="application/x-shockwave-flash"], ' +
                  'embed[type="application/x-shockwave-flash"], ' +
                  'object[data$=".swf" i], embed[src$=".swf" i], ' +
                  'object[classid*="D27CDB6E" i]';
                var elements = document.querySelectorAll(selectors);
                for (var i = 0; i < elements.length; i++) {
                  var swfUrl = extractSwfUrl(elements[i]);
                  if (swfUrl) { redirectToPlayer(swfUrl, window.location.href); return; }
                }
              }

              checkExistingFlash();

              // 5. MutationObserver 持续监控
              if (window.MutationObserver) {
                var observer = new MutationObserver(function() {
                  if (redirected) { observer.disconnect(); return; }
                  checkExistingFlash();
                });
                try {
                  observer.observe(document.documentElement || document.body || document, {
                    childList: true, subtree: true
                  });
                } catch(e) {}
                setTimeout(function() { observer.disconnect(); }, 15000);
              }
            })();
        """
    }
}
