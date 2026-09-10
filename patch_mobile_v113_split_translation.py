from pathlib import Path
import re

ASSET=Path('app/src/main/assets/touch_addon.js')
SRC=Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
GRADLE=Path('app/build.gradle.kts')
a=ASSET.read_text('utf-8')
s=SRC.read_text('utf-8')
g=GRADLE.read_text('utf-8')

def rep(old,new,label,count=1):
    global a
    n=a.count(old)
    if n < count:
        raise SystemExit(f'{label}: marker missing ({n})')
    a=a.replace(old,new,count)

def rj(pattern,new,label,count=1,flags=re.S):
    global a
    a2,n=re.subn(pattern,lambda _m:new,a,count=count,flags=flags)
    if n!=count:
        raise SystemExit(f'{label}: regex count={n}')
    a=a2

# Add genuinely separate scheduler state. Legacy fields remain for compatibility with older UI/helpers.
rep(
" rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0,autoRetryAt:0,lastAutoMs:0,bgLastMs:0,bgActiveSource:'',autoBlocked:new Set(),manualRev:new Map(),autoGuardRetryAt:0,autoLastError:''",
" rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0,autoRetryAt:0,lastAutoMs:0,bgLastMs:0,bgActiveSource:'',autoBlocked:new Set(),manualRev:new Map(),autoGuardRetryAt:0,autoLastError:'',autoInFlight:new Map(),autoRetryBySource:new Map(),autoSeq:0,autoCurrentError:'',bgError:''",
'v113 state fields')

# Manual completion may invalidate an old result for the same source, but MUST NOT mutate auto/background scheduler state.
rj(r"function trMarkManualCommit\(src\)\{.*?\n\}", r"""function trMarkManualCommit(src){
 src=trClean(src);if(!src)return;
 MTR.manualRev.set(src,trManualRevision(src)+1);
 // Deliberately do not touch autoInFlight / auto retry / bgQueue / bgBusy.
 // The durable cache itself is enough for schedulers to skip this source later.
 trUpdateBgStatus('手动处理完成 · 与自动 Pro 调度完全独立');
}""", 'pure manual commit')

# Status no longer uses one shared backoff gate for both foreground and background.
rj(r"function trUpdateBgStatus\(extra='',bad=false\)\{.*?\n\}", r"""function trUpdateBgStatus(extra='',bad=false){
 if(!MTR.scene)return trSceneStatus('后台：未进入 Scene',bad);
 const done=Math.min(MTR.bgTotal,MTR.bgDone),left=Math.max(0,MTR.bgTotal-done-MTR.bgFailed.size);
 let t='后台 Pro：Scene '+MTR.scene+' · '+done+'/'+MTR.bgTotal;
 if(MTR.glossaryError)t+=' · 术语同步失败';
 else if(!MTR.glossaryReady)t+=' · 术语库同步中';
 if(!MTR.enabled)t+=' · 翻译关闭';
 else if(MTR.bgBusy)t+=' · 正在慢速翻译';
 else if(left<=0)t+=' · 已完成/跳过拒绝项';
 else if(trCurrentNeedsPriority())t+=' · 当前句优先，后台暂停发新请求';
 else t+=' · 待翻 '+left+' 条';
 if(MTR.autoInFlight&&MTR.autoInFlight.size)t+=' · 当前句 Pro '+MTR.autoInFlight.size+' 条在途';
 if(MTR.bgError)t+=' · 后台上次错误：'+MTR.bgError;
 if(MTR.bgLastMs>0&&!MTR.bgBusy)t+=' · 上条 '+(MTR.bgLastMs/1000).toFixed(1)+'s';
 if(extra)t+=' · '+extra;trSceneStatus(t,bad);
}""", 'split status')

# Per-lane HTTP request tag. Native shim consumes this header and does not forward it to Tencent.
rj(r"async function trChat\(url,model,prompt\)\{.*?\n\}", r"""async function trChat(url,model,prompt,lane='manual'){
 const c=await trConfig();return trApiSerial(async()=>{const ctrl=new AbortController(),tm=setTimeout(()=>ctrl.abort(),c.timeoutMs);try{const r=await fetch(url,{method:'POST',headers:{'Authorization':'Bearer '+c.key,'Content-Type':'application/json','Accept':'application/json','X-TA-Translation-Lane':lane},body:JSON.stringify({model,messages:[{role:'user',content:prompt}],stream:false}),signal:ctrl.signal});const raw=await r.text();if(!r.ok)throw new Error('HTTP '+r.status+': '+raw.slice(0,360));let d;try{d=JSON.parse(raw);}catch(e){throw new Error('返回不是JSON');}const ch=(d.choices||[])[0]||{};if(String(ch.finish_reason||'')==='sensitive')throw new Error('模型返回 sensitive');const out=trClean(ch.message?.content||'');if(!out)throw new Error('模型没有返回有效内容');if(trRefusal(out))throw new Error('模型返回内容安全拒绝文本');return out;}catch(e){if(e?.name==='AbortError')throw new Error('腾讯请求超时');if(String(e).includes('Failed to fetch'))throw new Error('Android 原生 TokenHub 代理连接失败');throw e;}finally{clearTimeout(tm);}});
}""", 'lane chat')

