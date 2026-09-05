package com.example.taimaninviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HttpViewerActivity : Activity() {
    companion object {
        const val REQ = 1001
        const val PREFS = "viewer_prefs"
        const val KEY = "tree_uri"
        const val CRASH_KEY = "last_crash"
    }

    private var web: WebView? = null
    private var files: TreeFiles? = null
    private var server: LocalHttpServer? = null
    private var viewerActive = false
    private var pageStatus = "未启动"
    private val consoleLines = Collections.synchronizedList(ArrayList<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusPoll: Runnable? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installCrashRecorder()
        showLauncher()
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(CRASH_KEY, Log.getStackTraceString(error)).commit()
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLauncher(message: String? = null) {
        stopViewer()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val title = TextView(this).apply {
            text = "Taimanin RPGX Viewer"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = message ?: "v0.5 · Android 16 / HyperOS 兼容版\n\n本版使用手机内部 localhost HTTP 服务，运行方式更接近 PC 自带的 server.py。\n原 Viewer 文件夹不会被修改。"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) {
            val open = Button(this).apply {
                text = "运行已授权的 Viewer"
                setOnClickListener { startViewer(Uri.parse(saved)) }
            }
            root.addView(open, buttonParams())
        }

        val choose = Button(this).apply {
            text = "重新选择 Viewer 文件夹"
            setOnClickListener { chooseFolder() }
        }
        root.addView(choose, buttonParams())

        val lastCrash = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CRASH_KEY, null)
        if (!lastCrash.isNullOrBlank()) {
            val diag = Button(this).apply {
                text = "查看上次崩溃信息"
                setOnClickListener {
                    AlertDialog.Builder(this@HttpViewerActivity)
                        .setTitle("上次崩溃信息")
                        .setMessage(lastCrash.take(12000))
                        .setPositiveButton("清除") { _, _ ->
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(CRASH_KEY).apply()
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
            root.addView(diag, buttonParams())
        }

        val hint = TextView(this).apply {
            text = "根目录中应直接包含 index.html、data、scenes 等。第一次启动大文件库可能需要一点时间。"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(10)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chooseFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }, REQ)
        } catch (e: Throwable) {
            showLauncher("系统文件夹选择器无法启动：\n${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
        }
    }

    @Deprecated("legacy result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK) {
            showLauncher("没有选择文件夹。")
            return
        }
        val uri = data?.data
        if (uri == null) {
            showLauncher("系统没有返回有效的文件夹地址。")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
        startViewer(uri)
    }

    private fun startViewer(uri: Uri) {
        stopViewer()
        consoleLines.clear()
        pageStatus = "检查 Viewer 文件夹"
        try {
            val tree = TreeFiles(uri)
            if (tree.resolve("index.html") == null) {
                showLauncher("所选目录中没有找到 index.html。请重新选择 Viewer 根目录。")
                return
            }
            files = tree

            val localServer = LocalHttpServer(tree)
            localServer.start()
            server = localServer

            val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val w = WebView(this)
            web = w
            setupWebView(w)
            frame.addView(w, FrameLayout.LayoutParams(-1, -1))

            val status = TextView(this).apply {
                text = "正在启动 Viewer…"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(190, 0, 0, 0))
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            frame.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(6)
                topMargin = dp(6)
            })

            val diag = Button(this).apply {
                text = "诊断"
                textSize = 12f
                alpha = 0.88f
                setOnClickListener { showDiagnostics() }
            }
            frame.addView(diag, FrameLayout.LayoutParams(dp(80), dp(46), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(6)
                topMargin = dp(6)
            })

            setContentView(frame)
            viewerActive = true
            startStatusPoll(w, status)
            pageStatus = "加载 index.html"
            w.loadUrl("http://127.0.0.1:${localServer.port}/index.html")
        } catch (e: Throwable) {
            showLauncher("Viewer 初始化失败：\n${e.javaClass.simpleName}: ${e.message ?: "未知错误"}\n\n请把这段文字或截图发给我。")
        }
    }

    private fun setupWebView(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.BLACK)

        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val source = cm.sourceId()?.substringAfterLast('/') ?: "?"
                addConsole("${cm.messageLevel()} $source:${cm.lineNumber()} ${cm.message()}")
                return true
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageStatus = "HTML 开始加载"
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageStatus = "HTML 已加载，等待 Viewer JS 初始化"
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val msg = "WEB ${request?.url}: ${error?.errorCode} ${error?.description}"
                addConsole(msg)
                if (request?.isForMainFrame == true) {
                    pageStatus = "主页面加载失败"
                    Toast.makeText(this@HttpViewerActivity, "播放器页面加载失败：${error?.description ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startStatusPoll(w: WebView, status: TextView) {
        statusPoll?.let { mainHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!viewerActive || web !== w) return
                val js = """
                    (function(){
                      try {
                        var l=document.getElementById('loading-wrap');
                        var f=document.getElementById('loading-file');
                        var e=document.getElementById('loading-error');
                        var lv=l?getComputedStyle(l).visibility:'missing';
                        var ev=e?getComputedStyle(e).visibility:'missing';
                        return [document.readyState,typeof main,typeof sceneData,lv,ev,f?f.innerText:''].join('|||');
                      } catch(x) { return 'ERR|||'+x.message; }
                    })();
                """.trimIndent()
                try {
                    w.evaluateJavascript(js) { raw ->
                        val value = decodeJsString(raw)
                        val p = value.split("|||", limit = 6)
                        if (p.isNotEmpty()) {
                            when {
                                p[0] == "ERR" -> {
                                    status.visibility = View.VISIBLE
                                    status.text = "JS 状态读取失败：${p.getOrNull(1) ?: "未知"}"
                                }
                                p.size >= 5 && p[4] != "hidden" && p[4] != "missing" -> {
                                    status.visibility = View.VISIBLE
                                    status.text = "Viewer 报告资源加载错误 · 点右上角【诊断】"
                                }
                                p.size >= 4 && p[3] != "hidden" && p[3] != "missing" -> {
                                    status.visibility = View.VISIBLE
                                    val file = p.getOrNull(5).orEmpty().take(70)
                                    status.text = if (file.isBlank()) "Viewer 正在加载资源…" else "Viewer 加载中：$file"
                                }
                                p.size >= 2 && p[1] == "undefined" -> {
                                    status.visibility = View.VISIBLE
                                    status.text = "HTML 已打开，但 main.js 尚未运行 · 点【诊断】"
                                }
                                else -> {
                                    status.visibility = View.GONE
                                    pageStatus = "Viewer JS 已运行"
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    status.visibility = View.VISIBLE
                    status.text = "状态检测失败：${e.javaClass.simpleName}"
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        statusPoll = task
        mainHandler.postDelayed(task, 700)
    }

    private fun showDiagnostics() {
        val w = web ?: return
        val js = """
            (function(){
              try {
                var l=document.getElementById('loading-wrap');
                var lf=document.getElementById('loading-file');
                var le=document.getElementById('loading-error');
                var ss=document.getElementById('scene-select');
                var c=document.getElementById('content');
                return JSON.stringify({
                  href:location.href,
                  readyState:document.readyState,
                  title:document.title,
                  inner:[window.innerWidth,window.innerHeight],
                  screen:[screen.width,screen.height],
                  content:c?[c.offsetWidth,c.offsetHeight,getComputedStyle(c).display,getComputedStyle(c).visibility]:null,
                  loading:l?{display:getComputedStyle(l).display,visibility:getComputedStyle(l).visibility,file:lf?lf.innerText:''}:null,
                  loadingError:le?{visibility:getComputedStyle(le).visibility,text:(document.getElementById('loading-error-msg')||{}).value||''}:null,
                  sceneSelect:ss?{display:getComputedStyle(ss).display,visibility:getComputedStyle(ss).visibility}:null,
                  globals:{main:typeof main,prefs:typeof prefs,sceneData:typeof sceneData,CHAR:typeof CHAR,TAG:typeof TAG,TABAData:typeof TABAData,NecroData:typeof NecroData,OtogiData:typeof OtogiData}
                });
              } catch(e) { return 'JS_DIAG_ERROR: '+e.stack; }
            })();
        """.trimIndent()
        try {
            w.evaluateJavascript(js) { raw ->
                val page = decodeJsString(raw)
                val srv = server
                val text = buildString {
                    appendLine("APP: v0.5")
                    appendLine("PAGE STATUS: $pageStatus")
                    appendLine("URL: ${w.url}")
                    if (srv != null) {
                        appendLine("LOCAL SERVER: 127.0.0.1:${srv.port}")
                        appendLine("REQUESTS: ${srv.requestCount.get()}  OK: ${srv.okCount.get()}  404: ${srv.notFoundCount.get()}  ERR: ${srv.errorCount.get()}")
                        val miss = srv.missingSnapshot()
                        if (miss.isNotEmpty()) {
                            appendLine("\nMISSING FILES:")
                            miss.forEach { appendLine("- $it") }
                        }
                        val errs = srv.errorSnapshot()
                        if (errs.isNotEmpty()) {
                            appendLine("\nSERVER ERRORS:")
                            errs.forEach { appendLine("- $it") }
                        }
                    }
                    appendLine("\nPAGE STATE:")
                    appendLine(page)
                    val console = synchronized(consoleLines) { consoleLines.takeLast(30).toList() }
                    if (console.isNotEmpty()) {
                        appendLine("\nCONSOLE:")
                        console.forEach { appendLine(it) }
                    }
                }
                showDiagnosticDialog(text)
            }
        } catch (e: Throwable) {
            showDiagnosticDialog("无法读取 WebView 诊断信息：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun showDiagnosticDialog(text: String) {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 20, 20))
            textSize = 11f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            this.text = text
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Viewer 诊断信息")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Taimanin Viewer Diagnostics", text))
                Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return "null"
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else v.toString()
        } catch (_: Throwable) {
            raw.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
        }
    }

    private fun addConsole(line: String) {
        synchronized(consoleLines) {
            consoleLines.add(line.take(1000))
            while (consoleLines.size > 120) consoleLines.removeAt(0)
        }
    }

    private fun patchHtml(s: String): String {
        val injection = """
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
            <style id="android-viewer-shell-style">
              html,body,#body-wrapper{width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important;background:#111!important;}
              #content{margin:0!important;}
            </style>
            <script id="android-viewer-shell-script">
            (function(){
              window.addEventListener('error',function(e){console.error('[ANDROID JS ERROR] '+e.message+' @ '+e.filename+':'+e.lineno+':'+e.colno);});
              window.addEventListener('unhandledrejection',function(e){console.error('[ANDROID PROMISE] '+String(e.reason));});
              function fit(){
                var c=document.getElementById('content'); if(!c)return;
                var w=c.offsetWidth||1280, h=c.offsetHeight||720;
                var s=Math.min(window.innerWidth/w,window.innerHeight/h);
                if(!isFinite(s)||s<=0)s=1;
                c.style.position='fixed'; c.style.left='50%'; c.style.top='50%'; c.style.margin='0';
                c.style.transformOrigin='center center';
                c.style.transform='translate(-50%, -50%) scale('+s+')';
              }
              window.__androidViewerFit=fit;
              window.addEventListener('load',function(){fit();setTimeout(fit,100);setTimeout(fit,600);setTimeout(fit,1600);try{var c=document.getElementById('content');if(c&&window.ResizeObserver)new ResizeObserver(fit).observe(c);}catch(e){}});
              window.addEventListener('resize',fit);
              if(window.visualViewport)window.visualViewport.addEventListener('resize',fit);
            })();
            </script>
        """.trimIndent()

        var out = s
        out = out.replace(Regex("<meta\\s+[^>]*name=[\\\"']viewport[\\\"'][^>]*>", RegexOption.IGNORE_CASE), "")
        return if (Regex("<head[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
            out.replaceFirst(Regex("<head([^>]*)>", RegexOption.IGNORE_CASE), "<head$1>\n$injection")
        } else {
            "$injection\n$out"
        }
    }

    private fun patchMainJs(s: String): String = s
        .replace(
            "screen.orientation.addEventListener(\"change\", rescale);",
            "if(screen.orientation&&screen.orientation.addEventListener){screen.orientation.addEventListener(\"change\",rescale);}\nwindow.addEventListener(\"resize\",rescale);"
        )
        .replace(
            "screen.orientation.addEventListener('change', rescale);",
            "if(screen.orientation&&screen.orientation.addEventListener){screen.orientation.addEventListener('change',rescale);}\nwindow.addEventListener('resize',rescale);"
        )

    private fun mime(path: String): String {
        val e = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when (e) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ogg", "oga" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
        }
    }

    private fun parseRange(h: String, total: Long): Pair<Long, Long>? {
        if (!h.lowercase(Locale.US).startsWith("bytes=")) return null
        val p = h.substringAfter('=').substringBefore(',').trim().split('-', limit = 2)
        if (p.size != 2 || total <= 0) return null
        if (p[0].isBlank()) {
            val suffix = p[1].toLongOrNull() ?: return null
            return (total - suffix).coerceAtLeast(0) to total - 1
        }
        val start = p[0].toLongOrNull() ?: return null
        val end = p[1].toLongOrNull()?.coerceAtMost(total - 1) ?: total - 1
        return if (start in 0..end && start < total) start to end else null
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) left -= n else {
                if (input.read() < 0) break
                left--
            }
        }
    }

    private fun stopViewer() {
        viewerActive = false
        statusPoll?.let { mainHandler.removeCallbacks(it) }
        statusPoll = null
        try { web?.stopLoading() } catch (_: Throwable) {}
        try { web?.loadUrl("about:blank") } catch (_: Throwable) {}
        try { web?.destroy() } catch (_: Throwable) {}
        web = null
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        files = null
    }

    @Deprecated("legacy back API")
    override fun onBackPressed() {
        val w = web
        if (viewerActive && w != null) {
            if (w.canGoBack()) {
                w.goBack()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Taimanin RPGX Viewer")
                .setMessage("返回启动页、查看诊断，还是退出？")
                .setPositiveButton("启动页") { _, _ -> showLauncher() }
                .setNeutralButton("诊断") { _, _ -> showDiagnostics() }
                .setNegativeButton("退出") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        stopViewer()
        super.onDestroy()
    }

    private data class Entry(val uri: Uri, val id: String, val dir: Boolean, val size: Long)

    private inner class TreeFiles(private val tree: Uri) {
        private val pathCache = ConcurrentHashMap<String, Entry>()
        private val childCache = ConcurrentHashMap<String, Map<String, Entry>>()

        init {
            val id = DocumentsContract.getTreeDocumentId(tree)
            pathCache[""] = Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, true, -1)
        }

        fun resolve(path: String): Entry? {
            val clean = path.substringBefore('?').substringBefore('#').trim('/').replace("\\", "/")
            if (clean.isBlank()) return pathCache[""]
            pathCache[clean]?.let { return it }
            var current = pathCache[""] ?: return null
            var built = ""
            for (part in clean.split('/').filter { it.isNotBlank() }) {
                if (part == "..") return null
                if (part == ".") continue
                built = if (built.isEmpty()) part else "$built/$part"
                val cached = pathCache[built]
                if (cached != null) {
                    current = cached
                    continue
                }
                val kids = children(current.id)
                current = kids[part] ?: kids.entries.firstOrNull { it.key.equals(part, ignoreCase = true) }?.value ?: return null
                pathCache[built] = current
            }
            return current
        }

        private fun children(parentId: String): Map<String, Entry> {
            childCache[parentId]?.let { return it }
            val out = HashMap<String, Entry>()
            val u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            try {
                contentResolver.query(u, projection, null, null, null)?.use { c ->
                    val idc = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nc = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mc = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sc = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    while (c.moveToNext()) {
                        val id = c.getString(idc)
                        val name = c.getString(nc)
                        val mt = c.getString(mc)
                        val size = if (sc >= 0 && !c.isNull(sc)) c.getLong(sc) else -1
                        out[name] = Entry(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            id,
                            mt == DocumentsContract.Document.MIME_TYPE_DIR,
                            size
                        )
                    }
                }
            } catch (e: Throwable) {
                addConsole("SAF query failed: ${e.javaClass.simpleName}: ${e.message}")
                return emptyMap()
            }
            childCache[parentId] = out
            return out
        }
    }

    private inner class LocalHttpServer(private val tree: TreeFiles) {
        private var socket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private var workers: ExecutorService? = null
        @Volatile private var running = false

        val requestCount = AtomicInteger(0)
        val okCount = AtomicInteger(0)
        val notFoundCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        private val missing = Collections.synchronizedList(ArrayList<String>())
        private val errors = Collections.synchronizedList(ArrayList<String>())
        var port: Int = 0
            private set

        fun start() {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            socket = ss
            port = ss.localPort
            running = true
            workers = Executors.newFixedThreadPool(8) { r ->
                Thread(r, "viewer-http-worker").apply { isDaemon = true }
            }
            acceptThread = Thread({
                while (running) {
                    try {
                        val client = ss.accept()
                        workers?.execute { handle(client) }
                    } catch (e: Throwable) {
                        if (running) recordError("accept: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }, "viewer-http-accept").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
            try { socket?.close() } catch (_: Throwable) {}
            socket = null
            try { workers?.shutdownNow() } catch (_: Throwable) {}
            workers = null
        }

        fun missingSnapshot(): List<String> = synchronized(missing) { missing.takeLast(50).toList() }
        fun errorSnapshot(): List<String> = synchronized(errors) { errors.takeLast(30).toList() }

        private fun recordMissing(path: String) {
            notFoundCount.incrementAndGet()
            synchronized(missing) {
                if (!missing.contains(path)) missing.add(path)
                while (missing.size > 100) missing.removeAt(0)
            }
        }

        private fun recordError(text: String) {
            errorCount.incrementAndGet()
            synchronized(errors) {
                errors.add(text.take(800))
                while (errors.size > 80) errors.removeAt(0)
            }
        }

        private fun handle(client: Socket) {
            client.use { s ->
                try {
                    s.soTimeout = 15000
                    val input = BufferedInputStream(s.getInputStream())
                    val output = BufferedOutputStream(s.getOutputStream())
                    val requestLine = readAsciiLine(input) ?: return
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return
                    val method = parts[0].uppercase(Locale.US)
                    val target = parts[1]
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readAsciiLine(input) ?: break
                        if (line.isEmpty()) break
                        val pos = line.indexOf(':')
                        if (pos > 0) headers[line.substring(0, pos).trim().lowercase(Locale.US)] = line.substring(pos + 1).trim()
                    }
                    requestCount.incrementAndGet()
                    if (method != "GET" && method != "HEAD") {
                        sendBytes(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "405".toByteArray(), method == "HEAD")
                        return
                    }

                    val rawPath = target.substringBefore('?').substringBefore('#')
                    val decoded = try { Uri.decode(rawPath) } catch (_: Throwable) { rawPath }
                    val path = decoded.removePrefix("/").ifBlank { "index.html" }
                    val entry = tree.resolve(path)
                    if (entry == null || entry.dir) {
                        recordMissing(path)
                        sendBytes(output, 404, "Not Found", "text/plain; charset=utf-8", "404 $path".toByteArray(), method == "HEAD")
                        return
                    }

                    if (path.equals("index.html", true) || path.equals("data/scripts/main.js", true)) {
                        val text = contentResolver.openInputStream(entry.uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                            ?: throw java.io.IOException("Cannot open $path")
                        val patched = if (path.equals("index.html", true)) patchHtml(text) else patchMainJs(text)
                        val bytes = patched.toByteArray(Charsets.UTF_8)
                        okCount.incrementAndGet()
                        sendBytes(output, 200, "OK", mime(path), bytes, method == "HEAD")
                        return
                    }

                    val pfd = contentResolver.openFileDescriptor(entry.uri, "r")
                        ?: throw java.io.IOException("Cannot open PFD $path")
                    val total = if (entry.size >= 0) entry.size else pfd.statSize
                    var fileInput: InputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    val range = if (total > 0) headers["range"]?.let { parseRange(it, total) } else null
                    val statusCode: Int
                    val reason: String
                    val start: Long
                    val end: Long
                    if (range != null) {
                        start = range.first
                        end = range.second
                        statusCode = 206
                        reason = "Partial Content"
                        skipFully(fileInput, start)
                        fileInput = LimitedInputStream(fileInput, end - start + 1)
                    } else {
                        start = 0
                        end = if (total > 0) total - 1 else -1
                        statusCode = 200
                        reason = "OK"
                    }
                    val length = if (total > 0) end - start + 1 else -1
                    val extra = linkedMapOf("Accept-Ranges" to "bytes")
                    if (range != null) extra["Content-Range"] = "bytes $start-$end/$total"
                    writeHeaders(output, statusCode, reason, mime(path), length, extra)
                    okCount.incrementAndGet()
                    if (method != "HEAD") {
                        fileInput.use { src -> copyStream(src, output, length) }
                    } else {
                        try { fileInput.close() } catch (_: Throwable) {}
                    }
                    output.flush()
                } catch (e: Throwable) {
                    recordError("${e.javaClass.simpleName}: ${e.message}")
                    try {
                        val out = BufferedOutputStream(s.getOutputStream())
                        sendBytes(out, 500, "Internal Server Error", "text/plain; charset=utf-8", "500".toByteArray(), false)
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun sendBytes(out: BufferedOutputStream, code: Int, reason: String, type: String, bytes: ByteArray, headOnly: Boolean) {
            writeHeaders(out, code, reason, type, bytes.size.toLong(), emptyMap())
            if (!headOnly) out.write(bytes)
            out.flush()
        }

        private fun writeHeaders(out: BufferedOutputStream, code: Int, reason: String, type: String, length: Long, extra: Map<String, String>) {
            val h = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: $type\r\n")
                if (length >= 0) append("Content-Length: $length\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                for ((k, v) in extra) append("$k: $v\r\n")
                append("\r\n")
            }
            out.write(h.toByteArray(StandardCharsets.ISO_8859_1))
        }

        private fun copyStream(input: InputStream, out: BufferedOutputStream, expectedLength: Long) {
            val buf = ByteArray(64 * 1024)
            var left = expectedLength
            while (true) {
                val want = if (left >= 0) minOf(buf.size.toLong(), left).toInt() else buf.size
                if (want == 0) break
                val n = input.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
                if (left >= 0) left -= n
            }
        }

        private fun readAsciiLine(input: InputStream): String? {
            val bytes = ArrayList<Byte>(128)
            while (bytes.size < 16384) {
                val b = input.read()
                if (b < 0) {
                    if (bytes.isEmpty()) return null
                    val arr = ByteArray(bytes.size)
                    for (i in bytes.indices) arr[i] = bytes[i]
                    return String(arr, StandardCharsets.ISO_8859_1)
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes.add(b.toByte())
            }
            val arr = ByteArray(bytes.size)
            for (i in bytes.indices) arr[i] = bytes[i]
            return String(arr, StandardCharsets.ISO_8859_1)
        }
    }

    private class LimitedInputStream(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val v = super.read()
            if (v >= 0) left--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
