from pathlib import Path
import re

SRC=Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
ASSET=Path('app/src/main/assets/touch_addon.js')
GRADLE=Path('app/build.gradle.kts')
s=SRC.read_text('utf-8'); a=ASSET.read_text('utf-8'); g=GRADLE.read_text('utf-8')

def rs(old,new,label,count=1):
 global s
 if old not in s: raise SystemExit(label+' source marker missing')
 s=s.replace(old,new,count)
def rj(pattern,new,label,count=1,flags=re.S):
 global a
 a2,n=re.subn(pattern,new,a,count=count,flags=flags)
 if n!=count: raise SystemExit(f'{label} js marker count={n}')
 a=a2

# Native model requests: allow the visible line to bypass a slow background call.
rs('Executors.newSingleThreadExecutor { r ->\n        Thread(r, "taimanin-translation-http")',
   'Executors.newFixedThreadPool(3) { r ->\n        Thread(r, "taimanin-translation-http")','translation pool')

# One cloud glossary fetch per Viewer session; RAM only.
anchor='''    private val glossaryExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->\n        Thread(r, "taimanin-glossary-sync").apply { isDaemon = true }\n    }\n'''
rs(anchor,anchor+'''    @Volatile private var runtimeGlossaryJson: String? = null\n    @Volatile private var runtimeGlossaryError: String? = null\n    @Volatile private var glossarySessionId: Long = 0L\n''','glossary RAM fields')
rs('        private fun fetchGlossaryFromCloud(timeoutMs: Int): String {','        private fun fetchGlossaryFromCloudUncached(timeoutMs: Int): String {','rename uncached glossary')

sync_anchor='''        @JavascriptInterface\n        fun syncGlossaryAsync(requestId: String, timeoutMs: Int) {'''
bridge=r'''        fun fetchGlossaryFromCloud(timeoutMs: Int): String {
            runtimeGlossaryJson?.let { return it }
            runtimeGlossaryError?.let { throw IllegalStateException(it) }
            return try {
                fetchGlossaryFromCloudUncached(timeoutMs).also { runtimeGlossaryJson = it; runtimeGlossaryError = null }
            } catch (e: Throwable) {
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "术语库同步失败"}"
                runtimeGlossaryError = msg
                throw e
            }
        }

        @JavascriptInterface
        fun readTranslationState(sceneId: String): String = try {
            val root = viewerRoot ?: throw IllegalStateException("Viewer 根目录尚未就绪")
            val scene = safeSceneId(sceneId)
            val f = File(File(root,"Translation"),"$scene.json")
            if(f.isFile) f.readText(Charsets.UTF_8) else "{}"
        } catch(e:Throwable) {
            JSONObject().put("__native_error","${e.javaClass.simpleName}: ${e.message ?: "Translation读取失败"}").toString()
        }

        @JavascriptInterface
        fun listAudioCatalog(kindRaw: String): String = try {
            val root = viewerRoot ?: throw IllegalStateException("Viewer 根目录尚未就绪")
            val kind = when(kindRaw.trim().uppercase(Locale.US)){"BGM"->"BGM";"INSERT"->"Insert";else->throw IllegalArgumentException("只允许读取 BGM / Insert")}
            val base=File(root,kind); val arr=org.json.JSONArray(); val exts=setOf("mp3","ogg","wav","m4a","aac","flac","opus","webm")
            if(base.isDirectory){
                val rootCanon=root.canonicalPath
                base.walkTopDown().filter{it.isFile&&it.extension.lowercase(Locale.US) in exts}.toList()
                    .sortedBy{try{it.relativeTo(base).path.lowercase(Locale.US)}catch(_:Throwable){it.name.lowercase(Locale.US)}}
                    .forEach{f->
                        val c=try{f.canonicalFile}catch(_:Throwable){return@forEach}; val cp=c.canonicalPath
                        if(cp!=rootCanon&&!cp.startsWith(rootCanon+File.separator))return@forEach
                        val rel=try{c.relativeTo(root).path.replace(File.separatorChar,'/')}catch(_:Throwable){return@forEach}
                        val label=try{c.relativeTo(base).path.replace(File.separatorChar,'/')}catch(_:Throwable){c.name}
                        arr.put(JSONObject().put("name",c.name).put("label",label).put("rel",rel).put("url","./"+rel.split('/').joinToString("/"){Uri.encode(it)}))
                    }
            }
            arr.toString()
        } catch(e:Throwable) { JSONObject().put("error","${e.javaClass.simpleName}: ${e.message ?: "音频目录读取失败"}").toString() }

'''
rs(sync_anchor,bridge+sync_anchor,'native file bridges')