rj(r"async function trPlusTranslate\(src,context=''\)\{.*?\n\}", r"""async function trPlusTranslate(src,context='',lane='manual'){
 const c=await trConfig();return trApiSerial(async()=>{const ctrl=new AbortController(),tm=setTimeout(()=>ctrl.abort(),c.timeoutMs);try{const body={model:c.plusModel,text:src,source:c.source,target:c.target,stream:false,glossary_ids:[c.glossaryId]};const cx=trClean(context);if(cx)body.context=cx;const r=await fetch(c.plusUrl,{method:'POST',headers:{'Authorization':'Bearer '+c.key,'Content-Type':'application/json','Accept':'application/json','X-TA-Translation-Lane':lane},body:JSON.stringify(body),signal:ctrl.signal});const raw=await r.text();if(!r.ok)throw new Error('HTTP '+r.status+': '+raw.slice(0,360));let d;try{d=JSON.parse(raw);}catch(e){throw new Error('返回不是JSON');}const ch=(d.choices||[])[0]||{};if(String(ch.finish_reason||'')==='sensitive')throw new Error('Plus 返回 sensitive');const out=trClean(ch.message?.content||'');if(!out)throw new Error('Plus 未返回有效译文');if(trRefusal(out))throw new Error('Plus 返回内容安全拒绝文本');return out;}catch(e){if(e?.name==='AbortError')throw new Error('腾讯请求超时');if(String(e).includes('Failed to fetch'))throw new Error('Android 原生 TokenHub 代理连接失败');throw e;}finally{clearTimeout(tm);}});
}""", 'lane plus')

# Keep durable piece cache shared, but dedupe only inside the same lane.
rj(r"async function trCachedCall\(key,call,force=false\)\{.*?(?=\nasync function trProOne)", r"""async function trCachedCall(key,call,force=false,pendingKey=''){
 const pk=pendingKey||key;
 if(!force){try{const z=localStorage.getItem(key);if(z)return z;}catch(e){}}
 if(!force&&MTR.pending.has(pk))return MTR.pending.get(pk);
 const p=(async()=>{const out=await call();trDurableSet(key,out);return out;})();if(!force)MTR.pending.set(pk,p);
 try{return await p;}finally{if(!force)MTR.pending.delete(pk);}
}""", 'lane pending')

rj(r"async function trProOne\(src,prompt='',context='',force=false\)\{.*?(?=\nasync function trRewriteOne)", r"""async function trProOne(src,prompt='',context='',force=false,lane='manual'){await trEnsureGlossary();const c=await trConfig(),key=MTR_PIECE+'pro:'+c.proModel+':'+src;return trCachedCall(key,()=>trChat(c.proUrl,c.proModel,trProPrompt(src,prompt,context),lane),force,key+':lane:'+lane);}""", 'lane pro')
rj(r"async function trRewriteOne\(src,prompt='',context='',force=false\)\{.*?(?=\nasync function trPlusOne)", r"""async function trRewriteOne(src,prompt='',context='',force=false,lane='manual'){await trEnsureGlossary();const c=await trConfig(),key=MTR_REWRITE+c.rewriteModel+':'+src;return trCachedCall(key,()=>trChat(c.rewriteUrl,c.rewriteModel,trRewritePrompt(src,prompt,context),lane),force,key+':lane:'+lane);}""", 'lane rewrite')
rj(r"async function trPlusOne\(src,context='',force=false\)\{.*?(?=\nfunction trSceneContext)", r"""async function trPlusOne(src,context='',force=false,lane='manual'){const c=await trConfig(),key=MTR_PIECE+'plus:'+c.plusModel+':'+c.glossaryId+':'+src;if(force)return trPlusTranslate(src,context,lane);const pk=key+':lane:'+lane;if(MTR.pending.has(pk))return MTR.pending.get(pk);const p=trPlusTranslate(src,context,lane);MTR.pending.set(pk,p);try{return await p;}finally{MTR.pending.delete(pk);}}""", 'lane plus one')

