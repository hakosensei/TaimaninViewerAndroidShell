package com.example.xunleivrplayer;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量迅雷云盘协议客户端（个人实验用途）。
 *
 * 登录流程同步到当前 AList Thunder 驱动：
 * 1) /xluser.core.login/v3/login 用账号密码换 sessionID；
 * 2) /v1/shield/captcha/init 初始化本次 signin/token 的验证码状态；
 * 3) /v1/auth/signin/token 用 sessionID 换 access_token / refresh_token；
 * 4) 后续优先用 refresh_token 恢复登录态。
 *
 * 这些接口不是迅雷面向第三方开发者承诺长期稳定的公开 SDK，未来迅雷升级时可能需要再次同步。
 */
class XunleiApi {
    static final String API_BASE = "https://api-pan.xunlei.com/drive/v1";
    static final String FILE_API = API_BASE + "/files";
    static final String USER_API_BASE = "https://xluser-ssl.xunlei.com";
    static final String USER_API = USER_API_BASE + "/v1";

    // 当前 AList 标准 Thunder 驱动使用的迅雷 Android 主程序身份。
    static final String CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    static final String CLIENT_SECRET = "Xp6vsy4tN9toTVdMSpomVdXpRmES";
    static final String CLIENT_VERSION = "8.31.0.9726";
    static final String XL_PACKAGE = "com.xunlei.downloadprovider";
    static final String SDK_VERSION = "512000";
    static final String APP_ID = "40";
    static final String APP_KEY = "34a062aaa22f906fca4fefe9fb3a3021";
    static final String DOWNLOAD_UA = "Dalvik/2.1.0 (Linux; U; Android 12; M2004J7AC Build/SP1A.210812.016)";
    static final String CORE_UA = "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000";
    static final String SIGN_PROVIDER = "access_end_point_token";

    // 当前主程序驱动根目录使用空 space，而不是旧 Browser 驱动的 SPACE_BROWSER。
    static final String ROOT_SPACE = "";

    static class VerificationRequiredException extends Exception {
        final String verifyUrl;
        VerificationRequiredException(String verifyUrl) {
            super("迅雷要求额外验证");
            this.verifyUrl = verifyUrl;
        }
    }

    private Models.Token token;
    private String captchaToken = "";
    private String deviceId = "";
    private String userAgent = "";
    private String pendingSessionId = "";

    void configureIdentity(String id) {
        this.deviceId = normalizeDeviceId(id);
        this.userAgent = buildUserAgent(this.deviceId);
    }

    String getDeviceId() { return deviceId; }
    Models.Token getToken() { return token; }

    Models.Token login(String username, String password) throws Exception {
        // 设备 ID 不再依赖密码，避免改密码后被服务端识别成另一台设备。
        configureIdentity(md5("xunlei-vr-player|" + username));

        // 1. 当前流程先通过 v3 core login 获取 sessionID。
        pendingSessionId = coreLogin(username, password);
        if (pendingSessionId.isBlank()) throw new Exception("迅雷 v3 登录没有返回 sessionID");

        // 2. 为 signin/token 初始化 captcha 状态。
        initCaptchaForSignin(username);

        // 3. 用 sessionID 换正式 OAuth token。
        return exchangeSessionForToken(pendingSessionId);
    }

    /**
     * 浏览器验证通过后，xlaccsdk01:// 回调会把 captcha_token 带回 APP。
     * 保留前一步的 sessionID，直接继续 signin/token；若进程被 Android 回收，则重新执行 core login。
     */
    Models.Token completeLogin(String username, String password, String callbackCaptchaToken) throws Exception {
        if (deviceId == null || deviceId.isBlank()) configureIdentity(md5("xunlei-vr-player|" + username));
        this.captchaToken = callbackCaptchaToken == null ? "" : callbackCaptchaToken;
        if (pendingSessionId == null || pendingSessionId.isBlank()) {
            pendingSessionId = coreLogin(username, password);
        }
        return exchangeSessionForToken(pendingSessionId);
    }

    Models.Token loginWithRefreshToken(String refreshToken, String persistedDeviceId) throws Exception {
        configureIdentity((persistedDeviceId == null || persistedDeviceId.isBlank()) ? md5("xunlei-vr-player|" + refreshToken) : persistedDeviceId);
        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", CLIENT_ID);
        body.put("client_secret", CLIENT_SECRET);
        JSONObject o = requestJson("POST", USER_API + "/auth/token", body, false, null, null);
        Models.Token t = Models.Token.fromJson(o);
        if (t.accessToken.isBlank()) throw new Exception("刷新登录失败：迅雷没有返回 access_token");
        if (t.refreshToken.isBlank()) t.refreshToken = refreshToken;
        token = t;
        return t;
    }

