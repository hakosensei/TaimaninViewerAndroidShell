from pathlib import Path
import re

ASSET=Path('app/src/main/assets/touch_addon.js')
GRADLE=Path('app/build.gradle.kts')
a=ASSET.read_text('utf-8')
g=GRADLE.read_text('utf-8')

def repl(old,new,label,count=1):
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

# 1) Translation should be ON by default on mobile, while still remembering
#    an explicit user choice.  This is the biggest UX difference from the old
#    Android build where translation existed but silently started OFF.
anchor="/* ---------- Mobile translation v1.0.7: PC v12.3.3 hybrid sync ---------- */\n"
insert=anchor+"""const MTR_ENABLE_PREF='ta_mobile_translation_enabled_v1';
function trInitialEnabled(){try{const v=localStorage.getItem(MTR_ENABLE_PREF);return v===null?true:v!=='0';}catch(e){return true;}}
"""
repl(anchor,insert,'translation pref anchor')
repl(" enabled:false,scene:'',cache:new Map(),local:new Map(),cfg:null,cfgPromise:null,loading:'',wrapped:false,",
     " enabled:trInitialEnabled(),scene:'',cache:new Map(),local:new Map(),cfg:null,cfgPromise:null,loading:'',wrapped:false,",
     'translation default on')
repl(" rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null",
     " rollbackSource:'',rollbackStack:[],actionBusy:false,instantObserver:null,observedText:null,observedName:null,observerTimer:0",
     'observer fields')

# Normalize the little Viewer progress glyphs out of the source string.  PC learned
# that these can otherwise make a fully rendered line look like a new string forever.
repl(r"""function trClean(x){return String(x||'').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/\r\n/g,'\n').replace(/\r/g,'\n').replace(/<br\s*\/?>/gi,'\n').replace(/##/g,'\n').replace(/\n{3,}/g,'\n\n').trim();}""",
     r"""function trClean(x){let s=String(x||'').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/\r\n/g,'\n').replace(/\r/g,'\n').replace(/<br\s*\/?>/gi,'\n').replace(/##/g,'\n').replace(/\n{3,}/g,'\n\n').trim();return s.replace(/[ \t]*(?:[▶▷►▸▹▼▽▾▿◆◇●○◎★☆▌▍▋▊█]){1,3}[ \t]*$/u,'').trim();}""",
     'clean trailing viewer glyphs')

# 2) Use visible/relevant Viewer dialogue nodes, not an arbitrary stale node.
old="""function trTextEl(){try{if(window.scene?.elements?.textBoxText)return scene.elements.textBoxText;}catch(e){}return document.querySelector('.text-box-text,#text-box-content');}
function trNameEl(){try{if(window.scene?.elements?.namePlate)return scene.elements.namePlate;}catch(e){}return document.querySelector('.text-box-name,.name-plate,.nameplate,.speaker-name,#text-box-name,#name-plate');}
"""
new="""function trElementVisible(el){if(!el||!el.isConnected)return false;try{const cs=getComputedStyle(el);if(cs.display==='none'||cs.visibility==='hidden'||Number(cs.opacity||1)<0.03)return false;const r=el.getBoundingClientRect();return r.width>6&&r.height>3&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;}catch(e){return false;}}
function trPickVisible(cands){let best=null,bestArea=-1;for(const el of cands){if(!el||!el.isConnected)continue;const t=trClean(el.innerText||el.textContent||'');if(!t)continue;let area=0;try{const r=el.getBoundingClientRect();area=r.width*r.height;}catch(e){}if(trElementVisible(el)&&area>bestArea){best=el;bestArea=area;}}return best||cands.find(x=>x&&x.isConnected)||null;}
function trTextEl(){const a=[];try{if(window.scene?.elements?.textBoxText)a.push(scene.elements.textBoxText);}catch(e){}try{document.querySelectorAll('.text-box-text,#text-box-content').forEach(x=>{if(!a.includes(x))a.push(x);});}catch(e){}return trPickVisible(a);}
function trNameEl(){const a=[];try{if(window.scene?.elements?.namePlate)a.push(scene.elements.namePlate);}catch(e){}try{document.querySelectorAll('.text-box-name,.name-plate,.nameplate,.speaker-name,#text-box-name,#name-plate').forEach(x=>{if(!a.includes(x))a.push(x);});}catch(e){}return trPickVisible(a);}
"""
repl(old,new,'visible node selection')