# Replace foreground auto with a tiny independent per-source guardian. No manual state can stop it.
rj(r"async function trAutoVisible\(src\)\{.*?\n\}(?=\nfunction trVisibleTick)", r"""async function trAutoVisible(src){
 src=trClean(src);if(!src||!MTR.enabled||MTR.cache.has(src)||MTR.autoBlocked.has(src)||MTR.autoInFlight.has(src))return;
 const retryAt=Number(MTR.autoRetryBySource.get(src)||0);if(Date.now()<retryAt)return;
 const token={id:++MTR.autoSeq,rev:trManualRevision(src),started:performance.now()};MTR.autoInFlight.set(src,token);MTR.autoCurrentError='';
 const pulse=setInterval(()=>{if(MTR.autoInFlight.get(src)!==token)return;const sec=(performance.now()-token.started)/1000;trStatus('当前句自动 Pro 翻译中 · '+sec.toFixed(1)+'s');},250);
 trStatus('当前句自动 Pro 已立即提交 · 0.0s');
 try{
  const zh=await trProOne(src,'','',false,'auto-current'),ms=Math.max(1,Math.round(performance.now()-token.started));
  if(trManualRevision(src)!==token.rev){trStatus('当前句旧自动 Pro 结果已丢弃 · 手动译文优先；自动循环不受影响');return;}
  MTR.lastAutoMs=ms;MTR.autoRetryBySource.delete(src);MTR.autoCurrentError='';trPersist(src,zh);
  if(MTR.bgSources.includes(src))MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;
  if(MTR.enabled){const e=trTextEl();if(e&&trSource(e)===src)trApplyCurrent();}
  trStatus('当前句自动 Pro 完成 · '+(ms/1000).toFixed(2)+'s');trUpdateBgStatus();
 }catch(e){
  if(trManualRevision(src)!==token.rev){trStatus('当前句旧自动 Pro 失败已忽略 · 自动循环继续');return;}
  const msg=String(e.message||e);MTR.autoCurrentError=msg;
  if(trContentBlocked(msg)){MTR.autoBlocked.add(src);MTR.autoRetryBySource.delete(src);trStatus('当前句 Pro 拒绝 · 可手动 Plus；下一句自动 Pro 仍会立即工作',true);}
  else{MTR.autoRetryBySource.set(src,Date.now()+1500);trStatus('当前句自动 Pro 临时失败 · 1.5s 后仅重试本句：'+msg,true);}
  trUpdateBgStatus();
 }finally{clearInterval(pulse);if(MTR.autoInFlight.get(src)===token)MTR.autoInFlight.delete(src);}
}""", 'split foreground auto')

rj(r"function trVisibleTick\(\)\{.*?\n\}(?=\nfunction trCurrentNeedsPriority)", r"""function trVisibleTick(){
 if(!MTR.enabled||trLogVisible())return;
 const e=trTextEl();if(!e||!trElementVisible(e))return;const src=trSource(e);if(!src)return;
 let sid=MTR.scene||trInferScene();if(!MTR.scene&&sid){trLoadScene(sid);return;}
 trSetActiveLine(src);const zh=MTR.cache.get(src);if(zh){trApplyCurrent();return;}
 const now=Date.now();
 if(src!==MTR.seenText){
  MTR.seenText=src;MTR.seenAt=now;MTR.lastSourceAt=now;
  trUpdateBgStatus('检测到未翻当前句 · 约 35ms 稳定后立即发 Pro');
  setTimeout(()=>{if(MTR.enabled&&MTR.seenText===src)trVisibleTick();},35);return;
 }
 if(MTR.autoBlocked.has(src)){trStatus('当前句 Pro 已拒绝 · 可手动 Plus；自动循环等待下一句');return;}
 if(now-MTR.seenAt<30)return;
 if(MTR.autoInFlight.has(src))return;
 const retryAt=Number(MTR.autoRetryBySource.get(src)||0);if(now<retryAt)return;
 void trAutoVisible(src);
}""", 'split visible tick')

rj(r"function trCurrentNeedsPriority\(\)\{.*?(?=\nasync function trBackgroundTick)", r"""function trCurrentNeedsPriority(){try{const e=trTextEl();if(!e||!trElementVisible(e))return false;const src=trSource(e);return !!(src&&!MTR.cache.has(src)&&!MTR.autoBlocked.has(src));}catch(e){return false;}}""", 'priority current')