    private String coreLogin(String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("protocolVersion", "301");
        body.put("sequenceNo", "1000012");
        body.put("platformVersion", "10");
        body.put("isCompressed", "0");
        body.put("appid", APP_ID);
        body.put("clientVersion", CLIENT_VERSION);
        body.put("peerID", "00000000000000000000000000000000");
        body.put("appName", "ANDROID-" + XL_PACKAGE);
        body.put("sdkVersion", SDK_VERSION);
        body.put("devicesign", generateDeviceSign(deviceId, XL_PACKAGE));
        body.put("netWorkType", "WIFI");
        body.put("providerName", "NONE");
        body.put("deviceModel", "M2004J7AC");
        body.put("deviceName", "Xiaomi_M2004j7ac");
        body.put("OSVersion", "12");
        body.put("creditkey", "");
        body.put("hl", "zh-CN");
        body.put("userName", username);
        body.put("passWord", password);
        body.put("verifyKey", "");
        body.put("verifyCode", "");
        body.put("isMd5Pwd", "0");

        JSONObject o = requestJsonRaw("POST", USER_API_BASE + "/xluser.core.login/v3/login", body, CORE_UA, null);

        String error = o.optString("error", "");
        String errorCode = o.optString("errorCode", "");
        String errorDesc = firstNonBlank(o.optString("error_description", ""), o.optString("errorDesc", ""));
        String reviewUrl = o.optString("reviewurl", "");

        // 某些账号/设备会触发短信或网页安全审核。
        if ("review_panel".equalsIgnoreCase(error) || !reviewUrl.isBlank()) {
            if (!reviewUrl.isBlank()) throw new VerificationRequiredException(reviewUrl);
            throw new Exception("迅雷要求安全验证，请稍后重试登录");
        }
        if ((!error.isBlank() && !"success".equalsIgnoreCase(error)) || (!errorCode.isBlank() && !"0".equals(errorCode))) {
            throw new Exception("迅雷登录失败" + (!errorCode.isBlank() ? " [" + errorCode + "]" : "") + ": " + firstNonBlank(errorDesc, error));
        }

        String sessionId = o.optString("sessionID", "");
        if (sessionId.isBlank()) {
            throw new Exception("迅雷 v3 登录未返回 sessionID：" + compactError(o, o.toString()));
        }
        return sessionId;
    }

    private void initCaptchaForSignin(String username) throws Exception {
        JSONObject meta = new JSONObject();
        if (Pattern.matches("\\w+([-+.\\w])*@\\w+([-.\\w])*\\.\\w+([-.\\w])*", username)) {
            meta.put("email", username);
        } else if (username != null && username.length() >= 11 && username.length() <= 18) {
            meta.put("phone_number", username);
        } else {
            meta.put("username", username);
        }

        JSONObject body = new JSONObject();
        body.put("action", "POST:/v1/auth/signin/token");
        body.put("captcha_token", captchaToken == null ? "" : captchaToken);
        body.put("client_id", CLIENT_ID);
        body.put("device_id", deviceId);
        body.put("meta", meta);
        body.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor");

        JSONObject resp = requestJson("POST", USER_API + "/shield/captcha/init", body, false, null, null);
        String verifyUrl = resp.optString("url", "");
        if (!verifyUrl.isBlank()) throw new VerificationRequiredException(verifyUrl);
        captchaToken = resp.optString("captcha_token", "");
        if (captchaToken.isBlank()) throw new Exception("迅雷没有返回 captcha_token");
    }

    private Models.Token exchangeSessionForToken(String sessionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("client_secret", CLIENT_SECRET);
        body.put("provider", SIGN_PROVIDER);
        body.put("signin_token", sessionId);

        JSONObject o = requestJson("POST", USER_API + "/auth/signin/token", body, false, null, null);
        Models.Token t = Models.Token.fromJson(o);
        if (t.accessToken.isBlank()) throw new Exception("登录失败：迅雷没有返回 access_token");
        token = t;
        pendingSessionId = "";
        return t;
    }

