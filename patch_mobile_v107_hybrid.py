from pathlib import Path
import base64, gzip

SRC = Path('app/src/main/java/com/example/taimaninviewer/DirectFileViewerActivityV81.kt')
GRADLE = Path('app/build.gradle.kts')
ASSET = Path('app/src/main/assets/touch_addon.js')

s = SRC.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'{label}: marker missing')
    s = s.replace(old, new, count)

# v1.0.7 Hybrid is applied AFTER patch_v083.py + v103 + v104.
# The WebView never talks to Tencent cross-origin itself: model requests are
# intercepted by v104 and proxied through native HttpURLConnection.  This patch
# adds the Tencent Cloud control-plane glossary reader needed by Hy-MT2-Pro.

# Exact feature-complete mobile addon, derived from the user's uploaded v1.0.2.
addon_b64 = ''.join(p.read_text(encoding='ascii').strip() for p in sorted(Path('touch107_parts').glob('part_*.txt')))
ASSET.parent.mkdir(parents=True, exist_ok=True)
ASSET.write_bytes(gzip.decompress(base64.b64decode(addon_b64)))

# Visible labels / native User-Agent.
s = s.replace('v1.0.4 Mobile · 原生翻译代理 / Translation落盘版',
              'v1.0.7 Hybrid · Pro主翻译 / Hy4润色 / Plus机器翻译')
s = s.replace('APP: v1.0.4 Mobile', 'APP: v1.0.7 Hybrid')
s = s.replace('MODE: Mobile v1.0.4 + native TokenHub proxy + durable Translation cache + strict input gate',
              'MODE: Mobile v1.0.7 Hybrid + native Tencent proxy + cloud glossary RAM sync + durable Translation cache')
s = s.replace('TaimaninRPGXViewer-Mobile/1.0.3', 'TaimaninRPGXViewer-Mobile/1.0.7')

# TC3-HMAC-SHA256 imports.
rep('import java.util.TimeZone\n',
    'import java.util.TimeZone\nimport javax.crypto.Mac\nimport javax.crypto.spec.SecretKeySpec\n',
    'TC3 imports')

# Separate executor: glossary sync can take ~10 paged control-plane requests and
# must never block ordinary model calls / durable cache work.
executor_anchor = '''    private val translationExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taimanin-translation-http").apply { isDaemon = true }
    }
'''
executor_new = executor_anchor + '''    private val glossaryExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taimanin-glossary-sync").apply { isDaemon = true }
    }
'''
rep(executor_anchor, executor_new, 'glossary executor')

