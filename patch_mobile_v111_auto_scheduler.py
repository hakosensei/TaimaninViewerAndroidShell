from pathlib import Path
import re

ASSET=Path('app/src/main/assets/touch_addon.js')
GRADLE=Path('app/build.gradle.kts')
a=ASSET.read_text('utf-8')
g=GRADLE.read_text('utf-8')

def rep(old,new,label,count=1):
    global a
    n=a.count(old)
    if n < count:
        raise SystemExit(f'{label}: marker missing ({n})')
    a=a.replace(old,new,count)

def rj(pattern,new,label,count=1,flags=re.S):
    global a
    a2,n=re.subn(pattern,new,a,count=count,flags=flags)
    if n!=count:
        raise SystemExit(f'{label}: regex count={n}')
    a=a2

rj(r"function trInferScene\(\)\{\n\s*try\{if\(ready\(\)&&main\.view\.current===1\)return '';\}catch\(e\)\{\}\n",
   "function trInferScene(){\n",
   'remove android view gate from scene inference')
rep("function trScenePlaying(){try{return ready()&&main.view.current!==1;}catch(e){return false;}}",
    "function trScenePlaying(){try{const e=trTextEl();return !!(MTR.scene&&e&&trElementVisible(e));}catch(e){return false;}}",
    'scene playing from visible dialogue')

rep(" rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0",
    " rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0,autoRetryAt:0,lastAutoMs:0,bgLastMs:0,bgActiveSource:''",
    'scheduler fields')

rj(r"function trUpdateBgStatus\(extra='',bad=false\)\{.*?\n\}", r"""function trUpdateBgStatus(extra='',bad=false){
 if(!MTR.scene)return trSceneStatus('后台：未进入 Scene',bad);
 const done=Math.min(MTR.bgTotal,MTR.bgDone),left=Math.max(0,MTR.bgTotal-done-MTR.bgFailed.size);
 let t='后台：Scene '+MTR.scene+' · '+done+'/'+MTR.bgTotal;
 if(MTR.glossaryError)t+=' · 术语同步失败';
 else if(!MTR.glossaryReady)t+=' · Pro术语库同步中';
 if(MTR.autoFatal)t+=' · 已暂停：'+MTR.autoFatal;
 else if(!MTR.enabled)t+=' · 翻译显示关闭';
 else if(MTR.bgBusy)t+=' · 正在翻译后台文本';
 else if(left<=0)t+=' · 已全部完成/跳过拒绝项';
 else if(trCurrentNeedsPriority())t+=' · 等待当前句优先';
 else t+=' · 队列待翻 '+left+' 条';
 if(MTR.bgLastMs>0&&!MTR.bgBusy)t+=' · 上条 '+(MTR.bgLastMs/1000).toFixed(1)+'s';
 if(extra)t+=' · '+extra;trSceneStatus(t,bad||!!MTR.autoFatal);
}""", 'truthful background status')

rep("MTR.scene=id;MTR.loading='';MTR.seenText='';MTR.requestedText='';MTR.lastSourceAt=0;MTR.fastUntil=0;MTR.autoFatal='';",
    "MTR.scene=id;MTR.loading='';MTR.seenText='';MTR.requestedText='';MTR.lastSourceAt=0;MTR.fastUntil=0;MTR.autoRetryAt=0;MTR.autoFatal='';",
    'scene scheduler reset')
rep("MTR.bgBusy=false;MTR.seenText='';MTR.requestedText='';trClearRollback();trSceneStatus('后台：未进入 Scene');",
    "MTR.bgBusy=false;MTR.bgActiveSource='';MTR.seenText='';MTR.requestedText='';MTR.autoRetryAt=0;trClearRollback();trSceneStatus('后台：未进入 Scene');",
    'leave scheduler reset')