release='    private fun releaseWebInput(reason: String) {\n'
preload=r'''    private fun preloadGlossaryThenLoad(session:Long,w:WebView,srv:FileHttpServer,status:TextView){
        status.visibility=View.VISIBLE;status.text="正在一次性载入云端术语库…";pageStatus="预载云端术语库"
        glossaryExecutor.execute{
            val result=try{NativeBridge().fetchGlossaryFromCloud(90_000)}catch(e:Throwable){JSONObject().put("ok",false).put("error","${e.javaClass.simpleName}: ${e.message ?: "术语库同步失败"}").toString()}
            val ok=try{JSONObject(result).optBoolean("ok",false)}catch(_:Throwable){false}
            if(session==glossarySessionId&&!ok){runtimeGlossaryJson=null;runtimeGlossaryError=try{JSONObject(result).optString("error","术语库同步失败")}catch(_:Throwable){"术语库同步失败"}}
            mainHandler.post{
                if(session!=glossarySessionId||web!==w||!viewerActive)return@post
                status.text=if(ok)"术语库已载入 · 正在启动 Viewer…" else "术语库载入失败，Viewer 仍将启动"
                pageStatus="加载 index.html";w.loadUrl("http://127.0.0.1:${srv.port}/index.html")
            }
        }
    }

'''
rs(release,preload+release,'preload helper')
rs('''            viewerRoot = root.canonicalFile\n            val srv = FileHttpServer(viewerRoot!!)\n''','''            viewerRoot = root.canonicalFile\n            val glossarySession = ++glossarySessionId\n            runtimeGlossaryJson=null;runtimeGlossaryError=null\n            val srv = FileHttpServer(viewerRoot!!)\n''','start glossary session')
rs('''            pageStatus = "加载 index.html"\n            w.loadUrl("http://127.0.0.1:${srv.port}/index.html")\n''','''            preloadGlossaryThenLoad(glossarySession,w,srv,status)\n''','defer viewer load')
rs('try{server?.stop()}catch(_:Throwable){};server=null;viewerRoot=null','try{server?.stop()}catch(_:Throwable){};server=null;glossarySessionId++;runtimeGlossaryJson=null;runtimeGlossaryError=null;viewerRoot=null','clear glossary RAM')

# Formal labels only; error text remains unchanged.
s=s.replace('v1.0.7 Hybrid · Pro主翻译 / Hy4润色 / Plus机器翻译','V1.0 正式版 · 独立移动端')
s=s.replace('APP: v1.0.7 Hybrid','APP: Taimanin Mobile V1.0')
s=s.replace('MODE: Mobile v1.0.7 Hybrid + native Tencent proxy + cloud glossary RAM sync + durable Translation cache','MODE: Mobile V1.0 + current-line priority + RAM glossary + durable Translation cache')
s=s.replace('TaimaninRPGXViewer-Mobile/1.0.7','TaimaninRPGXViewer-Mobile/1.0.8')

# Direct native BGM/Insert scan.  No Windows-generated catalog dependency.
rj(r"async function loadAudioCatalog\(kind\)\{.*?\n\}(?=\nfunction normalizeTrack)","""async function loadAudioCatalog(kind){
 try{if(!window.TaimaninNative||typeof window.TaimaninNative.listAudioCatalog!=='function')throw new Error('当前 APK 缺少原生音频目录读取桥');const d=JSON.parse(String(window.TaimaninNative.listAudioCatalog(kind)||'[]'));if(d&&d.error)throw new Error(d.error);return Array.isArray(d)?d:[];}catch(e){const m=String(e&&e.message?e.message:e);kind==='BGM'?bgmStatus('BGM目录读取失败：'+m,true):insertStatus('Insert目录读取失败：'+m,true);return [];}
}""",'audio scan')
a=a.replace("const msg='未找到 '+kind+' 列表；首版 APK 请保留 Windows v12.1 生成的 catalog';","const msg=kind+' 文件夹中没有可播放音频';")