bridge_anchor = '''        @JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean): Boolean {'''
bridge_extra = r'''        private fun hmacSha256(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        }

        private fun hexLower(data: ByteArray): String =
            data.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun loadFullTranslationConfig(): JSONObject {
            val root = viewerRoot ?: throw IllegalStateException("Viewer 根目录尚未就绪")
            val file = File(root, "translation_config.json")
            if (!file.isFile) throw IllegalStateException("缺少 translation_config.json")
            return JSONObject(file.readText(Charsets.UTF_8))
        }

        private fun tc3Post(
            host: String,
            region: String,
            secretId: String,
            secretKey: String,
            action: String,
            payload: String,
            timeoutMs: Int
        ): JSONObject {
            if (host.lowercase(Locale.US) != "tokenhub.tencentcloudapi.com") {
                throw SecurityException("术语库管控面仅允许 tokenhub.tencentcloudapi.com")
            }
            val service = "tokenhub"
            val version = "2026-03-22"
            val timestamp = System.currentTimeMillis() / 1000L
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            dateFmt.timeZone = TimeZone.getTimeZone("UTC")
            val date = dateFmt.format(Date(timestamp * 1000L))

            val contentType = "application/json; charset=utf-8"
            val canonicalHeaders =
                "content-type:$contentType\n" +
                "host:$host\n" +
                "x-tc-action:${action.lowercase(Locale.US)}\n"
            val signedHeaders = "content-type;host;x-tc-action"
            val hashedPayload = sha256Text(payload)
            val canonicalRequest =
                "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedPayload
            val credentialScope = "$date/$service/tc3_request"
            val stringToSign =
                "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n${sha256Text(canonicalRequest)}"

            val secretDate = hmacSha256(("TC3" + secretKey).toByteArray(StandardCharsets.UTF_8), date)
            val secretService = hmacSha256(secretDate, service)
            val secretSigning = hmacSha256(secretService, "tc3_request")
            val signature = hexLower(hmacSha256(secretSigning, stringToSign))
            val authorization =
                "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

            val t = timeoutMs.coerceIn(10_000, 180_000)
            val c = (URL("https://$host/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = t
                readTimeout = t
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Authorization", authorization)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Host", host)
                setRequestProperty("X-TC-Action", action)
                setRequestProperty("X-TC-Timestamp", timestamp.toString())
                setRequestProperty("X-TC-Version", version)
                setRequestProperty("X-TC-Region", region)
                setRequestProperty("User-Agent", "TaimaninRPGXViewer-Mobile/1.0.7")
            }
            try {
                c.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                val status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                if (status !in 200..299) {
                    throw IllegalStateException("腾讯云术语库 HTTP $status: ${raw.take(1200)}")
                }
                val obj = try { JSONObject(raw) }
                catch (_: Throwable) { throw IllegalStateException("腾讯云术语库返回了无法解析的 JSON") }
                val response = obj.optJSONObject("Response")
                    ?: throw IllegalStateException("腾讯云术语库响应缺少 Response")
                val err = response.optJSONObject("Error")
                if (err != null) {
                    throw IllegalStateException(
                        "腾讯云术语库 ${err.optString("Code")}: ${err.optString("Message")}"
                    )
                }
                return response
            } finally {
                c.disconnect()
            }
        }

        private fun fetchGlossaryFromCloud(timeoutMs: Int): String {
            val cfg = loadFullTranslationConfig()
            val gl = cfg.optJSONObject("glossary")
                ?: throw IllegalStateException("translation_config.json 缺少 glossary")
            if (!gl.optBoolean("enabled", true)) {
                throw IllegalStateException("translation_config.json 中 glossary.enabled=false")
            }
            val glossaryId = gl.optString("id", "").trim().ifBlank {
                val ids = cfg.optJSONArray("glossary_ids")
                if (ids != null && ids.length() > 0) ids.optString(0, "").trim() else ""
            }
            if (glossaryId.isBlank() || glossaryId.contains("PASTE_YOUR")) {
                throw IllegalStateException("GlossaryId 未配置")
            }
            val cp = gl.optJSONObject("control_plane")
                ?: throw IllegalStateException("glossary.control_plane 未配置")
            val host = cp.optString("host", "tokenhub.tencentcloudapi.com").trim()
                .ifBlank { "tokenhub.tencentcloudapi.com" }
            val region = cp.optString("region", "ap-guangzhou").trim().ifBlank { "ap-guangzhou" }
            val secretId = cp.optString("secret_id", "").trim()
            val secretKey = cp.optString("secret_key", "").trim()
            if (secretId.isBlank() || secretKey.isBlank() ||
                secretId.contains("PASTE_YOUR") || secretKey.contains("PASTE_YOUR") ||
                secretId == "__NATIVE_BRIDGE__" || secretKey == "__NATIVE_BRIDGE__") {
                throw IllegalStateException("腾讯云 SecretId / SecretKey 未配置")
            }

            val entries = JSONObject()
            var total = -1
            var page = 1
            while (page <= 100) {
                val payload = JSONObject()
                    .put("GlossaryId", glossaryId)
                    .put("Page", page)
                    .put("PageSize", 200)
                    .toString()
                val response = tc3Post(
                    host, region, secretId, secretKey,
                    "DescribeGlossaryEntries", payload, timeoutMs
                )
                val arr = response.optJSONArray("Entries") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val src = item.optString("SourceTerm", "").trim()
                    val dst = item.optString("TargetTerm", "").trim()
                    if (src.isNotBlank() && dst.isNotBlank()) entries.put(src, dst)
                }
                if (total < 0) total = response.optInt("Total", entries.length())
                if (arr.length() == 0 || entries.length() >= total) break
                page++
            }
            if (entries.length() == 0) {
                throw IllegalStateException("腾讯云术语库为空或未取得任何词条")
            }
            return JSONObject()
                .put("ok", true)
                .put("glossary_id", glossaryId)
                .put("count", entries.length())
                .put("total", if (total < 0) entries.length() else total)
                .put("entries", entries)
                .toString()
        }

        @JavascriptInterface
        fun syncGlossaryAsync(requestId: String, timeoutMs: Int) {
            val id = requestId.take(160)
            glossaryExecutor.execute {
                val result = try {
                    fetchGlossaryFromCloud(timeoutMs)
                } catch (e: Throwable) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", "${e.javaClass.simpleName}: ${e.message ?: "术语库同步失败"}")
                        .toString()
                }
                mainHandler.post {
                    val ww = web ?: return@post
                    val js = "window.__taNativeGlossaryResolve&&window.__taNativeGlossaryResolve(${JSONObject.quote(id)},${JSONObject.quote(result)});"
                    try { ww.evaluateJavascript(js, null) } catch (_: Throwable) {}
                }
            }
        }

'''
rep(bridge_anchor, bridge_extra + bridge_anchor, 'native glossary bridge')

