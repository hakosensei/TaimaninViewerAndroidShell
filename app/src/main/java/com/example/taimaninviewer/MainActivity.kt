package com.example.taimaninviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    companion object {
        const val REQ = 1001
        const val PREFS = "viewer_prefs"
        const val KEY = "tree_uri"
        const val CRASH_KEY = "last_crash"
        const val HOST = "viewer.local"
        const val START = "https://viewer.local/index.html"
    }

    private var web: WebView? = null
    private var files: TreeFiles? = null
    private var viewerActive = false

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
        viewerActive = false
        web?.destroy()
        web = null
        files = null

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
            text = message ?: "Android 16 / HyperOS 兼容启动页\n\n原 Viewer 文件夹不会被修改。"
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
                text = "打开已授权的 Viewer"
                setOnClickListener { startViewer(Uri.parse(saved)) }
            }
            root.addView(open, buttonParams())
        }

        val choose = Button(this).apply {
            text = "选择 Viewer 文件夹"
            setOnClickListener { chooseFolder() }
        }
        root.addView(choose, buttonParams())

        val lastCrash = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CRASH_KEY, null)
        if (!lastCrash.isNullOrBlank()) {
            val diag = Button(this).apply {
                text = "查看上次崩溃信息"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
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
            text = "请选择里面直接包含 index.html、data、scenes 等内容的根目录。"
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
        try {
            val tree = TreeFiles(uri)
            if (tree.resolve("index.html") == null) {
                showLauncher("所选目录中没有找到 index.html。请重新选择 Viewer 根目录。")
                return
            }
            files = tree
            val w = WebView(this)
            web = w
            setupWebView(w)
            setContentView(w)
            viewerActive = true
            w.loadUrl(START)
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
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                val req = r ?: return null
                val u = req.url
                if (u.scheme != "https" || u.host != HOST) return null
                val path = u.encodedPath?.removePrefix("/")?.let(Uri::decode) ?: return notFound()
                return serve(path, req.requestHeaders)
            }

            override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                super.onReceivedError(v, r, e)
                if (r?.isForMainFrame == true) {
                    Toast.makeText(
                        this@MainActivity,
                        "播放器页面加载失败：${e?.description ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun serve(path: String, requestHeaders: Map<String, String>): WebResourceResponse {
        val f = files?.resolve(path) ?: return notFound()
        if (f.dir) return notFound()
        if (path.equals("index.html", true)) return patchedText(f, "text/html", ::patchHtml)
        if (path.equals("data/scripts/main.js", true)) return patchedText(f, "text/javascript", ::patchMainJs)

        val pfd = try { contentResolver.openFileDescriptor(f.uri, "r") } catch (_: Throwable) { null } ?: return notFound()
        val total = if (f.size >= 0) f.size else pfd.statSize
        var input: InputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val mime = mime(path)
        val headers = linkedMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        val range = requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val parsed = if (range != null && total > 0) parseRange(range, total) else null
        if (parsed != null) {
            val (start, end) = parsed
            skipFully(input, start)
            val length = end - start + 1
            input = LimitedInputStream(input, length)
            headers["Content-Range"] = "bytes $start-$end/$total"
            headers["Content-Length"] = length.toString()
            return WebResourceResponse(mime, encoding(mime), 206, "Partial Content", headers, input)
        }
        if (total >= 0) headers["Content-Length"] = total.toString()
        return WebResourceResponse(mime, encoding(mime), 200, "OK", headers, input)
    }

    private fun patchedText(f: Entry, mime: String, patch: (String) -> String): WebResourceResponse {
        val bytes = try {
            contentResolver.openInputStream(f.uri)?.use {
                patch(it.bufferedReader(Charsets.UTF_8).readText()).toByteArray(Charsets.UTF_8)
            }
        } catch (_: Throwable) { null } ?: return notFound()
        return WebResourceResponse(
            mime, "UTF-8", 200, "OK",
            mapOf("Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store"),
            bytes.inputStream()
        )
    }

    private fun patchHtml(s: String): String {
        if (Regex("<meta\\s+[^>]*name=[\\\"']viewport[\\\"']", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        val v = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no\">"
        return if (Regex("<head[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(s))
            s.replaceFirst(Regex("<head([^>]*)>", RegexOption.IGNORE_CASE), "<head$1>\n$v")
        else "$v\n$s"
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
            "html", "htm" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "txt" -> "text/plain"
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

    private fun encoding(m: String): String? =
        if (m.startsWith("text/") || m == "application/json") "UTF-8" else null

    private fun parseRange(h: String, total: Long): Pair<Long, Long>? {
        if (!h.startsWith("bytes=")) return null
        val p = h.removePrefix("bytes=").substringBefore(',').trim().split('-', limit = 2)
        if (p.size != 2) return null
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

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found",
        mapOf("Cache-Control" to "no-store"), "404".byteInputStream()
    )

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
                .setMessage("返回启动页还是退出？")
                .setPositiveButton("启动页") { _, _ -> showLauncher() }
                .setNegativeButton("退出") { _, _ -> finish() }
                .setNeutralButton("取消", null)
                .show()
        } else {
            finish()
        }
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
            val clean = path.trim('/').replace("\\", "/")
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
                current = children(current.id)[part] ?: return null
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
                            DocumentsContract.buildDocumentUriUsingTree(tree, id), id,
                            mt == DocumentsContract.Document.MIME_TYPE_DIR, size
                        )
                    }
                }
            } catch (_: Throwable) {
                return emptyMap()
            }
            childCache[parentId] = out
            return out
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