    List<Models.CloudItem> list(String parentId, String space) throws Exception {
        ensureLoggedIn();
        List<Models.CloudItem> out = new ArrayList<>();
        String page = "";
        do {
            Uri.Builder b = Uri.parse(FILE_API).buildUpon();
            b.appendQueryParameter("space", "");
            b.appendQueryParameter("__type", "drive");
            b.appendQueryParameter("refresh", "true");
            b.appendQueryParameter("__sync", "true");
            b.appendQueryParameter("parent_id", parentId == null ? "" : parentId);
            b.appendQueryParameter("page_token", page);
            b.appendQueryParameter("with_audit", "true");
            b.appendQueryParameter("limit", "100");
            b.appendQueryParameter("filters", "{\"phase\":{\"eq\":\"PHASE_TYPE_COMPLETE\"},\"trashed\":{\"eq\":false}}");

            JSONObject o = requestJson("GET", b.build().toString(), null, true, null, null);
            JSONArray arr = o.optJSONArray("files");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jo = arr.optJSONObject(i);
                    if (jo == null) continue;
                    Models.CloudItem f = Models.CloudItem.fromJson(jo);
                    if (!f.id.isBlank()) out.add(f);
                }
            }
            page = o.optString("next_page_token", "");
        } while (!page.isBlank());
        return out;
    }

    Models.StreamLink getStreamLink(Models.CloudItem file) throws Exception {
        ensureLoggedIn();
        Uri.Builder b = Uri.parse(FILE_API + "/" + Uri.encode(file.id)).buildUpon();
        b.appendQueryParameter("thumbnail_size", "SIZE_LARGE");
        b.appendQueryParameter("with", "url");
        JSONObject o = requestJson("GET", b.build().toString(), null, true, null, null);
        Models.CloudItem detail = Models.CloudItem.fromJson(o);

        // 原始 web_content_link 优先，VR 片尽量避免云端转码降低分辨率；没有原始链接再退回 media URL。
        String url = detail.webContentLink;
        if (url == null || url.isBlank()) {
            for (String u : detail.mediaUrls) {
                if (u != null && !u.isBlank()) { url = u; break; }
            }
        }
        if (url == null || url.isBlank()) throw new Exception("迅雷没有返回可播放直链");
        return new Models.StreamLink(url, DOWNLOAD_UA);
    }

    private void ensureLoggedIn() throws Exception {
        if (token == null || token.accessToken == null || token.accessToken.isBlank()) throw new Exception("尚未登录迅雷");
    }

    private JSONObject requestJson(String method, String urlStr, JSONObject body, boolean auth,
                                   Map<String,String> extraHeaders, String overrideUserAgent) throws Exception {
        JSONObject o = requestJsonRaw(method, urlStr, body, overrideUserAgent, buildHeaders(auth, extraHeaders));
        long errorCode = o.optLong("error_code", 0L);
        String error = o.optString("error", "");
        String desc = o.optString("error_description", "");
        if (errorCode != 0 || (!error.isBlank() && !"success".equalsIgnoreCase(error)) || !desc.isBlank()) {
            String url = o.optString("url", "");
            if (!url.isBlank()) throw new VerificationRequiredException(url);
            throw new Exception("迅雷接口错误 " + errorCode + ": " + firstNonBlank(desc, error));
        }
        return o;
    }

    private Map<String,String> buildHeaders(boolean auth, Map<String,String> extraHeaders) {
        java.util.LinkedHashMap<String,String> h = new java.util.LinkedHashMap<>();
        if (auth && token != null) {
            h.put("Authorization", token.authorization());
            if (captchaToken != null && !captchaToken.isBlank()) h.put("X-Captcha-Token", captchaToken);
        }
        if (extraHeaders != null) h.putAll(extraHeaders);
        return h;
    }

    private JSONObject requestJsonRaw(String method, String urlStr, JSONObject body,
                                      String overrideUserAgent, Map<String,String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", overrideUserAgent == null || overrideUserAgent.isBlank()
                ? (userAgent == null || userAgent.isBlank() ? buildUserAgent(deviceId) : userAgent)
                : overrideUserAgent);
        c.setRequestProperty("Accept", "application/json;charset=UTF-8");
        c.setRequestProperty("X-Device-Id", deviceId == null ? "" : deviceId);
        c.setRequestProperty("X-Client-Id", CLIENT_ID);
        c.setRequestProperty("X-Client-Version", CLIENT_VERSION);
        if (headers != null) for (Map.Entry<String,String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        JSONObject o;
        try {
            o = text == null || text.isBlank() ? new JSONObject() : new JSONObject(text);
        } catch (Exception parse) {
            o = new JSONObject();
            o.put("raw", text == null ? "" : text);
        }
        if (code < 200 || code >= 400) {
            throw new Exception("迅雷接口 HTTP " + code + ": " + compactError(o, text));
        }
        return o;
    }

    private String compactError(JSONObject o, String raw) {
        String e = firstNonBlank(
                o.optString("error_description", ""),
                o.optString("errorDesc", ""),
                o.optString("error", ""),
                o.optString("raw", ""),
                raw == null ? "" : raw);
        return e.length() > 500 ? e.substring(0, 500) : e;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static String normalizeDeviceId(String s) {
        String v = s == null ? "" : s.trim();
        return v.length() == 32 ? v : md5(v);
    }

    static String md5(String s) {
        return digestHex("MD5", s == null ? "" : s);
    }

    static String sha1(String s) {
        return digestHex("SHA-1", s == null ? "" : s);
    }

    private static String digestHex(String algorithm, String s) {
        try {
            MessageDigest d = MessageDigest.getInstance(algorithm);
            byte[] b = d.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String generateDeviceSign(String deviceId, String packageName) {
        String sha1Hex = sha1(deviceId + packageName + APP_ID + APP_KEY);
        String md5Hex = md5(sha1Hex);
        return "div101." + deviceId + md5Hex;
    }

    static String buildUserAgent(String deviceId) {
        return "ANDROID-" + XL_PACKAGE + "/" + CLIENT_VERSION + " " +
                "netWorkType/5G appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC " +
                "OSVersion/12 protocolVersion/301 platformVersion/10 sdkVersion/" + SDK_VERSION + " " +
                "Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)";
    }
}
