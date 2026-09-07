from pathlib import Path
import base64, gzip

SRC = Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
s = SRC.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'{label}: marker missing')
    s = s.replace(old, new, count)

# Visible labels only. Keep the clean stable Activity as the base.
s = s.replace('v0.8.1 · HyperOS 稳定回归版', 'v0.9.6 Recovery · HyperOS 稳定恢复版')
s = s.replace('APP: v0.8.1', 'APP: v0.9.6 Recovery')
s = s.replace('MODE: v0.7 stable activity + post-load mobile patch',
              'MODE: clean stable activity + post-launch landscape + immersive bars + Touch Addon')

rep('import android.content.Context\n',
    'import android.content.Context\nimport android.content.pm.ActivityInfo\nimport android.content.res.Configuration\n',
    'imports')

rep('    private var pageStatus = "未启动"\n', '''    private var pageStatus = "未启动"
    private var pendingLandscapeRoot: File? = null
    private var landscapeStartScheduled = false
    private var touchAddonJsCache: String? = null
''', 'fields')

# Keep launcher creation. Immersive mode is applied only after the launcher exists.
rep('''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLauncher()
    }

    override fun onResume() {
        super.onResume()
        if (!viewerActive) showLauncher()
    }''', '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLauncher()
        mainHandler.post { enterImmersive() }
    }

    override fun onResume() {
        super.onResume()
        if (!viewerActive && pendingLandscapeRoot == null && !landscapeStartScheduled) showLauncher()
        mainHandler.post { enterImmersive() }
    }''', 'lifecycle')

# Explicit Run Viewer button is the ONLY landscape trigger.
rep('                    setOnClickListener { startViewer(detected) }',
    '                    setOnClickListener { requestLandscapeThenStart(detected) }',
    'run button')

# Selecting a folder never starts the Viewer automatically.
rep('''        val f = fileFromTreeUri(uri)
        if (f != null && File(f, "index.html").isFile) startViewer(f)
        else showLauncher("已记住目录位置，但无法映射到真实共享存储路径，或目录中没有 index.html。")''', '''        val f = fileFromTreeUri(uri)
        if (f != null && File(f, "index.html").isFile) {
            showLauncher("已选择 Viewer 文件夹。请点击【运行 Viewer】进入；点击后才会切换横屏。")
        } else {
            showLauncher("已记住目录位置，但无法映射到真实共享存储路径，或目录中没有 index.html。")
        }''', 'folder result')

helper = r'''    private fun requestLandscapeThenStart(root: File) {
        pendingLandscapeRoot = root
        landscapeStartScheduled = false
        enterImmersive()

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            schedulePendingViewerStart(140)
            return
        }

        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } catch (e: Throwable) {
            pendingLandscapeRoot = null
            showLauncher("横屏请求失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            return
        }
        waitForLandscape(0)
    }

    private fun waitForLandscape(attempt: Int) {
        if (pendingLandscapeRoot == null || landscapeStartScheduled) return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            schedulePendingViewerStart(220)
            return
        }
        if (attempt >= 35) {
            val root = pendingLandscapeRoot
            pendingLandscapeRoot = null
            if (root != null) {
                Toast.makeText(this, "系统横屏切换超时，将按当前方向启动 Viewer。", Toast.LENGTH_LONG).show()
                startViewer(root)
            }
            return
        }
        mainHandler.postDelayed({ waitForLandscape(attempt + 1) }, 100)
    }

    private fun schedulePendingViewerStart(delayMs: Long) {
        if (landscapeStartScheduled) return
        val root = pendingLandscapeRoot ?: return
        landscapeStartScheduled = true
        pendingLandscapeRoot = null
        mainHandler.postDelayed({
            landscapeStartScheduled = false
            enterImmersive()
            startViewer(root)
        }, delayMs)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersive()
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && pendingLandscapeRoot != null) {
            schedulePendingViewerStart(220)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let { c ->
                    c.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } catch (_: Throwable) {}
    }