# 3) Port the PC name-plate fix: exact first, then conservative equivalent-name
#    normalization.  No fuzzy matching, and ambiguous normalized keys are dropped.
name_helpers="""function trNameKey(x){let s=trClean(x);try{s=s.normalize('NFKC');}catch(e){}return s.replace(/[【】\\[\\]（）()「」『』]/g,'').replace(/[\\s・·･]/g,'').replace(/凜/g,'凛').trim();}
function trBuildGlossaryNameMap(){const m=new Map(),bad=new Set();for(const [src,zh] of MTR.glossary.entries()){const k=trNameKey(src);if(!k||!zh||src===zh)continue;if(m.has(k)&&m.get(k)!==zh)bad.add(k);else m.set(k,zh);}for(const k of bad)m.delete(k);MTR.glossaryName=m;}
function trGlossaryName(src){src=trClean(src);if(!src)return '';return MTR.glossary.get(src)||MTR.glossaryName?.get(trNameKey(src))||'';}
"""
repl("function trSource(el){if(!el)return '';",name_helpers+"function trSource(el){if(!el)return '';",'name helpers')
repl(" glossary:new Map(),glossaryReady:false,glossaryPromise:null,glossaryError:'',glossaryCount:0,",
     " glossary:new Map(),glossaryName:new Map(),glossaryReady:false,glossaryPromise:null,glossaryError:'',glossaryCount:0,",
     'glossary name map field')
repl("const ne=trNameEl();if(ne&&MTR.glossaryReady){const src=trSource(ne),zh=MTR.glossary.get(src);if(zh&&zh!==src)trReplace(ne,zh);}",
     "const ne=trNameEl();if(ne&&MTR.glossaryReady){const src=trSource(ne),zh=trGlossaryName(src);if(zh&&zh!==src)trReplace(ne,zh);}",
     'name apply')
repl("MTR.glossaryReady=true;MTR.glossaryCount=MTR.glossary.size;MTR.glossaryError='';trUpdateBgStatus('Pro术语库已同步 '+MTR.glossaryCount+' 条');if(MTR.enabled)trApplyCurrent();",
     "trBuildGlossaryNameMap();MTR.glossaryReady=true;MTR.glossaryCount=MTR.glossary.size;MTR.glossaryError='';trUpdateBgStatus('Pro术语库已同步 '+MTR.glossaryCount+' 条 · 姓名映射 '+MTR.glossaryName.size+' 条');if(MTR.enabled)trApplyCurrent();",
     'glossary ready name rebuild')

# 4) Remember toggle state, and keep the UI synchronized even when translation
#    starts enabled before the panel is opened.
old_toggle="""function trToggle(){
 MTR.enabled=!MTR.enabled;const b=document.getElementById('__ta_translate_toggle');if(b){b.textContent='翻译显示：'+(MTR.enabled?'开':'关');b.classList.toggle('green',MTR.enabled);}
 MTR.requestedText='';MTR.seenText='';MTR.autoFatal='';
"""
new_toggle="""function trSyncToggleUI(){const b=document.getElementById('__ta_translate_toggle');if(b){b.textContent='翻译显示：'+(MTR.enabled?'开':'关');b.classList.toggle('green',MTR.enabled);}}
function trToggle(){
 MTR.enabled=!MTR.enabled;try{localStorage.setItem(MTR_ENABLE_PREF,MTR.enabled?'1':'0');}catch(e){}trSyncToggleUI();
 MTR.requestedText='';MTR.seenText='';MTR.autoFatal='';
"""
repl(old_toggle,new_toggle,'toggle persistence')

