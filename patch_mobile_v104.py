from pathlib import Path

SRC = Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
s = SRC.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'{label}: marker missing')
    s = s.replace(old, new, count)

# This patch is applied AFTER patch_v083.py and patch_mobile_v103.py.
# It completes the native translation architecture:
#   WebView fetch(TokenHub) -> Android native HTTPS -> Tencent
#   localStorage translation commit -> Android native -> Translation/<Scene>.json
# It also prevents the real TokenHub API key from being served to page JavaScript.

# Visible version labels.
s = s.replace('v1.0.3 Mobile · 原生翻译桥 / 输入隔离版',
              'v1.0.4 Mobile · 原生翻译代理 / Translation落盘版')
s = s.replace('APP: v1.0.3 Mobile', 'APP: v1.0.4 Mobile')
s = s.replace('MODE: Mobile v1.0.3 + native TokenHub bridge + strict input gate + keep-screen-on',
              'MODE: Mobile v1.0.4 + native TokenHub proxy + durable Translation cache + strict input gate')

# Extra imports used by durable cache metadata / hashing.
rep('import java.nio.charset.StandardCharsets\n',
    'import java.nio.charset.StandardCharsets\nimport java.security.MessageDigest\nimport java.text.SimpleDateFormat\n',
    'hash/date imports')
rep('import java.util.Locale\n',
    'import java.util.Locale\nimport java.util.Date\nimport java.util.TimeZone\n',
    'date utility imports')

# Native side owns the real API key. Keep the JS-interface signature compatible,
# but ignore the key passed by JavaScript.
rep('setRequestProperty("Authorization", "Bearer $apiKey")',
    'setRequestProperty("Authorization", "Bearer ${loadTokenHubApiKey()}")',
    'native API key ownership')

# Add config reader and durable Translation JSON writer inside NativeBridge.
bridge_anchor = '''        @JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean): Boolean {'''
bridge_extra = r'''        private fun loadTokenHubApiKey(): String {
            val root = viewerRoot ?: throw IllegalStateException("Viewer 根目录尚未就绪")
            val file = File(root, "translation_config.json")
            if (!file.isFile) throw IllegalStateException("缺少 translation_config.json")
            val cfg = JSONObject(file.readText(Charsets.UTF_8))
            val api = cfg.optJSONObject("api")
            val key = (api?.optString("key", "") ?: "").trim().ifBlank { cfg.optString("key", "").trim() }
            if (key.isBlank() || key == "__NATIVE_BRIDGE__") throw IllegalStateException("translation_config.json 的 API key 为空")
            return key
        }

        private fun safeSceneId(sceneId: String): String {
            val safe = sceneId.trim().replace(Regex("[^A-Za-z0-9_.-]+"), "_")
            if (safe.isBlank()) throw IllegalArgumentException("Scene ID 为空")
            return safe
        }

        private fun sha256Text(text: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun nowIsoUtc(): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date())
        }

        private fun writeJsonDurably(file: File, obj: JSONObject) {
            file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IllegalStateException("无法创建目录：${it.absolutePath}") }
            val text = obj.toString(2) + "\n"
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.outputStream().buffered().use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            if (file.exists() && !file.delete()) {
                tmp.delete()
                throw IllegalStateException("无法替换缓存文件：${file.absolutePath}")
            }
            if (!tmp.renameTo(file)) {
                try { file.writeText(text, Charsets.UTF_8) }
                finally { tmp.delete() }
            }
            if (!file.isFile || file.length() <= 2L) throw IllegalStateException("Translation 缓存落盘校验失败")
        }

        @JavascriptInterface
        fun persistTranslationMap(sceneId: String, rawMap: String): String {
            return try {
                val scene = safeSceneId(sceneId)
                val root = viewerRoot ?: throw IllegalStateException("Viewer 根目录尚未就绪")
                val map = JSONObject(rawMap)
                val dir = File(root, "Translation")
                if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("无法创建 Translation 目录")
                val file = File(dir, "$scene.json")

                var state = if (file.isFile) {
                    try { JSONObject(file.readText(Charsets.UTF_8)) } catch (_: Throwable) { JSONObject() }
                } else JSONObject()
                if (state.optString("scene_id", "").isNotBlank() && state.optString("scene_id") != scene) state = JSONObject()

                val now = nowIsoUtc()
                if (!state.has("schema_version")) state.put("schema_version", 1)
                state.put("scene_id", scene)
                if (!state.has("source")) state.put("source", JSONObject()
                    .put("scene_type", "mobile-visible-cache")
                    .put("script_scene_id", scene)
                    .put("script_paths", org.json.JSONArray())
                    .put("script_hash", "")
                    .put("entry_count", 0)
                    .put("parser_supported", false))
                if (!state.has("progress")) state.put("progress", JSONObject()
                    .put("complete", false).put("translated", 0).put("passthrough", 0)
                    .put("pending", 0).put("stale", 0).put("total", 0).put("updated_at", now))
                if (!state.has("usage")) state.put("usage", JSONObject()
                    .put("successful_requests", 0).put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0))
                if (!state.has("entries")) state.put("entries", JSONObject())

                val visible = state.optJSONObject("visible_cache") ?: JSONObject().also { state.put("visible_cache", it) }
                var changed = 0
                val keys = map.keys()
                while (keys.hasNext()) {
                    val source = keys.next().trim()
                    val zh = map.optString(source, "").trim()
                    if (source.isBlank() || zh.isBlank()) continue
                    val hash = sha256Text(source)
                    val old = visible.optJSONObject(hash)
                    if (old == null || old.optString("translation") != zh || old.optString("source") != source) {
                        visible.put(hash, JSONObject()
                            .put("source", source)
                            .put("source_hash", hash)
                            .put("translation", zh)
                            .put("translated_at", now)
                            .put("provider", "mobile_native_bridge")
                            .put("model", JSONObject.NULL)
                            .put("glossary", JSONObject.NULL)
                            .put("request_id", JSONObject.NULL)
                            .put("usage", JSONObject()
                                .put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)))
                        changed++
                    }
                }
                if (!state.has("created_at")) state.put("created_at", now)
                state.put("updated_at", now)
                writeJsonDurably(file, state)
                JSONObject().put("ok", true).put("scene", scene).put("changed", changed).put("path", file.absolutePath).toString()
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message ?: "Translation 缓存写入失败"}").toString()
            }
        }

'''
rep(bridge_anchor, bridge_extra + bridge_anchor, 'durable native bridge')

