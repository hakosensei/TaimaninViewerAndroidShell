package com.example.xunleivrplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Hosts Xunlei's official review panel inside the app and implements the
 * XLJSWebViewBridge contract expected by https://i.xunlei.com/xlcaptcha/android.html.
 */
public class XunleiVerifyActivity extends Activity {
    static final String EXTRA_REVIEW_URL = "review_url";
    static final String EXTRA_CREDIT_KEY = "credit_key";
    static final String EXTRA_DEVICE_SIGN = "device_sign";
    static final String RESULT_CREDIT_KEY = "credit_key";

    private WebView web;
    private String reviewUrl = "";
    private String creditKey = "";
    private String deviceSign = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        reviewUrl = in == null ? "" : safe(in.getStringExtra(EXTRA_REVIEW_URL));
        creditKey = in == null ? "" : safe(in.getStringExtra(EXTRA_CREDIT_KEY));
        deviceSign = in == null ? "" : safe(in.getStringExtra(EXTRA_DEVICE_SIGN));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("迅雷登录安全验证");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("迅雷要求进行手机短信/安全验证。请在下方获取并输入验证码；验证成功后会自动返回播放器继续登录。\n\n如果页面显示“智能检测”，按迅雷页面提示完成即可。");
        hint.setTextSize(14);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(XunleiApi.buildUserAgent(""));

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "XLJSWebViewBridge");
        web.loadUrl("https://i.xunlei.com/xlcaptcha/android.html");
    }

    private final class Bridge {
        @JavascriptInterface
        public void sendMessage(String name, String data, String callback) {
            if ("nativeGetUserDeviceInfo".equals(name)) {
                runOnUiThread(() -> sendReviewData(callback));
                return;
            }
            if ("nativeRecvOperationResult".equals(name)) {
                handleOperationResult(data);
            }
        }
    }

    private void sendReviewData(String callback) {
        try {
            JSONObject o = new JSONObject();
            o.put("creditkey", creditKey);
            o.put("reviewurl", reviewUrl);
            o.put("deviceid", deviceSign);
            o.put("devicesign", deviceSign);
            String cb = callback == null || callback.isBlank() ? "reviewCb" : callback;
            String js = "window[" + JSONObject.quote(cb) + "](" + JSONObject.quote(o.toString()) + ");";
            web.evaluateJavascript(js, null);
        } catch (Exception e) {
            fail("无法初始化迅雷验证页：" + e.getMessage());
        }
    }

    private void handleOperationResult(String data) {
        try {
            JSONObject o = new JSONObject(data == null ? "{}" : data);
            String code = o.optString("roErrorCode", "");
            if ("0".equals(code)) {
                JSONObject rd = o.optJSONObject("roData");
                String trusted = rd == null ? "" : rd.optString("creditkey", "");
                if (trusted.isBlank()) {
                    fail("迅雷验证成功，但没有返回 CreditKey");
                    return;
                }
                Intent out = new Intent();
                out.putExtra(RESULT_CREDIT_KEY, trusted);
                setResult(RESULT_OK, out);
                finish();
            } else if ("30001".equals(code)) {
                setResult(RESULT_CANCELED);
                finish();
            }
        } catch (Exception e) {
            fail("无法读取迅雷验证结果：" + e.getMessage());
        }
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else { setResult(RESULT_CANCELED); super.onBackPressed(); }
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("XLJSWebViewBridge");
            web.destroy();
        }
        super.onDestroy();
    }
}