# 5) Replace the broad document-wide MutationObserver with the same targeted
#    strategy that fixed PC's animation/page-freeze issue.  We only observe the
#    real text/name nodes, rebind if Viewer rebuilds them, and rAF-throttle.
old_observer="""function trInstallInstantTranslationObserver(){if(MTR.instantObserver)return;const mo=new MutationObserver(()=>{if(!MTR.enabled)return;trApplyCurrent();queueMicrotask(trVisibleTick);});mo.observe(document.documentElement,{subtree:true,childList:true,characterData:true});MTR.instantObserver=mo;}
function startTranslationMobile(){trWrapChooseScene();trInstallInstantTranslationObserver();trEnsureGlossary().catch(e=>{trStatus('Pro 术语库同步失败：'+String(e.message||e),true);});setInterval(()=>{if(!ready())return;trWrapChooseScene();if(main.view.current===1){if(MTR.scene)trLeaveScene();return;}const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);if(MTR.enabled){trApplyCurrent();trVisibleTick();trBackgroundTick();}},80);}"""
new_observer="""function trInstallInstantTranslationObserver(){
 if(MTR.instantObserver)return;
 let queued=false;
 const fire=()=>{if(!MTR.enabled||queued)return;queued=true;requestAnimationFrame(()=>{queued=false;trApplyCurrent();trVisibleTick();});};
 const mo=new MutationObserver(fire);MTR.instantObserver=mo;
 const bind=()=>{const te=trTextEl(),ne=trNameEl();if(te===MTR.observedText&&ne===MTR.observedName)return;try{mo.disconnect();}catch(e){}if(te)try{mo.observe(te,{subtree:true,childList:true,characterData:true});}catch(e){}if(ne&&ne!==te)try{mo.observe(ne,{subtree:true,childList:true,characterData:true});}catch(e){}MTR.observedText=te;MTR.observedName=ne;if(MTR.enabled)fire();};
 bind();MTR.observerTimer=setInterval(bind,250);
}
function startTranslationMobile(){trWrapChooseScene();trInstallInstantTranslationObserver();trSyncToggleUI();trEnsureGlossary().catch(e=>{trStatus('Pro 术语库同步失败：'+String(e.message||e),true);});setInterval(()=>{if(!ready())return;trWrapChooseScene();if(main.view.current===1){if(MTR.scene)trLeaveScene();return;}const id=trInferScene();if(id&&id!==MTR.scene)trLoadScene(id);if(MTR.enabled){trApplyCurrent();trVisibleTick();trBackgroundTick();}},120);}"""
repl(old_observer,new_observer,'targeted translation observer')

# 6) Make the visible current line the obvious priority in status and keep the
#    90ms stable-text gate from V1.0.  Cached lines still replace immediately.
a=a.replace('Taimanin 外挂 · V1.0','Taimanin 外挂 · V1.1')
a=a.replace('后台：未进入 Scene · 术语库进入 Viewer 时一次性载入 RAM','后台：未进入 Scene · 启动预载术语库 · 当前句优先即时翻译')
a=a.replace('⚠ 在线翻译会调用云端模型并消耗 Token · Pro 主翻译 · Hy4 润色 · Plus 机翻','⚠ 在线翻译会消耗 Token · 默认开启 · 当前句优先 · Pro 主翻译 / Hy4 润色 / Plus 机翻')

# Panel was created before startTranslationMobile; make sure the initial button
# state is correct immediately, not only after the first polling tick.
repl("document.getElementById('__ta_translate_toggle').onclick=trToggle;document.getElementById('__ta_translate_rollback').onclick=trRollbackCurrent;",
     "document.getElementById('__ta_translate_toggle').onclick=trToggle;trSyncToggleUI();document.getElementById('__ta_translate_rollback').onclick=trRollbackCurrent;",
     'panel translation ui init')

# Upgrade build version without changing package/signing lineage.
if 'versionCode = 108' not in g or 'versionName = "1.0.8"' not in g:
    raise SystemExit('expected post-v108 Gradle version missing')
g=g.replace('versionCode = 108','versionCode = 110').replace('versionName = "1.0.8"','versionName = "1.1.0"')

# Safety checks: no broad observer regression and all new markers exist.
for m in ['trInitialEnabled','trGlossaryName','trBuildGlossaryNameMap','trPickVisible','trSyncToggleUI','observerTimer=setInterval(bind,250)','Taimanin 外挂 · V1.1']:
    if m not in a: raise SystemExit('missing v1.1 marker '+m)
if "mo.observe(document.documentElement" in a:
    raise SystemExit('broad document translation observer still present')
if 'versionCode = 110' not in g or 'versionName = "1.1.0"' not in g:
    raise SystemExit('Gradle version update failed')

ASSET.write_text(a,'utf-8')
GRADLE.write_text(g,'utf-8')
print('Mobile V1.1 PC-translation sync patched')