# Background Pro is its own single slow queue. Its errors/backoff never gate foreground or manual lanes.
rj(r"async function trBackgroundTick\(\)\{.*?\n\}(?=\nasync function trMachineCurrent)", r"""async function trBackgroundTick(){
 if(!MTR.enabled||MTR.bgBusy||trLogVisible()||Date.now()<MTR.bgNextAt)return;
 const te=trTextEl();if(!MTR.scene||!te||!trElementVisible(te))return;
 if(trCurrentNeedsPriority()){trUpdateBgStatus('当前句未完成，后台只暂停发新请求');return;}
 if(MTR.bgScene!==MTR.scene||!MTR.bgQueue.length){trUpdateBgStatus();return;}
 let src='';while(MTR.bgQueue.length&&!src){const x=MTR.bgQueue.shift();if(!MTR.cache.has(x)&&!MTR.bgFailed.has(x))src=x;}
 if(!src){trUpdateBgStatus();return;}
 MTR.bgBusy=true;MTR.bgActiveSource=src;MTR.bgError='';const started=performance.now(),rev=trManualRevision(src);trUpdateBgStatus('后台 Pro 已提交');
 try{
  const zh=await trProOne(src,'','',false,'auto-background');
  if(trManualRevision(src)!==rev){trUpdateBgStatus('后台旧结果丢弃 · 手动译文优先；后台继续');return;}
  trPersist(src,zh);MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;MTR.bgLastMs=Math.max(1,Math.round(performance.now()-started));MTR.bgError='';
 }catch(e){
  if(trManualRevision(src)!==rev){trUpdateBgStatus('后台旧失败忽略 · 后台继续');return;}
  const msg=String(e.message||e);MTR.bgError=msg;
  if(trContentBlocked(msg)){MTR.bgFailed.add(src);trUpdateBgStatus('后台跳过 1 条 Pro 拒绝项',true);}
  else{MTR.bgQueue.unshift(src);trUpdateBgStatus('后台临时失败；仅后台队列稍后重试',true);}
 }finally{
  MTR.bgBusy=false;MTR.bgActiveSource='';
  // Slow background cadence: one new request about every 1.5 seconds.
  MTR.bgNextAt=Date.now()+1500;trUpdateBgStatus();
 }
}""", 'split background')

# Manual buttons always use the native manual lane and do not mutate the two auto schedulers.
rep("trPlusOne(src,ctx,true)","trPlusOne(src,ctx,true,'manual')",'manual plus lane')
rep("trProOne(src,prompt,trSceneContext(src,3),true)","trProOne(src,prompt,trSceneContext(src,3),true,'manual')",'manual retry lane')
rep("trProOne(ps[i],prompt,ctx,true)","trProOne(ps[i],prompt,ctx,true,'manual')",'manual split lane')
rep("trRewriteOne(ps[i],prompt,ctx,true)","trRewriteOne(ps[i],prompt,ctx,true,'manual')",'manual hy4 lane')
rep("trProOne(ja,inst,ctx,true)","trProOne(ja,inst,ctx,true,'manual')",'manual post-hy4 pro lane')

# Scene lifecycle resets only the relevant scheduler containers.
rep("MTR.autoBlocked=new Set();MTR.manualRev=new Map();trClearRollback();trPrepareBackground(id);",
    "MTR.autoBlocked=new Set();MTR.manualRev=new Map();MTR.autoInFlight=new Map();MTR.autoRetryBySource=new Map();MTR.autoCurrentError='';MTR.bgError='';trClearRollback();trPrepareBackground(id);",
    'scene split reset')
rep("MTR.autoBlocked=new Set();MTR.manualRev=new Map();trClearRollback();trSceneStatus('后台：未进入 Scene');",
    "MTR.autoBlocked=new Set();MTR.manualRev=new Map();MTR.autoInFlight=new Map();MTR.autoRetryBySource=new Map();MTR.autoCurrentError='';MTR.bgError='';trClearRollback();trSceneStatus('后台：未进入 Scene');",
    'leave split reset')

# Version label.
a=a.replace('Taimanin 外挂 · V1.1.2','Taimanin 外挂 · V1.1.3')
a=a.replace('后台：未进入 Scene · 自动 Pro 守护与手动翻译完全解耦','后台：未进入 Scene · Pro自动当前句 / Pro后台 / 手动翻译三通道完全独立')

