package com.example.xunleivrplayer;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 轻量迅雷云盘协议客户端（个人实验用途）。
 *
 * 设计原则：
 * 1. APP 只保存 refresh_token，不保存用户密码；
 * 2. 文件列表、文件详情、播放直链均实时从迅雷获取；
 * 3. 接口细节参考公开可审阅的 AList/OpenList Thunder Browser 驱动行为，
 *    这些接口并非迅雷面向第三方开发者承诺长期稳定的公开 SDK，因此未来可能失效。
 */
class XunleiApi {
    static final String API_BASE = "https://x-api-pan.xunlei.com/drive/v1";
    static final String FILE_API = API_BASE + "/files";
    static final String USER_API = "https://xluser-ssl.xunlei.com/v1";

    // 当前公开的 Thunder Browser 客户端参数；可能随迅雷升级发生变化。
    static final String CLIENT_ID = "ZUBzD9J_XPXfn7f7";
    static final String CLIENT_SECRET = "yESVmHecEe6F0aou69vl-g";
    static final String CLIENT_VERSION = "1.10.0.2633";
    static final String XL_PACKAGE = "com.xunlei.browser";
    static final String SDK_VERSION = "233100";
    static final String DOWNLOAD_UA = "AndroidDownloadManager/13 (Linux; U; Android 13; M2004J7AC Build/SP1A.210812.016)";

    static final String ROOT_SPACE = "SPACE_BROWSER";

    private static final String[] CAPTCHA_ALGORITHMS = new String[]{
            "uWRwO7gPfdPB/0NfPtfQO+71",
            "F93x+qPluYy6jdgNpq+lwdH1ap6WOM+nfz8/V",
            "0HbpxvpXFsBK5CoTKam",
            "dQhzbhzFRcawnsZqRETT9AuPAJ+wTQso82mRv",
            "SAH98AmLZLRa6DB2u68sGhyiDh15guJpXhBzI",
            "unqfo7Z64Rie9RNHMOB",
            "7yxUdFADp3DOBvXdz0DPuKNVT35wqa5z0DEyEvf",
            "RBG",
            "ThTWPG5eC0UBqlbQ+04nZAptqGCdpv9o55A"
    };

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

    void configureIdentity(String deviceId) {
        this.deviceId = deviceId;
        this.userAgent = buildUserAgent(deviceId);
    }

    String getDeviceId() { return deviceId; }
    Models.Token getToken() { return token; }

    Models.Token login(String username, String password) throws Exception {
        configureIdentity(md5(username + password));
        initCaptchaForLogin(username);
        return signIn(username, password, captchaToken);
    }

    Models.Token completeLogin(String username, String password, String callbackCaptchaToken) throws Exception {
        if (deviceId == null || deviceId.isBlank()) configureIdentity(md5(username + password));
        this.captchaToken = callbackCaptchaToken == null ? "" : callbackCaptchaToken;
        return signIn(username, password, this.captchaToken);
    }

    Models.Token loginWithRefreshToken(String refreshToken, String persistedDeviceId) throws Exception {
        configureIdentity((persistedDeviceId == null || persistedDeviceId.isBlank()) ? md5(refreshToken) : persistedDeviceId);
        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", CLIENT_ID);
        body.put("client_secret", CLIENT_SECRET);
        JSONObject o = requestJson("POST", USER_API + "/auth/token", body, false, null);
        Models.Token t = Models.Token.fromJson(o);
        if (t.accessToken.isBlank()) throw new Exception("刷新登录失败：没有 access_token");
        if (t.refreshToken.isBlank()) t.refreshToken = refreshToken;
        token = t;
        return t;
    }

    private Models.Token signIn(String username, String password, String captcha) throws Exception {
        JSONObject body = new JSONObject();
        body.put("captcha_token", captcha == null ? "" : captcha);
        body.put("client_id", CLIENT_ID);
        body.put("client_secret", CLIENT_SECRET);
        body.put("username", username);
        body.put("password", password);
        JSONObject o = requestJson("POST", USER_API + "/auth/signin", body, false, null);
        Models.Token t = Models.Token.fromJson(o);
        if (t.accessToken.isBlank()) throw new Exception("登录失败：没有 access_token");
        token = t;
        return t;
    }

    private void initCaptchaForLogin(String username) throws Exception {
        String action = "POST:/v1/auth/signin";
        JSONObject meta = new JSONObject();
        if (Pattern.matches("\\w+([-+.\\w])*@\\w+([-.\\w])*\\.\\w+([-.\\w])*", username)) {
            meta.put("email", username);
        } else if (username != null && username.length() >= 11 && username.length() <= 18) {
            meta.put("phone_number", username);
        } else {
            meta.put("username", username);
        }

        JSONObject body = new JSONObject();
        body.put("action", action);
        body.put("captcha_token", captchaToken == null ? "" : captchaToken);
        body.put("client_id", CLIENT_ID);
        body.put("device_id", deviceId);
        body.put("meta", meta);
        body.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor");

        JSONObject resp = requestJson("POST", USER_API + "/shield/captcha/init", body, false, null);
        String verifyUrl = resp.optString("url", "");
        if (!verifyUrl.isBlank()) throw new VerificationRequiredException(verifyUrl);
        captchaToken = resp.optString("captcha_token", "");
        if (captchaToken.isBlank()) throw new Exception("迅雷没有返回 captcha_token");
    }