# Inject a same-page JS shim before Touch Addon runs. It intercepts only Tencent
# TokenHub HTTPS fetches, and routes them through NativeBridge. Ordinary Viewer
# fetch() calls remain untouched. It also mirrors the add-on localStorage scene
# map synchronously to Translation/<Scene>.json; failure rolls localStorage back
# and throws, so the UI cannot report a successful durable write prematurely.
shim_method = r'''    private fun applyTranslationNativeShim(w: WebView) {
        val js = """
(function(){
  try {
    if(window.__taNativeTranslationShimInstalled) return 'already';
    if(!window.TaimaninNative) return 'no-native-bridge';
    window.__taNativeTranslationShimInstalled=true;

    var originalFetch=window.fetch.bind(window);
    var pending=window.__taNativeHttpPending=window.__taNativeHttpPending||Object.create(null);
    var seq=0;
    window.__taNativeHttpResolve=function(id,raw){
      var p=pending[id]; if(!p)return; delete pending[id];
      try{
        var box=JSON.parse(String(raw||''));
        if(!box.ok){var er=new TypeError(box.error||'Native network error');p.reject(er);return;}
        var status=Number(box.status||0),body=String(box.body||'');
        p.resolve({
          ok:status>=200&&status<300,
          status:status,
          statusText:String(status),
          url:p.url,
          redirected:false,
          type:'basic',
          headers:{get:function(){return null;}},
          text:async function(){return body;},
          json:async function(){return JSON.parse(body);}
        });
      }catch(e){p.reject(e);}
    };

    window.fetch=function(input,init){
      var rawUrl=(typeof input==='string')?input:(input&&input.url?input.url:String(input||''));
      var abs;try{abs=new URL(rawUrl,location.href).href;}catch(_){return originalFetch(input,init);}
      var u;try{u=new URL(abs);}catch(_){return originalFetch(input,init);}
      var host=String(u.hostname||'').toLowerCase();
      if(!(u.protocol==='https:'&&(host==='tokenhub.tencentmaas.com'||host.endsWith('.tencentmaas.com')))) return originalFetch(input,init);
      init=init||{};
      return new Promise(function(resolve,reject){
        var id='n'+Date.now().toString(36)+(++seq).toString(36);
        pending[id]={resolve:resolve,reject:reject,url:abs};
        var sig=init.signal;
        if(sig){
          if(sig.aborted){delete pending[id];var ae=new Error('Aborted');ae.name='AbortError';reject(ae);return;}
          try{sig.addEventListener('abort',function(){if(!pending[id])return;delete pending[id];var ae=new Error('Aborted');ae.name='AbortError';reject(ae);},{once:true});}catch(_){ }
        }
        try{
          window.TaimaninNative.postJsonAsync(id,abs,'',String(init.body||''),120000);
        }catch(e){delete pending[id];reject(e);}
      });
    };

    var PREFIX='ta_mobile_translation_override_v2:';
    var os=Storage.prototype.setItem,og=Storage.prototype.getItem,orm=Storage.prototype.removeItem;
    Storage.prototype.setItem=function(k,v){
      var key=String(k),val=String(v);
      if(this===window.localStorage&&key.indexOf(PREFIX)===0&&window.TaimaninNative&&window.TaimaninNative.persistTranslationMap){
        var old=og.call(this,key),had=old!==null;
        os.call(this,key,val);
        try{
          var rr=JSON.parse(String(window.TaimaninNative.persistTranslationMap(key.slice(PREFIX.length),val)||''));
          if(!rr.ok)throw new Error(rr.error||'Translation 原生落盘失败');
          return;
        }catch(e){
          try{if(had)os.call(this,key,old);else orm.call(this,key);}catch(_){ }
          throw e;
        }
      }
      return os.call(this,key,val);
    };

    // One-time migration: preserve translations made by older mobile builds that
    // lived only in WebView localStorage.
    setTimeout(function(){
      try{
        for(var i=0;i<localStorage.length;i++){
          var k=localStorage.key(i);if(!k||k.indexOf(PREFIX)!==0)continue;
          var v=og.call(localStorage,k);if(v)window.TaimaninNative.persistTranslationMap(k.slice(PREFIX.length),v);
        }
      }catch(e){console.error('[TA NATIVE CACHE MIGRATION] '+e);}
    },0);
    return 'installed';
  }catch(e){console.error('[TA NATIVE TRANSLATION SHIM] '+e.stack);return 'error:'+e.message;}
})();
""".trimIndent()
        try { w.evaluateJavascript(js) { r -> addConsole("NATIVE-TR-SHIM $r") } }
        catch (e: Throwable) { addConsole("NATIVE-TR-SHIM EXCEPTION ${e.javaClass.simpleName}: ${e.message}") }
    }

'''
rep('    private fun applyMobilePatch(w: WebView) {\n', shim_method + '    private fun applyMobilePatch(w: WebView) {\n', 'translation shim method')