# --- Native network split: V1.1.2 uses one shared 3-worker pool for every model request. ---
old_exec = '''    private val translationExecutor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "taimanin-translation-http").apply { isDaemon = true }
    }
'''
new_exec = '''    private val translationAutoExecutor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "taimanin-translation-auto-current").apply { isDaemon = true }
    }
    private val translationBackgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taimanin-translation-auto-background").apply { isDaemon = true }
    }
    private val translationManualExecutor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "taimanin-translation-manual").apply { isDaemon = true }
    }
'''
if old_exec not in s:
    raise SystemExit('native shared translation executor marker missing')
s=s.replace(old_exec,new_exec,1)

old_post = '''        @JavascriptInterface
        fun postJsonAsync(requestId: String, url: String, apiKey: String, body: String, timeoutMs: Int) {
            val id = requestId.take(160)
            translationExecutor.execute {
'''
new_post = '''        @JavascriptInterface
        fun postJsonAsync(requestId: String, url: String, apiKey: String, body: String, timeoutMs: Int) {
            val id = requestId.take(160)
            // Since v1.0.4 the third argument no longer carries an API key; native code reads the real
            // key from translation_config.json. V1.1.3 safely reuses it as a scheduling-lane tag.
            val lane = apiKey.trim().lowercase(Locale.US)
            val executor = when (lane) {
                "auto-current" -> translationAutoExecutor
                "auto-background" -> translationBackgroundExecutor
                else -> translationManualExecutor
            }
            executor.execute {
'''
if old_post not in s:
    raise SystemExit('native postJsonAsync marker missing')
s=s.replace(old_post,new_post,1)

old_shim = "window.TaimaninNative.postJsonAsync(id,abs,'',String(init.body||''),120000);"
new_shim = r'''var lane='manual';
        try{
          var hh=init.headers||{};
          if(typeof Headers!=='undefined'&&hh instanceof Headers)lane=hh.get('X-TA-Translation-Lane')||lane;
          else if(Array.isArray(hh)){for(var hi=0;hi<hh.length;hi++){var hp=hh[hi]||[];if(String(hp[0]||'').toLowerCase()==='x-ta-translation-lane'){lane=String(hp[1]||lane);break;}}}
          else if(hh&&typeof hh==='object')lane=String(hh['X-TA-Translation-Lane']||hh['x-ta-translation-lane']||lane);
        }catch(_){ }
        window.TaimaninNative.postJsonAsync(id,abs,lane,String(init.body||''),120000);'''
if old_shim not in s:
    raise SystemExit('native fetch shim call marker missing')
s=s.replace(old_shim,new_shim,1)

old_destroy="try{translationExecutor.shutdownNow()}catch(_:Throwable){}; try{glossaryExecutor.shutdownNow()}catch(_:Throwable){}"
new_destroy="try{translationAutoExecutor.shutdownNow()}catch(_:Throwable){}; try{translationBackgroundExecutor.shutdownNow()}catch(_:Throwable){}; try{translationManualExecutor.shutdownNow()}catch(_:Throwable){}; try{glossaryExecutor.shutdownNow()}catch(_:Throwable){}"
if old_destroy not in s:
    raise SystemExit('native executor shutdown marker missing')
s=s.replace(old_destroy,new_destroy,1)

# Keep package/signing lineage; only bump app version so V1.1.3 updates V1.1.2 in place.
if 'versionCode = 112' not in g or 'versionName = "1.1.2"' not in g:
    raise SystemExit('expected V1.1.2 Gradle version missing')
g=g.replace('versionCode = 112','versionCode = 113',1).replace('versionName = "1.1.2"','versionName = "1.1.3"',1)

for m in [
    "'X-TA-Translation-Lane':lane", "autoInFlight:new Map()", "'auto-current'",
    "'auto-background'", "true,'manual'", "后台 Pro：Scene", "Taimanin 外挂 · V1.1.3"
]:
    if m not in a: raise SystemExit('missing JS V1.1.3 marker '+m)
for m in ['translationAutoExecutor','translationBackgroundExecutor','translationManualExecutor','postJsonAsync(id,abs,lane']:
    if m not in s: raise SystemExit('missing native V1.1.3 marker '+m)
if 'translationExecutor.execute' in s:
    raise SystemExit('legacy shared translation executor is still used')
if 'versionCode = 113' not in g or 'versionName = "1.1.3"' not in g:
    raise SystemExit('Gradle V1.1.3 update failed')

ASSET.write_text(a,'utf-8')
SRC.write_text(s,'utf-8')
GRADLE.write_text(g,'utf-8')
print('Mobile V1.1.3 fully split translation lanes patched')
