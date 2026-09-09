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
    a2,n=re.subn(pattern,lambda _m:new,a,count=count,flags=flags)
    if n!=count:
        raise SystemExit(f'{label}: regex count={n}')
    a=a2

rep(
    " rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0,autoRetryAt:0,lastAutoMs:0,bgLastMs:0,bgActiveSource:''",
    " rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0,autoRetryAt:0,lastAutoMs:0,bgLastMs:0,bgActiveSource:'',autoBlocked:new Set(),manualRev:new Map(),autoGuardRetryAt:0,autoLastError:''",
    'guardian state fields')

rj(r"function trFatalMessage\(msg\)\{.*?\}",
   r"""function trFatalMessage(msg){const s=String(msg||'');return /translation_config|API key|GlossaryId|SecretId|SecretKey|HTTP\s*(401|403)|原生术语库同步桥/i.test(s)?s:'';}""",
   'narrow fatal classifier')

anchor="function trSetBusy(v){MTR.actionBusy=!!v;trUpdateRollbackButton();}\n"
helpers=r"""function trManualRevision(src){src=trClean(src);return Number(MTR.manualRev.get(src)||0);}
function trMarkManualCommit(src){
 src=trClean(src);if(!src)return;
 MTR.manualRev.set(src,trManualRevision(src)+1);MTR.autoBlocked.delete(src);MTR.bgFailed.delete(src);
 if(Array.isArray(MTR.bgQueue)&&MTR.bgQueue.length)MTR.bgQueue=MTR.bgQueue.filter(x=>x!==src);
 if(Array.isArray(MTR.bgSources))MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;
 if(MTR.requestedText===src){MTR.requestedText='';MTR.autoRetryAt=0;}
 trUpdateBgStatus('手动处理完成 · 自动 Pro 守护继续');
}
function trContentBlocked(msg){const s=String(msg||'');return trRefusal(s)||/sensitive|内容安全|安全拒绝|content.?safety|safety.?policy|policy.?refus|HTTP\s*400/i.test(s);}
function trGuardBackoff(msg,ms=8000){MTR.autoLastError=String(msg||'');MTR.autoFatal=MTR.autoLastError;MTR.autoGuardRetryAt=Date.now()+Math.max(1000,Number(ms)||8000);}
function trGuardRecover(){MTR.autoLastError='';MTR.autoFatal='';MTR.autoGuardRetryAt=0;}
"""
rep(anchor,anchor+helpers,'guardian helpers')

rep(
 "function trCommitNew(source,zh){source=trClean(source);zh=trClean(zh);if(!source||!zh)throw new Error('原文或译文为空');trSetActiveLine(source);const old=trClean(MTR.cache.get(source)||'');if(old&&old!==zh){if(!MTR.rollbackStack.length||trClean(MTR.rollbackStack[MTR.rollbackStack.length-1])!==old)MTR.rollbackStack.push(old);}trPersist(source,zh);trUpdateRollbackButton();return MTR.rollbackStack.length;}",
 "function trCommitNew(source,zh){source=trClean(source);zh=trClean(zh);if(!source||!zh)throw new Error('原文或译文为空');trSetActiveLine(source);const old=trClean(MTR.cache.get(source)||'');if(old&&old!==zh){if(!MTR.rollbackStack.length||trClean(MTR.rollbackStack[MTR.rollbackStack.length-1])!==old)MTR.rollbackStack.push(old);}trPersist(source,zh);trMarkManualCommit(source);trUpdateRollbackButton();return MTR.rollbackStack.length;}",
 'manual commit stamp')
rep("trPersist(src,zh);if(MTR.enabled)trReplace(e,zh);trStatus('已回退到上一次翻译结果并重新写入 Translation'",
    "trPersist(src,zh);trMarkManualCommit(src);if(MTR.enabled)trReplace(e,zh);trStatus('已回退到上一次翻译结果并重新写入 Translation'",
    'rollback manual stamp')

rep(
 "MTR.scene=id;MTR.loading='';MTR.seenText='';MTR.requestedText='';MTR.lastSourceAt=0;MTR.fastUntil=0;MTR.autoRetryAt=0;MTR.autoFatal='';",
 "MTR.scene=id;MTR.loading='';MTR.seenText='';MTR.requestedText='';MTR.lastSourceAt=0;MTR.fastUntil=0;MTR.autoRetryAt=0;MTR.autoFatal='';MTR.autoLastError='';MTR.autoGuardRetryAt=0;MTR.autoBlocked=new Set();MTR.manualRev=new Map();",
 'scene guardian reset')