rep('view?.let { applyMobilePatch(it); mainHandler.postDelayed({ applyTouchAddon(it) }, 180) }',
    'view?.let { applyMobilePatch(it); applyTranslationNativeShim(it); mainHandler.postDelayed({ applyTouchAddon(it) }, 180) }',
    'page-finished shim install')
rep('p.firstOrNull() == "PATCHME" -> { applyMobilePatch(w); mainHandler.postDelayed({ applyTouchAddon(w) }, 180); status.visibility=View.VISIBLE; status.text="Viewer 已启动，正在应用手机适配…" }',
    'p.firstOrNull() == "PATCHME" -> { applyMobilePatch(w); applyTranslationNativeShim(w); mainHandler.postDelayed({ applyTouchAddon(w) }, 180); status.visibility=View.VISIBLE; status.text="Viewer 已启动，正在应用手机适配…" }',
    'poll shim install')

# Never serve the real API key into page JavaScript. The add-on still sees the
# same config shape, but api.key is replaced by a non-empty marker. NativeBridge
# reads the original file directly from viewerRoot when it performs HTTPS.
server_marker = '                event("$method $path :: FileInputStream")\n'
server_insert = r'''                if(path.equals("translation_config.json", true)){
                    try{
                        val obj=JSONObject(file.readText(Charsets.UTF_8))
                        obj.optJSONObject("api")?.let{if(it.has("key"))it.put("key","__NATIVE_BRIDGE__")}
                        if(obj.has("key"))obj.put("key","__NATIVE_BRIDGE__")
                        val bytes=obj.toString().toByteArray(StandardCharsets.UTF_8)
                        sendBytes(out,200,"OK","application/json; charset=utf-8",bytes,method=="HEAD");okCount.incrementAndGet();event("$method $path :: SANITIZED-CONFIG");return
                    }catch(e:Throwable){err("translation config sanitize ${e.javaClass.simpleName}: ${e.message}")}
                }
                event("$method $path :: FileInputStream")
'''
rep(server_marker, server_insert, 'sanitized translation config')

SRC.write_text(s, encoding='utf-8')
print('Patched Mobile v1.0.3 -> v1.0.4 native proxy + durable Translation cache')