rj(r"async function trAutoVisible\(src\)\{.*?\n\}(?=\nfunction trVisibleTick)", r"""async function trAutoVisible(src){
 const seq=++MTR.requestSeq;MTR.requestedText=src;MTR.autoRetryAt=0;const started=performance.now();
 const pulse=setInterval(()=>{if(seq!==MTR.requestSeq||MTR.requestedText!==src)return;const sec=(performance.now()-started)/1000;trStatus('当前句自动翻译中 · Hy-MT2-Pro · '+sec.toFixed(1)+'s');},250);
 trStatus('当前句自动翻译已提交 · Hy-MT2-Pro · 0.0s');
 try{
  const zh=await trProOne(src,'','',false),ms=Math.max(1,Math.round(performance.now()-started));MTR.lastAutoMs=ms;trPersist(src,zh);
  if(seq===MTR.requestSeq&&MTR.enabled){const e=trTextEl();if(e&&trSource(e)===src)trApplyCurrent();trStatus('当前句自动翻译完成 · '+(ms/1000).toFixed(2)+'s · 已写入 Translation');}
  if(MTR.bgSources.includes(src))MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;
  trUpdateBgStatus();
 }catch(e){
  const msg=String(e.message||e),fatal=trFatalMessage(msg);
  if(fatal){MTR.autoFatal=fatal;if(seq===MTR.requestSeq)trStatus('自动翻译已暂停：'+fatal,true);trUpdateBgStatus('',true);}
  else{
   if(seq===MTR.requestSeq){MTR.requestedText='';MTR.autoRetryAt=Date.now()+1200;}
   if(trRefusal(msg)||/sensitive|拒绝/.test(msg)){if(seq===MTR.requestSeq)trStatus('Pro 拒绝当前句 · 1.2s 后允许自动重试/可手动处理',true);}
   else if(seq===MTR.requestSeq)trStatus('当前句自动翻译失败：'+msg+' · 1.2s 后自动重试',true);
  }
 }finally{clearInterval(pulse);}
}""", 'auto visible scheduler')

rj(r"function trVisibleTick\(\)\{.*?\n\}(?=\nfunction trCurrentNeedsPriority)", r"""function trVisibleTick(){
 if(!MTR.enabled||trLogVisible()||MTR.autoFatal)return;
 const e=trTextEl();if(!e||!trElementVisible(e))return;const src=trSource(e);if(!src)return;
 let sid=MTR.scene||trInferScene();if(!MTR.scene&&sid){trLoadScene(sid);return;}
 trSetActiveLine(src);const zh=MTR.cache.get(src);if(zh){trApplyCurrent();return;}
 const now=Date.now();
 if(src!==MTR.seenText){
  MTR.requestSeq++;MTR.requestedText='';MTR.autoRetryAt=0;
  if(MTR.lastSourceAt&&now-MTR.lastSourceAt<90)MTR.fastUntil=now+350;else MTR.fastUntil=0;
  MTR.lastSourceAt=now;MTR.seenText=src;MTR.seenAt=now;trUpdateBgStatus('检测到当前日文，等待 60ms 稳定');return;
 }
 if(now<MTR.fastUntil){trStatus('快速跳过中 · 暂缓提交当前句');return;}
 if(now-MTR.seenAt<60||now<MTR.autoRetryAt||src===MTR.requestedText)return;
 void trAutoVisible(src);
}""", 'visible tick without main.view gate')

rep("function trCurrentNeedsPriority(){try{const src=trSource(trTextEl());return !!(src&&!MTR.cache.has(src));}catch(e){return false;}}",
    "function trCurrentNeedsPriority(){try{const e=trTextEl();if(!e||!trElementVisible(e))return false;const src=trSource(e);return !!(src&&!MTR.cache.has(src));}catch(e){return false;}}",
    'visible current priority')