# Browser receives model names / GlossaryId, but never receives real API or
# Tencent Cloud control-plane credentials. Native code reads the real file.
sanitize_old = '''                        obj.optJSONObject("api")?.let{if(it.has("key"))it.put("key","__NATIVE_BRIDGE__")}
                        if(obj.has("key"))obj.put("key","__NATIVE_BRIDGE__")'''
sanitize_new = sanitize_old + '''
                        obj.optJSONObject("glossary")?.optJSONObject("control_plane")?.let{
                            if(it.has("secret_id"))it.put("secret_id","__NATIVE_BRIDGE__")
                            if(it.has("secret_key"))it.put("secret_key","__NATIVE_BRIDGE__")
                        }'''
rep(sanitize_old, sanitize_new, 'mask control-plane credentials')

# Shutdown the additional executor cleanly.
destroy_old = 'override fun onDestroy(){ pendingLandscapeRoot=null; landscapeStartScheduled=false; stopViewer(); try{translationExecutor.shutdownNow()}catch(_:Throwable){}; super.onDestroy() }'
destroy_new = 'override fun onDestroy(){ pendingLandscapeRoot=null; landscapeStartScheduled=false; stopViewer(); try{translationExecutor.shutdownNow()}catch(_:Throwable){}; try{glossaryExecutor.shutdownNow()}catch(_:Throwable){}; super.onDestroy() }'
rep(destroy_old, destroy_new, 'destroy glossary executor')

SRC.write_text(s, encoding='utf-8')

# Install side-by-side with the user's original v1.0.2. We do not possess the
# private key that signed the uploaded APK, so reusing its applicationId would
# make Android reject this build as an update. A new applicationId + stable repo
# key guarantees an installable package and gives future v1.0.7+ builds a stable
# signing lineage.
g = GRADLE.read_text(encoding='utf-8')
g = g.replace('applicationId = "com.example.taimaninviewer.mobilev1000"',
              'applicationId = "com.example.taimaninviewer.mobilev107hybrid"')
g = g.replace('versionCode = 104', 'versionCode = 107')
g = g.replace('versionName = "1.0.4"', 'versionName = "1.0.7"')
GRADLE.write_text(g, encoding='utf-8')

# Build-time invariants: fail CI instead of shipping a half-migrated APK.
asset_text = ASSET.read_text(encoding='utf-8')
required = [
    'hy-mt2-pro', 'hy-mt2-plus', 'hy4-preview', 'syncGlossaryAsync',
    '回退上一次译文', '机器翻译（Plus）', '复制粘贴', '__taNativeGlossaryResolve'
]
for marker in required:
    if marker not in asset_text:
        raise SystemExit('missing mobile hybrid marker: ' + marker)
for forbidden in ['PRO_LOCAL_GLOSSARY', 'PRO_LOCAL_GLOSSARY_MAP']:
    if forbidden in asset_text:
        raise SystemExit('embedded glossary residue found: ' + forbidden)
if 'syncGlossaryAsync' not in s or 'DescribeGlossaryEntries' not in s:
    raise SystemExit('native glossary bridge missing after patch')
if 'mobilev107hybrid' not in g or 'versionCode = 107' not in g:
    raise SystemExit('Android package/version patch failed')
print('Patched Mobile v1.0.4 -> v1.0.7 Hybrid (Pro + Hy4 + Plus + cloud glossary RAM sync + rollback/manual)')
