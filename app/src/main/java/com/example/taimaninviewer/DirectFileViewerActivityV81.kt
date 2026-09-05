package com.example.taimaninviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
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
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DirectFileViewerActivityV81 : Activity() {
    companion object {
        const val REQ_TREE = 2001
        const val PREFS = "viewer_prefs"
        const val KEY = "tree_uri"
    }

    private var web: WebView? = null
    private var server: FileHttpServer? = null
    private var viewerRoot: File? = null
    private val consoleLines = Collections.synchronizedList(ArrayList<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var viewerActive = false
    private var pageStatus = "未启动"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLauncher()
    }

    override fun onResume() {
        super.onResume()
        if (!viewerActive) showLauncher()
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun showLauncher(message: String? = null) {
        stopViewer()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        root.addView(TextView(this).apply {
            text = "Taimanin RPGX Viewer"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
            text = message ?: if (hasAllFilesAccess()) {
                "v0.8.1 · HyperOS 稳定回归版\n\n已恢复 v0.7 的稳定启动方式：不强制横屏、不自动沉浸全屏。\n1280×720 缩放和空 video 修复只在网页加载后注入，不修改原 Viewer 文件。"
            } else {
                "v0.8.1 · HyperOS 稳定回归版\n\n请先授予一次【所有文件访问权限】。"
            }
        }, LinearLayout.LayoutParams(-1, -2))

        if (!hasAllFilesAccess()) {
            root.addView(Button(this).apply {
                text = "授予所有文件访问权限"
                setOnClickListener { requestAllFilesAccess() }
            }, buttonParams())
        } else {
            val detected = detectViewerRoot()
            root.addView(TextView(this).apply {
                textSize = 12f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(6))
                text = if (detected != null) "检测到：\n${detected.absolutePath}"
                else "尚未检测到包含 index.html 的 Viewer 根目录。\n建议放在内部存储的 Taimanin RPGX Viewer 文件夹中。"
            }, LinearLayout.LayoutParams(-1, -2))

            if (detected != null) {
                root.addView(Button(this).apply {
                    text = "运行 Viewer"
                    setOnClickListener { startViewer(detected) }
                }, buttonParams())
            }
            root.addView(Button(this).apply {
                text = "重新选择 Viewer 文件夹位置"
                setOnClickListener { chooseFolderForPath() }
            }, buttonParams())
            root.addView(Button(this).apply {
                text = "重新扫描"
                setOnClickListener { showLauncher() }
            }, buttonParams())
        }
        setContentView(root)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) { showLauncher(); return }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e: Throwable) { showLauncher("无法打开所有文件访问权限页面：${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun chooseFolderForPath() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_TREE)
        } catch (e: Throwable) {
            showLauncher("系统目录选择器启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Deprecated("legacy activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
        val f = fileFromTreeUri(uri)
        if (f != null && File(f, "index.html").isFile) startViewer(f)
        else showLauncher("已记住目录位置，但无法映射到真实共享存储路径，或目录中没有 index.html。")
    }

    private fun detectViewerRoot(): File? {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) {
            val fromSaved = try { fileFromTreeUri(Uri.parse(saved)) } catch (_: Throwable) { null }
            if (fromSaved != null && File(fromSaved, "index.html").isFile) return fromSaved
        }
        val primary = Environment.getExternalStorageDirectory()
        val commonNames = listOf("Taimanin RPGX Viewer", "TaimaninRPGXViewer", "Taimanin Viewer")
        for (name in commonNames) {
            val f = File(primary, name)
            if (File(f, "index.html").isFile) return f
            val nested = File(f, name)
            if (File(nested, "index.html").isFile) return nested
        }
        try {
            primary.listFiles()?.forEach { first ->
                if (!first.isDirectory) return@forEach
                if (File(first, "index.html").isFile) return first
                first.listFiles()?.firstOrNull { it.isDirectory && File(it, "index.html").isFile }?.let { return it }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun fileFromTreeUri(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Throwable) { return null }
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "").trimStart('/')
        val base = when {
            volume.equals("primary", true) -> Environment.getExternalStorageDirectory()
            volume.isNotBlank() -> File("/storage/$volume")
            else -> return null
        }
        return if (rel.isBlank()) base else File(base, rel)
    }

    private fun startViewer(root: File) {
        stopViewer()
        consoleLines.clear()
        pageStatus = "检查直接文件路径"
        if (!hasAllFilesAccess()) { showLauncher("尚未获得所有文件访问权限。"); return }
        if (!File(root, "index.html").isFile) { showLauncher("该目录中没有 index.html：\n${root.absolutePath}"); return }
        try {
            viewerRoot = root.canonicalFile
            val srv = FileHttpServer(viewerRoot!!)
            srv.start()
            server = srv

            val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val w = WebView(this)
            web = w
            setupWebView(w)
            frame.addView(w, FrameLayout.LayoutParams(-1, -1))

            val status = TextView(this).apply {
                text = "正在启动 Viewer…"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            frame.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(6); topMargin = dp(6)
            })

            val diag = Button(this).apply {
                text = "诊断"
                textSize = 11f
                alpha = 0.72f
                setOnClickListener { showDiagnostics() }
            }
            frame.addView(diag, FrameLayout.LayoutParams(dp(72), dp(42), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(4); topMargin = dp(4)
            })

            setContentView(frame)
            viewerActive = true
            startPoll(w, status)
            pageStatus = "加载 index.html"
            w.loadUrl("http://127.0.0.1:${srv.port}/index.html")
        } catch (e: Throwable) {
            showLauncher("启动失败：${e.javaClass.simpleName}: ${e.message}")
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
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        w.setBackgroundColor(Color.BLACK)
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val src = cm.sourceId()?.substringAfterLast('/') ?: "?"
                addConsole("${cm.messageLevel()} $src:${cm.lineNumber()} ${cm.message()}")
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageStatus = "HTML 开始加载"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                pageStatus = "HTML 已加载，等待 Viewer JS"
                view?.let { applyMobilePatch(it) }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                addConsole("WEB ${request?.url}: ${error?.errorCode} ${error?.description}")
                if (request?.isForMainFrame == true) pageStatus = "主页面加载失败"
            }
        }
    }

    private fun applyMobilePatch(w: WebView) {
        val js = """
(function(){
  try {
    if(window.__androidStablePatchInstalled){ if(window.__androidViewerFit)window.__androidViewerFit(); return 'already'; }
    window.__androidStablePatchInstalled=true;
    var st=document.createElement('style');
    st.id='android-v081-style';
    st.textContent=`html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;}\n#content{position:fixed!important;left:50%!important;top:50%!important;width:1280px!important;height:720px!important;margin:0!important;transform-origin:center center!important;}\nvideo::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-panel,video::-webkit-media-controls-start-playback-button{display:none!important;-webkit-appearance:none!important;}`;
    (document.head||document.documentElement).appendChild(st);
    function fit(){
      try{
        var c=document.getElementById('content'); if(!c)return;
        var w=window.innerWidth||document.documentElement.clientWidth||1280;
        var h=window.innerHeight||document.documentElement.clientHeight||720;
        var s=Math.min(w/1280,h/720); if(!isFinite(s)||s<=0)s=1;
        document.documentElement.style.setProperty('--android-fit-scale',String(s));
        c.style.setProperty('transform','translate(-50%,-50%) scale('+s+')','important');
      }catch(e){console.error('[ANDROID FIT] '+e);}
    }
    function hasMediaSource(v){
      try{
        var a=v.getAttribute('src');
        if(a&&a.trim())return true;
        if(v.currentSrc&&v.currentSrc.trim())return true;
        var s=v.querySelector&&v.querySelector('source[src]');
        return !!(s&&s.getAttribute('src')&&s.getAttribute('src').trim());
      }catch(e){return false;}
    }
    function prepVideo(v){
      if(!v||v.tagName!=='VIDEO')return;
      try{
        v.controls=false; v.playsInline=true; v.setAttribute('playsinline',''); v.setAttribute('webkit-playsinline','');
        if(hasMediaSource(v)){
          if(v.dataset.androidHiddenEmpty==='1'){v.style.removeProperty('display');delete v.dataset.androidHiddenEmpty;}
        }else{
          v.style.setProperty('display','none','important');v.dataset.androidHiddenEmpty='1';
        }
      }catch(e){console.error('[ANDROID VIDEO] '+e);}
    }
    function scan(){try{document.querySelectorAll('video').forEach(prepVideo);}catch(e){}}
    window.__androidViewerFit=fit;
    window.__androidVideoScan=scan;
    scan();fit();
    var mo=new MutationObserver(function(ms){
      try{
        ms.forEach(function(m){
          if(m.type==='attributes'&&m.target&&m.target.tagName==='VIDEO')prepVideo(m.target);
          if(m.addedNodes)Array.prototype.forEach.call(m.addedNodes,function(n){
            if(!n||n.nodeType!==1)return;
            if(n.tagName==='VIDEO')prepVideo(n);
            if(n.querySelectorAll)n.querySelectorAll('video').forEach(prepVideo);
          });
        });
        scan();fit();
      }catch(e){}
    });
    mo.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src','class']});
    window.addEventListener('resize',function(){fit();scan();});
    window.addEventListener('orientationchange',function(){setTimeout(fit,100);setTimeout(fit,500);});
    setTimeout(function(){scan();fit();},100);setTimeout(function(){scan();fit();},500);setTimeout(function(){scan();fit();},1500);
    return 'installed';
  }catch(e){console.error('[ANDROID PATCH] '+e.stack);return 'error:'+e.message;}
})();
""".trimIndent()
        try { w.evaluateJavascript(js) { r -> addConsole("PATCH $r") } } catch (e: Throwable) { addConsole("PATCH EXCEPTION ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun startPoll(w: WebView, status: TextView) {
        poll?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!viewerActive || web !== w) return
                val js = "(function(){try{if(typeof main!=='undefined'&&!window.__androidStablePatchInstalled)return 'PATCHME|||'+typeof main;if(window.__androidViewerFit)window.__androidViewerFit();if(window.__androidVideoScan)window.__androidVideoScan();return document.readyState+'|||'+typeof main;}catch(e){return 'ERR|||'+e.message}})();"
                try {
                    w.evaluateJavascript(js) { raw ->
                        val p = decodeJsString(raw).split("|||", limit = 2)
                        when {
                            p.firstOrNull() == "PATCHME" -> { applyMobilePatch(w); status.visibility=View.VISIBLE; status.text="Viewer 已启动，正在应用手机适配…" }
                            p.firstOrNull() == "ERR" -> { status.visibility=View.VISIBLE; status.text="JS 状态错误：${p.getOrNull(1)}" }
                            p.getOrNull(1) == "undefined" -> { status.visibility=View.VISIBLE; status.text="页面已打开，等待 main.js… · 点【诊断】" }
                            else -> { status.visibility=View.GONE; pageStatus="Viewer JS 已运行" }
                        }
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 1200)
            }
        }
        poll = r
        mainHandler.postDelayed(r, 700)
    }

    private fun showDiagnostics() {
        val w = web ?: return
        val js = """(function(){try{var c=document.getElementById('content');var vids=[].slice.call(document.querySelectorAll('video')).map(function(v){return {cls:v.className,attr:v.getAttribute('src'),current:v.currentSrc,paused:v.paused,ready:v.readyState,controls:v.controls,display:getComputedStyle(v).display,hiddenEmpty:v.dataset.androidHiddenEmpty||''};});return JSON.stringify({href:location.href,readyState:document.readyState,title:document.title,inner:[innerWidth,innerHeight],dpr:devicePixelRatio,patch:!!window.__androidStablePatchInstalled,fit:getComputedStyle(document.documentElement).getPropertyValue('--android-fit-scale'),content:c?{offset:[c.offsetWidth,c.offsetHeight],rect:[c.getBoundingClientRect().left,c.getBoundingClientRect().top,c.getBoundingClientRect().width,c.getBoundingClientRect().height]}:null,videos:vids,globals:{main:typeof main,sceneData:typeof sceneData,TABAData:typeof TABAData,NecroData:typeof NecroData}});}catch(e){return 'ERR:'+e.stack;}})();"""
        w.evaluateJavascript(js) { raw ->
            val srv = server
            val text = buildString {
                appendLine("APP: v0.8.1")
                appendLine("MODE: v0.7 stable activity + post-load mobile patch")
                appendLine("ALL_FILES_ACCESS: ${hasAllFilesAccess()}")
                appendLine("ROOT: ${viewerRoot?.absolutePath}")
                appendLine("WEB URL: ${w.url}")
                appendLine("PAGE STATUS: $pageStatus")
                if (srv != null) {
                    appendLine("SERVER: 127.0.0.1:${srv.port}")
                    appendLine("STAGE: ${srv.stage}")
                    appendLine("REQUESTS: ${srv.requestCount.get()}  OK: ${srv.okCount.get()}  404: ${srv.notFoundCount.get()}  ERR: ${srv.errorCount.get()}")
                    appendLine("\nRECENT SERVER EVENTS:")
                    srv.eventsSnapshot().forEach { appendLine("- $it") }
                    val miss=srv.missingSnapshot(); if(miss.isNotEmpty()){appendLine("\nMISSING:");miss.forEach{appendLine("- $it")}}
                    val errs=srv.errorSnapshot(); if(errs.isNotEmpty()){appendLine("\nSERVER ERRORS:");errs.forEach{appendLine("- $it")}}
                }
                appendLine("\nPAGE STATE:")
                appendLine(decodeJsString(raw))
                val c=synchronized(consoleLines){consoleLines.takeLast(30).toList()}
                if(c.isNotEmpty()){appendLine("\nCONSOLE:");c.forEach{appendLine(it)}}
            }
            showDiagnosticDialog(text)
        }
    }

    private fun showDiagnosticDialog(text: String) {
        val tv=TextView(this).apply{setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(20,20,20));textSize=11f;setPadding(dp(12),dp(12),dp(12),dp(12));this.text=text;setTextIsSelectable(true)}
        val scroll=ScrollView(this).apply{addView(tv)}
        AlertDialog.Builder(this).setTitle("Viewer 诊断信息").setView(scroll)
            .setPositiveButton("复制"){_,_->val cb=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cb.setPrimaryClip(ClipData.newPlainText("diag",text));Toast.makeText(this,"诊断信息已复制",Toast.LENGTH_SHORT).show()}
            .setNegativeButton("关闭",null).show()
    }

    private fun decodeJsString(raw:String?):String{
        if(raw==null||raw=="null")return "null"
        return try{val v=JSONTokener(raw).nextValue();if(v is String)v else v.toString()}catch(_:Throwable){raw.trim('"').replace("\\n","\n").replace("\\\"","\"")}
    }
    private fun addConsole(line:String){synchronized(consoleLines){consoleLines.add(line.take(1200));while(consoleLines.size>140)consoleLines.removeAt(0)}}

    private fun stopViewer(){
        viewerActive=false;poll?.let{mainHandler.removeCallbacks(it)};poll=null
        try{web?.stopLoading()}catch(_:Throwable){};try{web?.destroy()}catch(_:Throwable){};web=null
        try{server?.stop()}catch(_:Throwable){};server=null;viewerRoot=null
    }

    @Deprecated("legacy back API")
    override fun onBackPressed(){
        val w=web
        if(viewerActive&&w!=null){
            if(w.canGoBack()){w.goBack();return}
            AlertDialog.Builder(this).setTitle("Taimanin RPGX Viewer").setMessage("返回启动页、查看诊断，还是退出？")
                .setPositiveButton("启动页"){_,_->showLauncher()}.setNeutralButton("诊断"){_,_->showDiagnostics()}.setNegativeButton("退出"){_,_->finish()}.show()
        }else finish()
    }
    override fun onDestroy(){stopViewer();super.onDestroy()}

    private inner class FileHttpServer(private val root:File){
        private var socket:ServerSocket?=null
        private var workers:ExecutorService?=null
        @Volatile private var running=false
        @Volatile var stage:String="idle"
        val requestCount=AtomicInteger(0);val okCount=AtomicInteger(0);val notFoundCount=AtomicInteger(0);val errorCount=AtomicInteger(0)
        private val events=Collections.synchronizedList(ArrayList<String>());private val missing=Collections.synchronizedList(ArrayList<String>());private val errors=Collections.synchronizedList(ArrayList<String>())
        var port:Int=0;private set

        fun start(){
            val ss=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));socket=ss;port=ss.localPort;running=true
            workers=Executors.newFixedThreadPool(8){r->Thread(r,"viewer-file-http").apply{isDaemon=true}}
            event("SERVER READY :$port root=${root.absolutePath}")
            Thread({while(running){try{val c=ss.accept();workers?.execute{handle(c)}}catch(e:Throwable){if(running)err("accept ${e.javaClass.simpleName}: ${e.message}")}}},"viewer-file-accept").apply{isDaemon=true;start()}
        }
        fun stop(){running=false;try{socket?.close()}catch(_:Throwable){};try{workers?.shutdownNow()}catch(_:Throwable){}}
        fun eventsSnapshot()=synchronized(events){events.takeLast(36).toList()}
        fun missingSnapshot()=synchronized(missing){missing.takeLast(30).toList()}
        fun errorSnapshot()=synchronized(errors){errors.takeLast(30).toList()}
        private fun event(s:String){stage=s;synchronized(events){events.add(s);while(events.size>140)events.removeAt(0)}}
        private fun err(s:String){errorCount.incrementAndGet();synchronized(errors){errors.add(s);while(errors.size>100)errors.removeAt(0)}}

        private fun handle(client:Socket){
            client.use{s->try{
                s.soTimeout=30000
                val input=BufferedInputStream(s.getInputStream());val out=BufferedOutputStream(s.getOutputStream())
                val requestLine=readLine(input)?:return;val parts=requestLine.split(' ');if(parts.size<2)return
                val method=parts[0].uppercase(Locale.US);val target=parts[1];val headers=HashMap<String,String>()
                while(true){val line=readLine(input)?:break;if(line.isEmpty())break;val p=line.indexOf(':');if(p>0)headers[line.substring(0,p).trim().lowercase(Locale.US)]=line.substring(p+1).trim()}
                requestCount.incrementAndGet()
                if(method!="GET"&&method!="HEAD"){sendBytes(out,405,"Method Not Allowed","text/plain; charset=utf-8","405".toByteArray(),method=="HEAD");return}
                val raw=target.substringBefore('?').substringBefore('#');val path=try{Uri.decode(raw)}catch(_:Throwable){raw}.removePrefix("/").ifBlank{"index.html"}
                event("$method $path :: resolve File");val file=resolveFile(path)
                if(file==null||!file.isFile){notFoundCount.incrementAndGet();synchronized(missing){if(!missing.contains(path))missing.add(path)};sendBytes(out,404,"Not Found","text/plain; charset=utf-8","404 $path".toByteArray(),method=="HEAD");return}
                event("$method $path :: FileInputStream")
                val total=file.length();val fis=FileInputStream(file);var src:InputStream=fis
                val requestedRange=headers["range"]
                val range=if(requestedRange!=null)parseRange(requestedRange,total)else null
                if(requestedRange!=null&&range==null){fis.close();writeHeaders(out,416,"Range Not Satisfiable","text/plain",0,mapOf("Content-Range" to "bytes */$total"));out.flush();return}
                var start=0L;var end=if(total>0)total-1 else -1L;var code=200;var reason="OK"
                if(range!=null){start=range.first;end=range.second;code=206;reason="Partial Content";fis.channel.position(start);src=LimitedInputStream(fis,end-start+1)}
                val length=if(end>=start&&total>0)end-start+1 else 0
                event("$method $path :: headers len=$length")
                val extra=linkedMapOf("Accept-Ranges" to "bytes");if(range!=null)extra["Content-Range"]="bytes $start-$end/$total"
                writeHeaders(out,code,reason,mime(path),length,extra);okCount.incrementAndGet()
                if(method!="HEAD"){event("$method $path :: stream");src.use{copy(it,out,length)}}else src.close()
                out.flush();event("$method $path :: DONE")
            }catch(e:Throwable){
                if(e is SocketException||e.javaClass.simpleName.contains("ConnectionReset",true)||e.message?.contains("reset",true)==true||e.message?.contains("Broken pipe",true)==true){event("CLIENT CANCELLED")}
                else err("${e.javaClass.simpleName}: ${e.message}")
            }}
        }

        private fun resolveFile(path:String):File?{
            val clean=path.replace('\\','/').split('/').filter{it.isNotBlank()&&it!="."};if(clean.any{it==".."})return null
            var cur=root
            for(part in clean){var next=File(cur,part);if(!next.exists())next=cur.listFiles()?.firstOrNull{it.name.equals(part,true)}?:return null;cur=next}
            val canon=try{cur.canonicalFile}catch(_:Throwable){return null};val rp=root.canonicalPath;val cp=canon.canonicalPath
            if(cp!=rp&&!cp.startsWith(rp+File.separator))return null;return canon
        }
        private fun mime(path:String):String{
            val e=path.substringAfterLast('.',"").lowercase(Locale.US)
            return when(e){
                "html","htm"->"text/html; charset=utf-8";"js"->"text/javascript; charset=utf-8";"css"->"text/css; charset=utf-8";"json"->"application/json; charset=utf-8";"txt"->"text/plain; charset=utf-8"
                "png"->"image/png";"jpg","jpeg"->"image/jpeg";"gif"->"image/gif";"webp"->"image/webp";"svg"->"image/svg+xml"
                "ogg","oga"->"audio/ogg";"m4a"->"audio/mp4";"mp3"->"audio/mpeg";"wav"->"audio/wav";"webm"->"video/webm";"mp4"->"video/mp4"
                "ttf"->"font/ttf";"otf"->"font/otf";"woff"->"font/woff";"woff2"->"font/woff2"
                else->MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)?:"application/octet-stream"
            }
        }
        private fun parseRange(h:String,total:Long):Pair<Long,Long>?{
            if(!h.lowercase(Locale.US).startsWith("bytes=")||total<=0)return null
            val p=h.substringAfter('=').substringBefore(',').trim().split('-',limit=2);if(p.size!=2)return null
            if(p[0].isBlank()){val suffix=p[1].toLongOrNull()?:return null;if(suffix<=0)return null;return(total-suffix).coerceAtLeast(0) to total-1}
            val start=p[0].toLongOrNull()?:return null;val end=p[1].toLongOrNull()?.coerceAtMost(total-1)?:total-1
            return if(start in 0..end&&start<total)start to end else null
        }
        private fun sendBytes(out:BufferedOutputStream,code:Int,reason:String,type:String,bytes:ByteArray,head:Boolean){writeHeaders(out,code,reason,type,bytes.size.toLong(),emptyMap());if(!head)out.write(bytes);out.flush()}
        private fun writeHeaders(out:BufferedOutputStream,code:Int,reason:String,type:String,length:Long,extra:Map<String,String>){
            val h=buildString{append("HTTP/1.1 $code $reason\r\n");append("Content-Type: $type\r\n");append("Content-Length: $length\r\n");append("Cache-Control: no-store\r\n");append("Connection: close\r\n");extra.forEach{(k,v)->append("$k: $v\r\n")};append("\r\n")}
            out.write(h.toByteArray(StandardCharsets.ISO_8859_1))
        }
        private fun copy(input:InputStream,out:BufferedOutputStream,expected:Long){val buf=ByteArray(64*1024);var left=expected;while(left>0){val n=input.read(buf,0,minOf(buf.size.toLong(),left).toInt());if(n<0)break;out.write(buf,0,n);left-=n}}
        private fun readLine(input:InputStream):String?{val bytes=ArrayList<Byte>(128);while(bytes.size<16384){val b=input.read();if(b<0){if(bytes.isEmpty())return null;break};if(b=='\n'.code)break;if(b!='\r'.code)bytes.add(b.toByte())};val a=ByteArray(bytes.size);for(i in bytes.indices)a[i]=bytes[i];return String(a,StandardCharsets.ISO_8859_1)}
    }

    private class LimitedInputStream(input:InputStream,private var left:Long):FilterInputStream(input){
        override fun read():Int{if(left<=0)return -1;val v=super.read();if(v>=0)left--;return v}
        override fun read(b:ByteArray,off:Int,len:Int):Int{if(left<=0)return -1;val n=super.read(b,off,minOf(len.toLong(),left).toInt());if(n>0)left-=n;return n}
    }
}