rj(r"async function trBackgroundTick\(\)\{.*?\n\}(?=\nasync function trMachineCurrent)", r"""async function trBackgroundTick(){
 if(!MTR.enabled||MTR.bgBusy||MTR.autoFatal||trLogVisible()||Date.now()<MTR.fastUntil||Date.now()<MTR.bgNextAt)return;
 const te=trTextEl();if(!MTR.scene||!te||!trElementVisible(te))return;
 if(trCurrentNeedsPriority()){trUpdateBgStatus('当前句未完成，后台让路');return;}
 if(MTR.bgScene!==MTR.scene||!MTR.bgQueue.length){trUpdateBgStatus();return;}
 let src='';while(MTR.bgQueue.length&&!src){const x=MTR.bgQueue.shift();if(!MTR.cache.has(x)&&!MTR.bgFailed.has(x))src=x;}
 if(!src){trUpdateBgStatus();return;}
 MTR.bgBusy=true;MTR.bgActiveSource=src;const started=performance.now();trUpdateBgStatus('已提交后台请求');
 try{const zh=await trProOne(src,'','',false);trPersist(src,zh);MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;MTR.bgLastMs=Math.max(1,Math.round(performance.now()-started));}
 catch(e){const msg=String(e.message||e),fatal=trFatalMessage(msg);if(fatal){MTR.autoFatal=fatal;trStatus('后台自动翻译已暂停：'+fatal,true);trUpdateBgStatus('',true);}else{MTR.bgFailed.add(src);trUpdateBgStatus('后台跳过1条失败/拒绝项',true);}}
 finally{MTR.bgBusy=false;MTR.bgActiveSource='';MTR.bgNextAt=Date.now()+600;trUpdateBgStatus();}
}""", 'background scheduler')

rep("function startTranslationMobile(){trWrapChooseScene();trInstallInstantTranslationObserver();trSyncToggleUI();trEnsureGlossary().catch(e=>{trStatus('Pro 术语库同步失败：'+String(e.message||e),true);});setInterval(()=>{if(!ready())return;trWrapChooseScene();if(main.view.current===1){if(MTR.scene)trLeaveScene();return;}const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);if(MTR.enabled){trApplyCurrent();trVisibleTick();trBackgroundTick();}},120);}",
    "function startTranslationMobile(){trWrapChooseScene();trInstallInstantTranslationObserver();trSyncToggleUI();trEnsureGlossary().catch(e=>{trStatus('Pro 术语库同步失败：'+String(e.message||e),true);});setInterval(()=>{if(!ready())return;trWrapChooseScene();const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);if(MTR.enabled){trApplyCurrent();trVisibleTick();trBackgroundTick();}},80);}",
    'android periodic driver')

a=a.replace('Taimanin 外挂 · V1.1','Taimanin 外挂 · V1.1.1')
a=a.replace('后台：未进入 Scene · 启动预载术语库 · 当前句优先即时翻译','后台：未进入 Scene · 当前句自动翻译有实时时延显示')

if 'versionCode = 110' not in g or 'versionName = "1.1.0"' not in g:
    raise SystemExit('expected V1.1 Gradle version missing')
g=g.replace('versionCode = 110','versionCode = 111').replace('versionName = "1.1.0"','versionName = "1.1.1"')

if "if(main.view.current===1)" in a:
    raise SystemExit('Android main.view.current automatic translation gate still present')
if "return ready()&&main.view.current!==1" in a:
    raise SystemExit('legacy scene-playing gate still present')
for m in ['当前句自动翻译已提交','当前句自动翻译完成','后台让路','autoRetryAt','trElementVisible(te)','Taimanin 外挂 · V1.1.1']:
    if m not in a: raise SystemExit('missing V1.1.1 marker '+m)
if 'versionCode = 111' not in g or 'versionName = "1.1.1"' not in g:
    raise SystemExit('Gradle V1.1.1 update failed')

ASSET.write_text(a,'utf-8')
GRADLE.write_text(g,'utf-8')
print('Mobile V1.1.1 automatic translation scheduler patched')