    List<Models.CloudItem> list(String parentId, String space) throws Exception {
        ensureLoggedIn();
        List<Models.CloudItem> out = new ArrayList<>();
        String page = "";
        String actualSpace = (space == null) ? ROOT_SPACE : space;
        do {
            Uri.Builder b = Uri.parse(FILE_API).buildUpon();
            b.appendQueryParameter("parent_id", parentId == null ? "" : parentId);
            b.appendQueryParameter("page_token", page);
            b.appendQueryParameter("space", actualSpace);
            b.appendQueryParameter("filters", "{\"trashed\":{\"eq\":false}}");
            b.appendQueryParameter("with", "url");
            b.appendQueryParameter("with_audit", "true");
            b.appendQueryParameter("thumbnail_size", "SIZE_LARGE");
            JSONObject o = requestJson("GET", b.build().toString(), null, true, null);
            JSONArray arr = o.optJSONArray("files");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jo = arr.optJSONObject(i);
                    if (jo == null) continue;
                    Models.CloudItem f = Models.CloudItem.fromJson(jo);
                    // 迅雷后端偶尔返回一个空 ID 的重复“迅雷云盘”占位项，直接丢弃。
                    if ("DEFAULT_ROOT".equals(f.folderType) && f.id.isBlank() && f.space.isBlank() && parentId != null && !parentId.isBlank()) {
                        continue;
                    }
                    out.add(f);
                }
            }
            page = o.optString("next_page_token", "");
        } while (!page.isBlank());
        return out;
    }

    Models.StreamLink getStreamLink(Models.CloudItem file) throws Exception {
        ensureLoggedIn();
        Uri.Builder b = Uri.parse(FILE_API + "/" + Uri.encode(file.id)).buildUpon();
        b.appendQueryParameter("_magic", "2021");
        b.appendQueryParameter("space", file.space == null ? "" : file.space);
        b.appendQueryParameter("thumbnail_size", "SIZE_LARGE");
        b.appendQueryParameter("with", "url");
        JSONObject o = requestJson("GET", b.build().toString(), null, true, null);
        Models.CloudItem detail = Models.CloudItem.fromJson(o);
        String url = "";
        for (String u : detail.mediaUrls) {
            if (u != null && !u.isBlank()) { url = u; break; }
        }
        if (url.isBlank()) url = detail.webContentLink;
        if (url == null || url.isBlank()) throw new Exception("迅雷没有返回可播放直链");
        return new Models.StreamLink(url, DOWNLOAD_UA);
    }

    private void ensureLoggedIn() throws Exception {
        if (token == null || token.accessToken == null || token.accessToken.isBlank()) throw new Exception("尚未登录迅雷");
    }

    private JSONObject requestJson(String method, String urlStr, JSONObject body, boolean auth, Map<String,String> extraHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", userAgent == null || userAgent.isBlank() ? buildUserAgent(deviceId) : userAgent);
        c.setRequestProperty("Accept", "application/json;charset=UTF-8");
        c.setRequestProperty("X-Device-Id", deviceId);
        c.setRequestProperty("X-Client-Id", CLIENT_ID);
        c.setRequestProperty("X-Client-Version", CLIENT_VERSION);
        if (auth && token != null) {
            c.setRequestProperty("Authorization", token.authorization());
            if (captchaToken != null && !captchaToken.isBlank()) c.setRequestProperty("X-Captcha-Token", captchaToken);
        }
        if (extraHeaders != null) for (Map.Entry<String,String> e : extraHeaders.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        JSONObject o = text == null || text.isBlank() ? new JSONObject() : new JSONObject(text);
        if (code < 200 || code >= 400) {
            throw new Exception("迅雷接口 HTTP " + code + ": " + compactError(o, text));
        }
        long errorCode = o.optLong("error_code", 0L);
        String error = o.optString("error", "");
        String desc = o.optString("error_description", "");
        if (errorCode != 0 || (!error.isBlank() && !"success".equalsIgnoreCase(error)) || !desc.isBlank()) {
            throw new Exception("迅雷接口错误 " + errorCode + ": " + (!desc.isBlank() ? desc : error));
        }
        return o;
    }

    private String compactError(JSONObject o, String raw) {
        String e = o.optString("error_description", "");
        if (e.isBlank()) e = o.optString("error", "");
        if (e.isBlank()) e = raw == null ? "" : raw;
        return e.length() > 300 ? e.substring(0, 300) : e;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8);
    }

    static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String captchaSign(String deviceId) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String s = CLIENT_ID + CLIENT_VERSION + XL_PACKAGE + deviceId + timestamp;
        for (String a : CAPTCHA_ALGORITHMS) s = md5(s + a);
        return timestamp + "|1." + s;
    }

    static String buildUserAgent(String deviceId) {
        return "ANDROID-" + XL_PACKAGE + "/" + CLIENT_VERSION + " " +
                "networkType/WIFI appid/22062 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC " +
                "OSVersion/13 protocolVersion/301 platformversion/10 sdkVersion/" + SDK_VERSION + " " +
                "Oauth2Client/0.9 (Linux 4_9_337-perf-sn-uotan-gd9d488809c3d) (JAVA 0)";
    }
}
