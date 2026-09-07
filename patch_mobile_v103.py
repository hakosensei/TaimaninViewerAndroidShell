from pathlib import Path
import base64, gzip

SRC=Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
s=SRC.read_text(encoding='utf-8')

def rep(old,new,label,count=1):
 global s
 if old not in s: raise SystemExit(f'{label}: marker missing')
 s=s.replace(old,new,count)

# Visible version labels
s=s.replace('v0.9.6 Recovery · HyperOS 稳定恢复版','v1.0.3 Mobile · 原生翻译桥 / 输入隔离版')
s=s.replace('APP: v0.9.6 Recovery','APP: v1.0.3 Mobile')
s=s.replace('MODE: clean stable activity + post-launch landscape + immersive bars + Touch Addon',
            'MODE: Mobile v1.0.3 + native TokenHub bridge + strict input gate')

# Native bridge imports
rep('import android.webkit.WebViewClient\n',
    'import android.webkit.WebViewClient\nimport android.webkit.JavascriptInterface\n', 'JavascriptInterface import')
rep('import org.json.JSONTokener\n',
    'import org.json.JSONTokener\nimport org.json.JSONObject\n', 'JSONObject import')
rep('import java.net.InetAddress\n',
    'import java.net.InetAddress\nimport java.net.HttpURLConnection\nimport java.net.URL\n', 'HTTP imports')

# executor field
rep('    private var touchAddonJsCache: String? = null\n',
'''    private var touchAddonJsCache: String? = null
    private val translationExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taimanin-translation-http").apply { isDaemon = true }
    }
''','translation executor')

# Add JS interface to WebView before client setup
rep('        w.setBackgroundColor(Color.BLACK)\n',
'''        w.setBackgroundColor(Color.BLACK)
        try { w.addJavascriptInterface(NativeBridge(), "TaimaninNative") }
        catch (e: Throwable) { addConsole("NATIVE BRIDGE EXCEPTION ${e.javaClass.simpleName}: ${e.message}") }
''','addJavascriptInterface')

# Native bridge + release helper before applyMobilePatch
marker='    private fun applyMobilePatch(w: WebView) {\n'
bridge=r'''    private inner class NativeBridge {
        @JavascriptInterface
        fun postJsonAsync(requestId: String, url: String, apiKey: String, body: String, timeoutMs: Int) {
            val id = requestId.take(160)
            translationExecutor.execute {
                val result = try {
                    val u = URL(url)
                    val host = u.host.lowercase(Locale.US)
                    if (u.protocol.lowercase(Locale.US) != "https" || !(host == "tokenhub.tencentmaas.com" || host.endsWith(".tencentmaas.com"))) {
                        JSONObject().put("ok", false).put("error", "仅允许访问 Tencent TokenHub HTTPS 域名").toString()
                    } else {
                        val t = timeoutMs.coerceIn(10_000, 180_000)
                        val c = (u.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = t
                            readTimeout = t
                            doOutput = true
                            useCaches = false
                            instanceFollowRedirects = true
                            setRequestProperty("Authorization", "Bearer $apiKey")
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("User-Agent", "TaimaninRPGXViewer-Mobile/1.0.3")
                        }
                        try {
                            c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                            val status = c.responseCode
                            val stream = if (status in 200..299) c.inputStream else c.errorStream
                            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                            JSONObject().put("ok", true).put("status", status).put("body", raw).toString()
                        } finally { c.disconnect() }
                    }
                } catch (e: Throwable) {
                    JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message ?: "网络请求失败"}").toString()
                }
                mainHandler.post {
                    val ww = web ?: return@post
                    val js = "window.__taNativeHttpResolve&&window.__taNativeHttpResolve(${JSONObject.quote(id)},${JSONObject.quote(result)});"
                    try { ww.evaluateJavascript(js, null) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun releaseWebInput(reason: String) {
        val w = web ?: return
        val q = JSONObject.quote(reason)
        try { w.evaluateJavascript("window.__taimaninTouchForceRelease&&window.__taimaninTouchForceRelease($q);", null) }
        catch (_: Throwable) {}
    }

'''
rep(marker,bridge+marker,'native bridge block')

# Window focus: release on both loss and regain; still immersive on focus.
rep('''    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }''', '''    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        releaseWebInput(if (hasFocus) "android-focus-return" else "android-focus-lost")
        if (hasFocus) enterImmersive()
    }

    override fun onPause() {
        releaseWebInput("android-onPause")
        super.onPause()
    }

    override fun onStop() {
        releaseWebInput("android-onStop")
        super.onStop()
    }''','focus lifecycle')

# Before dialogs / back routing, explicitly release any held Viewer input.
rep('    private fun showDiagnostics() {\n        val w = web ?: return\n',
    '    private fun showDiagnostics() {\n        releaseWebInput("diagnostics")\n        val w = web ?: return\n','diagnostics release')
rep('''    override fun onBackPressed(){
        val w=web''','''    override fun onBackPressed(){
        releaseWebInput("android-back")
        val w=web''','back release')