# Scene Translation JSON is read synchronously through the native bridge before
# chooseScene continues, so cached Chinese is ready before the first paint.
rj(r"async function trLoadScene\(id\)\{.*?\n\}(?=\nfunction trLeaveScene)","""async function trLoadScene(id){
 id=String(id||'');if(!id)return;if(MTR.loading===id)return;MTR.loading=id;let map=new Map();try{if(window.TaimaninNative&&typeof window.TaimaninNative.readTranslationState==='function'){const d=JSON.parse(String(window.TaimaninNative.readTranslationState(id)||'{}'));if(d&&d.__native_error)throw new Error(d.__native_error);map=trParseState(d);}}catch(e){console.error('[TA TRANSLATION CACHE] '+e);}const loc=trLocalObj(id);MTR.local=new Map(Object.entries(loc));MTR.local.forEach((v,k)=>map.set(k,v));MTR.cache=map;MTR.scene=id;MTR.loading='';MTR.seenText='';MTR.requestedText='';MTR.lastSourceAt=0;MTR.fastUntil=0;MTR.autoFatal='';trClearRollback();trPrepareBackground(id);if(MTR.enabled){trApplyCurrent();queueMicrotask(trVisibleTick);}
}""",'native Translation read')

# MutationObserver runs before browser paint, eliminating the cached-Japanese flash.
rj(r"function trReplace\(el,zh\)\{.*?\}(?=\nfunction trRestoreEl)",r"""function trReplace(el,zh){if(!el||!zh)return;zh=trClean(zh);const now=trClean(el.innerText||el.textContent||'');if(el.dataset?.taTrZh===zh&&now===zh)return;const source=trSource(el);if(!source)return;if(el.dataset.taTrZh&&now!==el.dataset.taTrZh){delete el.dataset.taTrHtml;delete el.dataset.taTrJa;delete el.dataset.taTrZh;}if(!el.dataset.taTrHtml){el.dataset.taTrHtml=el.innerHTML;el.dataset.taTrJa=source;}el.dataset.taTrZh=zh;el.innerHTML=String(zh).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\r?\n/g,'<br>');}""",'instant replace')
a=a.replace("rollbackSource:'',rollbackStack:[],actionBusy:false","rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null")

# Remove the global JS request queue. Native has a 3-worker bounded pool; one
# background request can no longer block the current line.
a=a.replace("function trApiSerial(task){const run=MTR.apiChain.then(task,task);MTR.apiChain=run.catch(()=>{});return run;}","function trApiSerial(task){return Promise.resolve().then(task);}")