rep(
 "MTR.bgBusy=false;MTR.bgActiveSource='';MTR.seenText='';MTR.requestedText='';MTR.autoRetryAt=0;trClearRollback();trSceneStatus('后台：未进入 Scene');",
 "MTR.bgBusy=false;MTR.bgActiveSource='';MTR.seenText='';MTR.requestedText='';MTR.autoRetryAt=0;MTR.autoFatal='';MTR.autoLastError='';MTR.autoGuardRetryAt=0;MTR.autoBlocked=new Set();MTR.manualRev=new Map();trClearRollback();trSceneStatus('后台：未进入 Scene');",
 'leave guardian reset')
rep(
 "MTR.requestedText='';MTR.seenText='';MTR.autoFatal='';",
 "MTR.requestedText='';MTR.seenText='';MTR.autoFatal='';MTR.autoLastError='';MTR.autoGuardRetryAt=0;if(MTR.enabled)MTR.autoBlocked=new Set();",
 'toggle guardian reset')

rj(r"function trUpdateBgStatus\(extra='',bad=false\)\{.*?\n\}", r"""function trUpdateBgStatus(extra='',bad=false){
 if(!MTR.scene)return trSceneStatus('后台：未进入 Scene',bad);
 const done=Math.min(MTR.bgTotal,MTR.bgDone),left=Math.max(0,MTR.bgTotal-done-MTR.bgFailed.size),now=Date.now();
 let t='后台：Scene '+MTR.scene+' · '+done+'/'+MTR.bgTotal;
 if(MTR.glossaryError)t+=' · 术语同步失败';
 else if(!MTR.glossaryReady)t+=' · Pro术语库同步中';
 if(!MTR.enabled)t+=' · 翻译显示关闭';
 else if(MTR.autoLastError&&now<MTR.autoGuardRetryAt)t+=' · 自动守护等待重试 '+Math.max(1,Math.ceil((MTR.autoGuardRetryAt-now)/1000))+'s';
 else if(MTR.bgBusy)t+=' · 正在翻译后台文本';
 else if(left<=0)t+=' · 已全部完成/跳过拒绝项';
 else if(trCurrentNeedsPriority())t+=' · 等待当前句优先';
 else t+=' · 队列待翻 '+left+' 条';
 if(MTR.bgLastMs>0&&!MTR.bgBusy)t+=' · 上条 '+(MTR.bgLastMs/1000).toFixed(1)+'s';
 if(extra)t+=' · '+extra;trSceneStatus(t,bad);
}""", 'guardian background status')

rj(r"async function trAutoVisible\(src\)\{.*?\n\}(?=\nfunction trVisibleTick)", r"""async function trAutoVisible(src){
 const seq=++MTR.requestSeq,rev=trManualRevision(src);MTR.requestedText=src;MTR.autoRetryAt=0;const started=performance.now();
 const pulse=setInterval(()=>{if(seq!==MTR.requestSeq||MTR.requestedText!==src)return;const sec=(performance.now()-started)/1000;trStatus('当前句自动翻译中 · Hy-MT2-Pro · '+sec.toFixed(1)+'s');},250);
 trStatus('当前句自动翻译已提交 · Hy-MT2-Pro · 0.0s');
 try{
  const zh=await trProOne(src,'','',false),ms=Math.max(1,Math.round(performance.now()-started));
  if(trManualRevision(src)!==rev){if(seq===MTR.requestSeq)MTR.requestedText='';trStatus('自动 Pro 返回已丢弃 · 当前句已由手动译文接管 · 自动守护继续');return;}
  trGuardRecover();MTR.lastAutoMs=ms;trPersist(src,zh);
  if(seq===MTR.requestSeq&&MTR.enabled){const e=trTextEl();if(e&&trSource(e)===src)trApplyCurrent();trStatus('当前句自动翻译完成 · '+(ms/1000).toFixed(2)+'s · 已写入 Translation');}
  if(MTR.bgSources.includes(src))MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;
  trUpdateBgStatus();
 }catch(e){
  if(trManualRevision(src)!==rev){if(seq===MTR.requestSeq)MTR.requestedText='';trStatus('自动 Pro 的旧请求已忽略 · 手动译文优先 · 自动守护继续');return;}
  const msg=String(e.message||e),hard=trFatalMessage(msg);
  if(trContentBlocked(msg)){
   MTR.autoBlocked.add(src);if(seq===MTR.requestSeq){MTR.requestedText='';MTR.autoRetryAt=0;}
   trStatus('Pro 拒绝当前句 · 可手动 Plus；自动守护没有停止，下一句继续',true);trUpdateBgStatus('当前句已跳过 Pro 拒绝项',true);
  }else if(hard){
   trGuardBackoff(hard,8000);if(seq===MTR.requestSeq){MTR.requestedText='';MTR.autoRetryAt=MTR.autoGuardRetryAt;}
   trStatus('自动 Pro 配置/鉴权错误 · 守护仍在，8s 后重试：'+hard,true);trUpdateBgStatus('',true);
  }else{
   MTR.autoLastError=msg;MTR.autoFatal='';if(seq===MTR.requestSeq){MTR.requestedText='';MTR.autoRetryAt=Date.now()+1500;}
   trStatus('当前句自动翻译临时失败：'+msg+' · 1.5s 后自动重试',true);trUpdateBgStatus('自动守护仍在运行',true);
  }
 }finally{clearInterval(pulse);}
}""", 'independent auto guardian')