# Shutdown network worker.
rep('    override fun onDestroy(){ pendingLandscapeRoot=null; landscapeStartScheduled=false; stopViewer(); super.onDestroy() }',
    '    override fun onDestroy(){ pendingLandscapeRoot=null; landscapeStartScheduled=false; stopViewer(); try{translationExecutor.shutdownNow()}catch(_:Throwable){}; super.onDestroy() }',
    'destroy executor')

# Pre-Viewer event gate injected into index.html before any Viewer JS registers listeners.
prelude=r'''<script>
(function(){
 if(window.__taInputGateInstalled)return;window.__taInputGateInstalled=true;window.__TA_INPUT_BLOCK__=false;
 var SEL='#__ta_panel,#__ta_edge,#__ta_radial_layer,#__ta_toast,#__ta_pick_hint';
 var TYPES=new Set(['pointerdown','pointermove','pointerup','pointercancel','mousedown','mousemove','mouseup','touchstart','touchmove','touchend','touchcancel','click','dblclick','contextmenu','keydown','keyup','keypress']);
 var OA=EventTarget.prototype.addEventListener,OR=EventTarget.prototype.removeEventListener,WM=new WeakMap();
 function addon(n){try{return !!(n&&n.nodeType===1&&n.closest&&n.closest(SEL));}catch(e){return false;}}
 function outside(e){try{var x,y;if(e.touches&&e.touches.length){x=e.touches[0].clientX;y=e.touches[0].clientY;}else if(e.changedTouches&&e.changedTouches.length){x=e.changedTouches[0].clientX;y=e.changedTouches[0].clientY;}else if(typeof e.clientX==='number'){x=e.clientX;y=e.clientY;}else return false;var c=document.getElementById('content');if(!c)return false;var r=c.getBoundingClientRect();return x<r.left||x>r.right||y<r.top||y>r.bottom;}catch(_){return false;}}
 function blocked(e,self){var sa=addon(self),ta=addon(e.target);if(ta&&!sa)return true;if(window.__TA_INPUT_BLOCK__&&!sa)return true;if(outside(e)&&!sa)return true;return false;}
 EventTarget.prototype.addEventListener=function(type,listener,opt){if(!listener||!TYPES.has(type))return OA.call(this,type,listener,opt);var target=this,wrapped=function(e){if(blocked(e,target))return;if(typeof listener==='function')return listener.call(this,e);if(listener&&typeof listener.handleEvent==='function')return listener.handleEvent(e);};var byTarget=WM.get(listener);if(!byTarget){byTarget=new WeakMap();WM.set(listener,byTarget);}var byType=byTarget.get(target);if(!byType){byType={};byTarget.set(target,byType);}byType[type]=wrapped;return OA.call(target,type,wrapped,opt);};
 EventTarget.prototype.removeEventListener=function(type,listener,opt){try{var w=WM.get(listener)?.get(this)?.[type];if(w)return OR.call(this,type,w,opt);}catch(e){}return OR.call(this,type,listener,opt);};
})();
</script>'''
# Insert branch inside server after file resolution / existence check.
server_anchor='''                if(file==null||!file.isFile){notFoundCount.incrementAndGet();synchronized(missing){if(!missing.contains(path))missing.add(path)};sendBytes(out,404,"Not Found","text/plain; charset=utf-8","404 $path".toByteArray(),method=="HEAD");return}
                event("$method $path :: FileInputStream")'''
server_repl='''                if(file==null||!file.isFile){notFoundCount.incrementAndGet();synchronized(missing){if(!missing.contains(path))missing.add(path)};sendBytes(out,404,"Not Found","text/plain; charset=utf-8","404 $path".toByteArray(),method=="HEAD");return}
                if(path.equals("index.html", true)){
                    val html=try{file.readText(Charsets.UTF_8)}catch(_:Throwable){file.readText()}
                    val inject="""'''+prelude.replace('"""','')+'''"""
                    val pos=html.indexOf("<head",ignoreCase=true)
                    val at=if(pos>=0)html.indexOf('>',pos).let{if(it>=0)it+1 else 0}else 0
                    val patched=if(at>0)html.substring(0,at)+inject+html.substring(at) else inject+html
                    val bytes=patched.toByteArray(StandardCharsets.UTF_8)
                    sendBytes(out,200,"OK","text/html; charset=utf-8",bytes,method=="HEAD");okCount.incrementAndGet();event("$method $path :: INPUT-GATE");return
                }
                event("$method $path :: FileInputStream")'''
rep(server_anchor,server_repl,'index input gate')

SRC.write_text(s,encoding='utf-8')
print('Patched v0.9.6 Recovery source -> Mobile v1.0.3 native bridge/input gate')

# Replace the Recovery touch addon with the fully integrated Mobile v1.0.3 addon.
parts=[Path(f"mobile_v103/touch{i}.txt").read_text(encoding="ascii").strip() for i in range(1,5)]
asset=Path("app/src/main/assets/touch_addon.js")
asset.parent.mkdir(parents=True,exist_ok=True)
asset.write_bytes(gzip.decompress(base64.b64decode("".join(parts))))
print("Installed Mobile v1.0.3 touch addon asset")