rj(r"function trVisibleTick\(\)\{.*?\n\}(?=\nasync function trBackgroundTick)","""function trVisibleTick(){
 if(!MTR.enabled||!trScenePlaying()||trLogVisible()||MTR.autoFatal)return;const e=trTextEl();if(!e)return;const src=trSource(e);if(!src)return;trSetActiveLine(src);const zh=MTR.cache.get(src);if(zh){trApplyCurrent();return;}const now=Date.now();if(src!==MTR.seenText){if(MTR.lastSourceAt&&now-MTR.lastSourceAt<160)MTR.fastUntil=now+650;MTR.lastSourceAt=now;MTR.seenText=src;MTR.seenAt=now;MTR.requestedText='';return;}if(now<MTR.fastUntil){trStatus('检测到快速跳过 · 暂停提交新翻译请求');return;}if(now-MTR.seenAt<90||src===MTR.requestedText)return;trAutoVisible(src);
}
function trCurrentNeedsPriority(){try{const src=trSource(trTextEl());return !!(src&&!MTR.cache.has(src));}catch(e){return false;}}""",'visible priority')
rj(r"async function trBackgroundTick\(\)\{.*?\n\}(?=\nasync function trMachineCurrent)","""async function trBackgroundTick(){
 if(!MTR.enabled||MTR.bgBusy||MTR.autoFatal||!trScenePlaying()||trLogVisible()||Date.now()<MTR.fastUntil||Date.now()<MTR.bgNextAt||trCurrentNeedsPriority())return;if(!MTR.scene||MTR.bgScene!==MTR.scene||!MTR.bgQueue.length){trUpdateBgStatus();return;}let src='';while(MTR.bgQueue.length&&!src){const x=MTR.bgQueue.shift();if(!MTR.cache.has(x)&&!MTR.bgFailed.has(x))src=x;}if(!src){trUpdateBgStatus();return;}MTR.bgBusy=true;try{const zh=await trProOne(src,'','',false);trPersist(src,zh);MTR.bgDone++;trUpdateBgStatus();}catch(e){const msg=String(e.message||e),fatal=trFatalMessage(msg);if(fatal){MTR.autoFatal=fatal;trStatus('后台自动翻译已暂停：'+fatal,true);trUpdateBgStatus('',true);}else{MTR.bgFailed.add(src);trUpdateBgStatus('跳过1条失败/拒绝项',true);}}finally{MTR.bgBusy=false;MTR.bgNextAt=Date.now()+1500;}
}""",'background priority')
a=a.replace("if(MTR.enabled){trStatus('翻译已开启 · 后台/默认使用 Hy-MT2-Pro；Plus 作为机器翻译保底');trEnsureGlossary().catch(()=>{});const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);else{trApplyCurrent();queueMicrotask(trVisibleTick);queueMicrotask(trBackgroundTick);}}","if(MTR.enabled){trStatus('翻译已开启 · 当前句优先 · 后台使用 Hy-MT2-Pro 慢速补翻');trEnsureGlossary().catch(()=>{});const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);trApplyCurrent();queueMicrotask(trVisibleTick);queueMicrotask(trBackgroundTick);}")
rj(r"function startTranslationMobile\(\)\{.*?\n\}(?=\n\nfunction createPanel)","""function trInstallInstantTranslationObserver(){if(MTR.instantObserver)return;const mo=new MutationObserver(()=>{if(!MTR.enabled)return;trApplyCurrent();queueMicrotask(trVisibleTick);});mo.observe(document.documentElement,{subtree:true,childList:true,characterData:true});MTR.instantObserver=mo;}
function startTranslationMobile(){trWrapChooseScene();trInstallInstantTranslationObserver();trEnsureGlossary().catch(e=>{trStatus('Pro 术语库同步失败：'+String(e.message||e),true);});setInterval(()=>{if(!ready())return;trWrapChooseScene();if(main.view.current===1){if(MTR.scene)trLeaveScene();return;}const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);if(MTR.enabled){trApplyCurrent();trVisibleTick();trBackgroundTick();}},80);}""",'translation loop')

# Formal UI synced with PC V1.0.
a=a.replace('Taimanin Mobile · v1.0.7 Hybrid','Taimanin 外挂 · V1.0')
a=a.replace('Pro 主翻译 · Hy4 润色 · Plus 机器翻译 · 当前句可连续回退 · 复制粘贴人工修正','⚠ 在线翻译会调用云端模型并消耗 Token · Pro 主翻译 · Hy4 润色 · Plus 机翻')
a=a.replace('后台：未进入Scene · 术语库启动时从腾讯云同步到RAM','后台：未进入 Scene · 术语库进入 Viewer 时一次性载入 RAM')
a=a.replace('<div id="__ta_translate_status" class="__ta_status">⚠ 在线翻译','<div id="__ta_translate_status" class="__ta_status" style="color:#facc15;opacity:1">⚠ 在线翻译')

if 'data/taimanin_addon_v10' in a or 'index_addon_' in a: raise SystemExit('desktop sidecar dependency remains')

# Same applicationId + same repo key = installable update over v1.0.7.
g=g.replace('versionCode = 107','versionCode = 108').replace('versionName = "1.0.7"','versionName = "1.0.8"')
for m in ['preloadGlossaryThenLoad','readTranslationState','listAudioCatalog','newFixedThreadPool(3)']:
 if m not in s: raise SystemExit('missing native marker '+m)
for m in ['trInstallInstantTranslationObserver','trCurrentNeedsPriority','Taimanin 外挂 · V1.0']:
 if m not in a: raise SystemExit('missing JS marker '+m)
if 'versionCode = 108' not in g or 'mobilev107hybrid' not in g: raise SystemExit('signing/update lineage failed')
SRC.write_text(s,'utf-8');ASSET.write_text(a,'utf-8');GRADLE.write_text(g,'utf-8')
print('Mobile formal V1.0 / build108 patched')