rj(r"function trVisibleTick\(\)\{.*?\n\}(?=\nfunction trCurrentNeedsPriority)", r"""function trVisibleTick(){
 if(!MTR.enabled||trLogVisible())return;
 const e=trTextEl();if(!e||!trElementVisible(e))return;const src=trSource(e);if(!src)return;
 let sid=MTR.scene||trInferScene();if(!MTR.scene&&sid){trLoadScene(sid);return;}
 trSetActiveLine(src);const zh=MTR.cache.get(src);if(zh){trApplyCurrent();return;}
 const now=Date.now();
 if(src!==MTR.seenText){
  MTR.requestSeq++;MTR.requestedText='';MTR.autoRetryAt=0;
  if(MTR.lastSourceAt&&now-MTR.lastSourceAt<90)MTR.fastUntil=now+350;else MTR.fastUntil=0;
  MTR.lastSourceAt=now;MTR.seenText=src;MTR.seenAt=now;trUpdateBgStatus('检测到当前日文，等待 60ms 稳定');return;
 }
 if(MTR.autoBlocked.has(src)){trStatus('当前句 Pro 已拒绝 · 可手动 Plus；自动守护等待下一句');return;}
 if(now<MTR.autoGuardRetryAt){trStatus('自动 Pro 守护等待重试 · '+Math.max(1,Math.ceil((MTR.autoGuardRetryAt-now)/1000))+'s');return;}
 if(now<MTR.fastUntil){trStatus('快速跳过中 · 暂缓提交当前句');return;}
 if(now-MTR.seenAt<60||now<MTR.autoRetryAt||src===MTR.requestedText)return;
 void trAutoVisible(src);
}""", 'guardian visible tick')

rep(
 "function trCurrentNeedsPriority(){try{const e=trTextEl();if(!e||!trElementVisible(e))return false;const src=trSource(e);return !!(src&&!MTR.cache.has(src));}catch(e){return false;}}",
 "function trCurrentNeedsPriority(){try{const e=trTextEl();if(!e||!trElementVisible(e))return false;const src=trSource(e);return !!(src&&!MTR.cache.has(src)&&!MTR.autoBlocked.has(src));}catch(e){return false;}}",
 'blocked current no longer freezes background')