'''
rep('    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()\n\n',
    '    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()\n\n' + helper,
    'landscape helper')

# Re-hide system bars after the WebView replaces the launcher.
rep('            setContentView(frame)\n            viewerActive = true',
    '            setContentView(frame)\n            enterImmersive()\n            viewerActive = true',
    'viewer frame')

# Install Touch Addon only after the stable post-load fit patch.
rep('                view?.let { applyMobilePatch(it) }',
    '                view?.let { applyMobilePatch(it); mainHandler.postDelayed({ applyTouchAddon(it) }, 180) }',
    'page finished')

rep('''                            p.firstOrNull() == "PATCHME" -> { applyMobilePatch(w); status.visibility=View.VISIBLE; status.text="Viewer 已启动，正在应用手机适配…" }''',
    '''                            p.firstOrNull() == "PATCHME" -> { applyMobilePatch(w); mainHandler.postDelayed({ applyTouchAddon(w) }, 180); status.visibility=View.VISIBLE; status.text="Viewer 已启动，正在应用手机适配…" }''',
    'poll patch')

addon_fn = '''    private fun applyTouchAddon(w: WebView) {
        val js = try {
            touchAddonJsCache ?: assets.open("touch_addon.js").bufferedReader(Charsets.UTF_8).use { it.readText() }.also { touchAddonJsCache = it }
        } catch (e: Throwable) {
            addConsole("TOUCH ASSET EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        try {
            w.evaluateJavascript(js) { r -> addConsole("TOUCH $r") }
        } catch (e: Throwable) {
            addConsole("TOUCH EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        }
    }

'''
rep('    private fun startPoll(w: WebView, status: TextView) {\n',
    addon_fn + '    private fun startPoll(w: WebView, status: TextView) {\n',
    'touch addon function')

# Keep original cleanup and add only our pending-start state cleanup.
rep('    override fun onDestroy(){stopViewer();super.onDestroy()}',
    '    override fun onDestroy(){ pendingLandscapeRoot=null; landscapeStartScheduled=false; stopViewer(); super.onDestroy() }',
    'destroy')

# Preserve v0.8.3 Android IME page-number submit compatibility.
marker = '''    setTimeout(function(){scan();fit();},100);setTimeout(function(){scan();fit();},500);setTimeout(function(){scan();fit();},1500);
    return 'installed';'''
injection = r'''    setTimeout(function(){scan();fit();},100);setTimeout(function(){scan();fit();},500);setTimeout(function(){scan();fit();},1500);

    function androidCommitPageInput(inp){
      try{
        if(!inp||inp.id!=='page-number')return false;
        var raw=String(inp.value||'').trim();
        if(!/^\d+$/.test(raw))return false;
        var target=parseInt(raw,10);
        if(typeof main==='undefined'||typeof sceneSelect==='undefined'||typeof prefs==='undefined'||typeof constructSceneSelect!=='function'||!main.sceneList)return false;
        var pp=prefs.select.rows*prefs.select.columns;
        var max=Math.floor((main.sceneList.length-1)/pp)+1;
        if(!isFinite(target)||target<1||target>max)return false;
        sceneSelect.page=target-1;constructSceneSelect();try{inp.blur();}catch(_e){}return true;
      }catch(e){console.error('[ANDROID PAGE JUMP] '+e);return false;}
    }
    var pn=document.getElementById('page-number');
    if(pn){
      pn.setAttribute('inputmode','numeric');pn.setAttribute('enterkeyhint','go');pn.setAttribute('autocomplete','off');
      window.__androidCommitPageInput=androidCommitPageInput;
      var lastPageCommit=0,suppressNextClickUntil=0;
      function androidIsEnter(e){return !!e&&(e.key==='Enter'||e.code==='Enter'||e.code==='NumpadEnter'||e.keyCode===13||e.which===13);}
      document.addEventListener('keydown',function(e){if(e.target===pn&&androidIsEnter(e)&&androidCommitPageInput(pn)){lastPageCommit=Date.now();e.preventDefault();e.stopImmediatePropagation();}},true);
      document.addEventListener('keyup',function(e){if(e.target===pn&&androidIsEnter(e)&&(Date.now()-lastPageCommit<500||androidCommitPageInput(pn))){lastPageCommit=Date.now();e.preventDefault();e.stopImmediatePropagation();}},true);
      document.addEventListener('beforeinput',function(e){if(e.target===pn&&(e.inputType==='insertLineBreak'||e.inputType==='insertParagraph')&&androidCommitPageInput(pn)){lastPageCommit=Date.now();e.preventDefault();e.stopImmediatePropagation();}},true);
      document.addEventListener('focusout',function(e){if(e.target===pn)androidCommitPageInput(pn);},true);
      document.addEventListener('pointerdown',function(e){var next=e.target&&e.target.closest?e.target.closest('#next-page'):null;if(next&&document.activeElement===pn&&/^\d+$/.test(String(pn.value||'').trim())&&androidCommitPageInput(pn)){suppressNextClickUntil=Date.now()+800;e.preventDefault();e.stopImmediatePropagation();}},true);
      document.addEventListener('click',function(e){var next=e.target&&e.target.closest?e.target.closest('#next-page'):null;if(next&&Date.now()<suppressNextClickUntil){suppressNextClickUntil=0;e.preventDefault();e.stopImmediatePropagation();}},true);
    }

    return 'installed';'''
if marker not in s:
    raise SystemExit('page jump marker missing')
s = s.replace(marker, injection, 1)

SRC.write_text(s, encoding='utf-8')
print('Patched clean V81 source for v0.9.6 Recovery')

parts = [Path(f'touch_parts/touch{i}.txt').read_text(encoding='ascii').strip() for i in range(1,5)]
ASSET = Path('app/src/main/assets/touch_addon.js')
ASSET.parent.mkdir(parents=True, exist_ok=True)
ASSET.write_bytes(gzip.decompress(base64.b64decode(''.join(parts))))
print('Wrote Touch Addon v0.9.6 asset')
