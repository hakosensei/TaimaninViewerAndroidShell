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
import android.provider.DocumentsContract
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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

class ViewerActivityV6 : Activity() {
    companion object {
        const val REQ = 1001
        const val PREFS = "viewer_prefs"
        const val KEY = "tree_uri"
    }

    private var web: WebView? = null
    private var server: LocalServer? = null
    private var viewerActive = false
    private val console = Collections.synchronizedList(ArrayList<String>())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        showLauncher()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showLauncher(message: String? = null) {
        stopViewer()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(18,18,18))
        }
        root.addView(TextView(this).apply {
            text = "Taimanin RPGX Viewer"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1,-2))
        root.addView(TextView(this).apply {
            text = message ?: "v0.6 · HyperOS / Android 16\n\n本版按 PC server.py 的方式原样发送 Viewer 文件，不修改 index.html 或 main.js。"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0,dp(18),0,dp(18))
        }, LinearLayout.LayoutParams(-1,-2))

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) {
            root.addView(Button(this).apply {
                text = "运行已授权的 Viewer"
                setOnClickListener { startViewer(Uri.parse(saved)) }
            }, buttonParams())
        }
        root.addView(Button(this).apply {
            text = "重新选择 Viewer 文件夹"
            setOnClickListener { chooseFolder() }
        }, buttonParams())
        root.addView(TextView(this).apply {
            text = "请选择里面直接包含 index.html、data、scenes 等内容的根目录。"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0,dp(16),0,0)
        }, LinearLayout.LayoutParams(-1,-2))
        setContentView(root)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) }

    private fun chooseFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }, REQ)
        } catch (e: Throwable) {
            showLauncher("无法启动系统文件夹选择器：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Deprecated("legacy result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK) { showLauncher("没有选择文件夹。"); return }
        val uri = data?.data ?: run { showLauncher("系统没有返回有效目录。"); return }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
        startViewer(uri)
    }

    private fun startViewer(uri: Uri) {
        stopViewer()
        console.clear()
        try {
            val tree = TreeFiles(uri)
            if (tree.resolve("index.html") == null) {
                showLauncher("所选目录中没有找到 index.html。")
                return
            }
            val srv = LocalServer(tree)
            srv.start()
            server = srv

            val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val w = WebView(this)
            web = w
            setupWebView(w)
            frame.addView(w, FrameLayout.LayoutParams(-1,-1))

            val status = TextView(this).apply {
                text = "正在读取 index.html…"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(190,0,0,0))
                setPadding(dp(8),dp(5),dp(8),dp(5))
            }
            frame.addView(status, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(6); topMargin = dp(6)
            })
            frame.addView(Button(this).apply {
                text = "诊断"
                textSize = 12f
                alpha = .9f
                setOnClickListener { showDiagnostics() }
            }, FrameLayout.LayoutParams(dp(80),dp(46),Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(6); topMargin = dp(6)
            })

            setContentView(frame)
            viewerActive = true
            w.loadUrl("http://127.0.0.1:${srv.port}/index.html")
            w.postDelayed({
                if (viewerActive) status.text = "${srv.stage}\n如长时间不变，请点右上角【诊断】"
            }, 1500)
            w.postDelayed({
                if (viewerActive && w.url != null && w.url != "about:blank") status.visibility = android.view.View.GONE
            }, 4000)
        } catch (e: Throwable) {
            showLauncher("Viewer 初始化失败：${e.javaClass.simpleName}: ${e.message}")
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
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.BLACK)
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                synchronized(console) {
                    console.add("${cm.messageLevel()} ${cm.sourceId()?.substringAfterLast('/')}:${cm.lineNumber()} ${cm.message()}")
                    while (console.size > 100) console.removeAt(0)
                }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                synchronized(console) { console.add("WEB ${request?.url}: ${error?.errorCode} ${error?.description}") }
                if (request?.isForMainFrame == true) Toast.makeText(this@ViewerActivityV6,"主页面加载失败：${error?.description}",Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDiagnostics() {
        val w = web ?: return
        val srv = server
        val js = """
            (function(){try{return JSON.stringify({href:location.href,readyState:document.readyState,title:document.title,inner:[innerWidth,innerHeight],body:!!document.body,globals:{main:typeof main,sceneData:typeof sceneData,TABAData:typeof TABAData,NecroData:typeof NecroData}})}catch(e){return 'JSERR:'+e.stack}})();
        """.trimIndent()
        w.evaluateJavascript(js) { raw ->
            val page = decodeJs(raw)
            val text = buildString {
                appendLine("APP: v0.6")
                appendLine("WEB URL: ${w.url}")
                if (srv != null) {
                    appendLine("SERVER: 127.0.0.1:${srv.port}")
                    appendLine("STAGE: ${srv.stage}")
                    appendLine("REQUESTS: ${srv.requests.get()}  OK: ${srv.ok.get()}  404: ${srv.missing.get()}  ERR: ${srv.errors.get()}")
                    val recent = srv.recentSnapshot()
                    if (recent.isNotEmpty()) { appendLine("\nRECENT SERVER EVENTS:"); recent.forEach { appendLine("- $it") } }
                }
                appendLine("\nPAGE STATE:")
                appendLine(page)
                val c = synchronized(console) { console.takeLast(30).toList() }
                if (c.isNotEmpty()) { appendLine("\nCONSOLE:"); c.forEach { appendLine(it) } }
            }
            showDiagDialog(text)
        }
    }

    private fun decodeJs(raw: String?): String {
        if (raw == null || raw == "null") return "null"
        return try { JSONTokener(raw).nextValue().toString() } catch (_: Throwable) { raw }
    }

    private fun showDiagDialog(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20,20,20))
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Viewer v0.6 诊断").setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Viewer diagnostics", text))
                Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()
            }.setNegativeButton("关闭",null).show()
    }

    private fun stopViewer() {
        viewerActive = false
        try { web?.stopLoading() } catch (_: Throwable) {}
        try { web?.destroy() } catch (_: Throwable) {}
        web = null
        try { server?.stop() } catch (_: Throwable) {}
        server = null
    }

    @Deprecated("legacy back")
    override fun onBackPressed() {
        val w = web
        if (viewerActive && w != null) {
            AlertDialog.Builder(this).setTitle("Taimanin RPGX Viewer")
                .setItems(arrayOf("返回启动页","诊断","退出")) { _, which ->
                    when(which) { 0 -> showLauncher(); 1 -> showDiagnostics(); 2 -> finish() }
                }.show()
        } else finish()
    }

    override fun onDestroy() { stopViewer(); super.onDestroy() }

    private data class Entry(val uri: Uri, val id: String, val dir: Boolean, val size: Long)

    private inner class TreeFiles(private val tree: Uri) {
        private val pathCache = ConcurrentHashMap<String,Entry>()
        private val childCache = ConcurrentHashMap<String,Map<String,Entry>>()
        init {
            val id = DocumentsContract.getTreeDocumentId(tree)
            pathCache[""] = Entry(DocumentsContract.buildDocumentUriUsingTree(tree,id),id,true,-1)
        }
        fun resolve(path: String): Entry? {
            val clean = path.substringBefore('?').substringBefore('#').trim('/').replace("\\","/")
            if (clean.isBlank()) return pathCache[""]
            pathCache[clean]?.let { return it }
            var current = pathCache[""] ?: return null
            var built = ""
            for (part in clean.split('/').filter { it.isNotBlank() }) {
                if (part == "..") return null
                if (part == ".") continue
                built = if (built.isEmpty()) part else "$built/$part"
                pathCache[built]?.let { current = it; return@let }
                if (pathCache[built] == null) {
                    val kids = children(current.id)
                    current = kids[part] ?: kids.entries.firstOrNull { it.key.equals(part,true) }?.value ?: return null
                    pathCache[built] = current
                }
            }
            return current
        }
        private fun children(parentId: String): Map<String,Entry> {
            childCache[parentId]?.let { return it }
            val out = HashMap<String,Entry>()
            val u = DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId)
            val p = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE)
            contentResolver.query(u,p,null,null,null)?.use { c ->
                val idc=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nc=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mc=c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sc=c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while(c.moveToNext()) {
                    val id=c.getString(idc); val name=c.getString(nc); val mt=c.getString(mc)
                    val size=if(sc>=0&&!c.isNull(sc))c.getLong(sc) else -1
                    out[name]=Entry(DocumentsContract.buildDocumentUriUsingTree(tree,id),id,mt==DocumentsContract.Document.MIME_TYPE_DIR,size)
                }
            }
            childCache[parentId]=out
            return out
        }
    }

    private inner class LocalServer(private val tree: TreeFiles) {
        private var ss: ServerSocket? = null
        private var pool: ExecutorService? = null
        @Volatile private var running=false
        @Volatile var stage="未启动"
        var port=0; private set
        val requests=AtomicInteger(0); val ok=AtomicInteger(0); val missing=AtomicInteger(0); val errors=AtomicInteger(0)
        private val recent=Collections.synchronizedList(ArrayList<String>())

        private fun event(s:String) { stage=s; synchronized(recent){recent.add(s.take(300));while(recent.size>40)recent.removeAt(0)} }
        fun recentSnapshot()=synchronized(recent){recent.toList()}

        fun start() {
            val socket=ServerSocket(0,50,InetAddress.getByName("127.0.0.1")); ss=socket; port=socket.localPort; running=true
            pool=Executors.newFixedThreadPool(8){r->Thread(r,"viewer-v6-http").apply{isDaemon=true}}
            Thread({while(running){try{val c=socket.accept();pool?.execute{handle(c)}}catch(_:Throwable){}}},"viewer-v6-accept").apply{isDaemon=true;start()}
            event("SERVER READY :$port")
        }
        fun stop(){running=false;try{ss?.close()}catch(_:Throwable){};try{pool?.shutdownNow()}catch(_:Throwable){}}

        private fun handle(client: Socket) {
            client.use { s ->
                try {
                    s.soTimeout=15000
                    val input=BufferedInputStream(s.getInputStream()); val output=BufferedOutputStream(s.getOutputStream())
                    val first=readLine(input)?:return; val parts=first.split(' '); if(parts.size<2)return
                    val method=parts[0].uppercase(Locale.US); val target=parts[1]
                    val headers=HashMap<String,String>()
                    while(true){val line=readLine(input)?:break;if(line.isEmpty())break;val pos=line.indexOf(':');if(pos>0)headers[line.substring(0,pos).trim().lowercase(Locale.US)]=line.substring(pos+1).trim()}
                    requests.incrementAndGet()
                    val raw=target.substringBefore('?').substringBefore('#'); val decoded=try{Uri.decode(raw)}catch(_:Throwable){raw}; val path=decoded.removePrefix("/").ifBlank{"index.html"}
                    event("$method $path :: resolve")
                    val e=tree.resolve(path)
                    if(e==null||e.dir){missing.incrementAndGet();event("$method $path :: 404");sendBytes(output,404,"Not Found","text/plain","404 $path".toByteArray(),method=="HEAD");return}
                    event("$method $path :: openFileDescriptor")
                    val pfd=contentResolver.openFileDescriptor(e.uri,"r")?:throw java.io.IOException("openFileDescriptor returned null: $path")
                    event("$method $path :: PFD opened")
                    val total=if(e.size>=0)e.size else pfd.statSize
                    var src:InputStream=android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    val range=if(total>0)headers["range"]?.let{parseRange(it,total)}else null
                    val start=range?.first?:0L; val end=range?.second?:if(total>0)total-1 else -1L
                    val code=if(range!=null)206 else 200; val reason=if(range!=null)"Partial Content" else "OK"
                    if(start>0){event("$method $path :: seek $start");skipFully(src,start);src=LimitedInputStream(src,end-start+1)}
                    val len=if(total>0)end-start+1 else -1
                    val extra=linkedMapOf("Accept-Ranges" to "bytes");if(range!=null)extra["Content-Range"]="bytes $start-$end/$total"
                    event("$method $path :: write headers len=$len")
                    writeHeaders(output,code,reason,mime(path),len,extra);ok.incrementAndGet()
                    if(method!="HEAD"){event("$method $path :: stream");src.use{copy(it,output,len)}}else try{src.close()}catch(_:Throwable){}
                    output.flush();event("$method $path :: DONE")
                } catch(t:Throwable) {
                    errors.incrementAndGet();event("ERROR ${t.javaClass.simpleName}: ${t.message}")
                    try{sendBytes(BufferedOutputStream(s.getOutputStream()),500,"Internal Server Error","text/plain","500".toByteArray(),false)}catch(_:Throwable){}
                }
            }
        }

        private fun mime(path:String):String { val e=path.substringAfterLast('.',"").lowercase(Locale.US);return when(e){
            "html","htm"->"text/html; charset=utf-8";"js"->"text/javascript; charset=utf-8";"css"->"text/css; charset=utf-8";"json"->"application/json; charset=utf-8";"txt"->"text/plain; charset=utf-8";
            "png"->"image/png";"jpg","jpeg"->"image/jpeg";"gif"->"image/gif";"webp"->"image/webp";"svg"->"image/svg+xml";
            "ogg","oga"->"audio/ogg";"m4a"->"audio/mp4";"mp3"->"audio/mpeg";"wav"->"audio/wav";"webm"->"video/webm";"mp4"->"video/mp4";
            "ttf"->"font/ttf";"otf"->"font/otf";"woff"->"font/woff";"woff2"->"font/woff2";else->MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)?:"application/octet-stream"}}
        private fun parseRange(h:String,total:Long):Pair<Long,Long>?{if(!h.lowercase(Locale.US).startsWith("bytes="))return null;val p=h.substringAfter('=').substringBefore(',').trim().split('-',limit=2);if(p.size!=2)return null;if(p[0].isBlank()){val suffix=p[1].toLongOrNull()?:return null;return(total-suffix).coerceAtLeast(0) to total-1};val st=p[0].toLongOrNull()?:return null;val en=p[1].toLongOrNull()?.coerceAtMost(total-1)?:total-1;return if(st in 0..en)st to en else null}
        private fun skipFully(i:InputStream,n0:Long){var n=n0;while(n>0){val k=i.skip(n);if(k>0)n-=k else{if(i.read()<0)break;n--}}}
        private fun copy(i:InputStream,o:BufferedOutputStream,len0:Long){val b=ByteArray(64*1024);var left=len0;while(true){val want=if(left>=0)minOf(b.size.toLong(),left).toInt()else b.size;if(want==0)break;val n=i.read(b,0,want);if(n<0)break;o.write(b,0,n);if(left>=0)left-=n}}
        private fun sendBytes(o:BufferedOutputStream,c:Int,r:String,t:String,b:ByteArray,head:Boolean){writeHeaders(o,c,r,t,b.size.toLong(),emptyMap());if(!head)o.write(b);o.flush()}
        private fun writeHeaders(o:BufferedOutputStream,c:Int,r:String,t:String,len:Long,x:Map<String,String>){val h=buildString{append("HTTP/1.1 $c $r\r\n");append("Content-Type: $t\r\n");if(len>=0)append("Content-Length: $len\r\n");append("Cache-Control: no-store\r\nConnection: close\r\n");for((k,v)in x)append("$k: $v\r\n");append("\r\n")};o.write(h.toByteArray(StandardCharsets.ISO_8859_1))}
        private fun readLine(i:InputStream):String?{val b=ArrayList<Byte>(128);while(b.size<16384){val x=i.read();if(x<0){if(b.isEmpty())return null;break};if(x=='\n'.code)break;if(x!='\r'.code)b.add(x.toByte())};val a=ByteArray(b.size);for(n in b.indices)a[n]=b[n];return String(a,StandardCharsets.ISO_8859_1)}
    }

    private class LimitedInputStream(input:InputStream,private var left:Long):FilterInputStream(input){override fun read():Int{if(left<=0)return-1;val v=super.read();if(v>=0)left--;return v};override fun read(b:ByteArray,off:Int,len:Int):Int{if(left<=0)return-1;val n=super.read(b,off,minOf(len.toLong(),left).toInt());if(n>0)left-=n;return n}}
}