rj(r"async function trBackgroundTick\(\)\{.*?\n\}(?=\nasync function trMachineCurrent)", r"""async function trBackgroundTick(){
 if(!MTR.enabled||MTR.bgBusy||trLogVisible()||Date.now()<MTR.fastUntil||Date.now()<MTR.bgNextAt||Date.now()<MTR.autoGuardRetryAt)return;
 const te=trTextEl();if(!MTR.scene||!te||!trElementVisible(te))return;
 if(trCurrentNeedsPriority()){trUpdateBgStatus('当前句未完成，后台让路');return;}
 if(MTR.bgScene!==MTR.scene||!MTR.bgQueue.length){trUpdateBgStatus();return;}
 let src='';while(MTR.bgQueue.length&&!src){const x=MTR.bgQueue.shift();if(!MTR.cache.has(x)&&!MTR.bgFailed.has(x))src=x;}
 if(!src){trUpdateBgStatus();return;}
 MTR.bgBusy=true;MTR.bgActiveSource=src;const started=performance.now(),rev=trManualRevision(src);trUpdateBgStatus('已提交后台请求');
 try{
  const zh=await trProOne(src,'','',false);
  if(trManualRevision(src)!==rev){trUpdateBgStatus('后台旧结果已丢弃 · 手动译文优先');return;}
  trGuardRecover();trPersist(src,zh);MTR.bgDone=MTR.bgSources.filter(x=>MTR.cache.has(x)).length;MTR.bgLastMs=Math.max(1,Math.round(performance.now()-started));
 }catch(e){
  if(trManualRevision(src)!==rev){trUpdateBgStatus('后台旧请求已忽略 · 自动守护继续');return;}
  const msg=String(e.message||e),hard=trFatalMessage(msg);
  if(trContentBlocked(msg)){MTR.bgFailed.add(src);trUpdateBgStatus('后台跳过1条 Pro 拒绝项',true);}
  else if(hard){MTR.bgQueue.unshift(src);trGuardBackoff(hard,8000);trUpdateBgStatus('后台配置/鉴权错误 · 8s 后重试',true);}
  else{MTR.bgQueue.unshift(src);MTR.bgNextAt=Date.now()+1500;MTR.autoLastError=msg;trUpdateBgStatus('后台临时失败 · 1.5s 后重试',true);}
 }finally{MTR.bgBusy=false;MTR.bgActiveSource='';MTR.bgNextAt=Math.max(MTR.bgNextAt,Date.now()+600);trUpdateBgStatus();}
}""", 'independent background guardian')

a=a.replace(";MTR.autoFatal='';trStatus('机器翻译完成", ";trStatus('机器翻译完成")
a=a.replace(";MTR.autoFatal='';trStatus('重译成功", ";trStatus('重译成功")
rep("trStatus('人工译文已写入 Translation · 0 Token'+(n?' · 可回退 '+n+' 次':''));trPrepareBackground(MTR.scene);",
    "trStatus('人工译文已写入 Translation · 0 Token'+(n?' · 可回退 '+n+' 次':'')+' · 自动 Pro 守护继续');trUpdateBgStatus();",
    'manual write does not reset scheduler')
rep("trStatus('机器翻译完成 · Hy-MT2-Plus + 云端Glossary + Scene上下文'+(n?' · 可回退 '+n+' 次':''));trPrepareBackground(MTR.scene);",
    "trStatus('机器翻译完成 · Hy-MT2-Plus + 云端Glossary + Scene上下文'+(n?' · 可回退 '+n+' 次':'')+' · 自动 Pro 守护继续');trUpdateBgStatus();",
    'Plus does not reset scheduler')
rep("trStatus('重译成功并已写入 Translation'+(n?' · 可回退 '+n+' 次':''));trPrepareBackground(MTR.scene);",
    "trStatus('重译成功并已写入 Translation'+(n?' · 可回退 '+n+' 次':'')+' · 自动 Pro 守护继续');trUpdateBgStatus();",
    'manual Pro/Hy4 does not reset scheduler')

a=a.replace('Taimanin 外挂 · V1.1.1','Taimanin 外挂 · V1.1.2')
a=a.replace('后台：未进入 Scene · 当前句自动翻译有实时时延显示','后台：未进入 Scene · 自动 Pro 守护与手动翻译完全解耦')

if 'versionCode = 111' not in g or 'versionName = "1.1.1"' not in g:
    raise SystemExit('expected V1.1.1 Gradle version missing')
g=g.replace('versionCode = 111','versionCode = 112').replace('versionName = "1.1.1"','versionName = "1.1.2"')

for m in ['autoBlocked:new Set()','manualRev:new Map()','trMarkManualCommit','自动守护没有停止','自动 Pro 返回已丢弃','手动译文优先','Taimanin 外挂 · V1.1.2']:
    if m not in a: raise SystemExit('missing V1.1.2 marker '+m)
if "||MTR.autoFatal||" in a or "||MTR.autoFatal)return" in a:
    raise SystemExit('autoFatal is still a hard stop gate')
if "trPrepareBackground(MTR.scene)" in a:
    raise SystemExit('manual action still resets background scheduler')
if 'versionCode = 112' not in g or 'versionName = "1.1.2"' not in g:
    raise SystemExit('Gradle V1.1.2 update failed')

ASSET.write_text(a,'utf-8')
GRADLE.write_text(g,'utf-8')
print('Mobile V1.1.2 independent automatic Pro guardian patched')
