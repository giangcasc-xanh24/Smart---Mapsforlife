package com.xanh24.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * Xanh24 - Maps for Life · Ứng dụng kiosk Android.
 * Mở bản đồ số từ máy chủ Xanh24 trong WebView toàn màn hình, luôn bật màn hình,
 * tự tải lại khi mất mạng / treo / có dữ liệu mới, tự chạy khi khởi động máy.
 * Menu cài đặt ẩn: chạm nhanh 5 lần vào góc trên bên phải màn hình → nhập PIN.
 */
public class MainActivity extends Activity {
    static final String TAG = "X24Kiosk";
    static final long CHECK_MS = 60_000;          // kiểm tra máy chủ / dữ liệu mới
    static final long RETRY_MS = 15_000;          // thử lại khi mất mạng
    static final long WATCHDOG_MS = 90_000;       // phát hiện WebView bị treo

    private Prefs prefs;
    private FrameLayout root;
    private WebView web;
    private LinearLayout offline;
    private TextView offlineText;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageOk = false;
    private boolean mainFrameError = false;
    private long lastPong = 0;
    private int lastReloadDay = -1;
    private String appVersion = "1.0";
    private int appCode = 1;
    private final long[] taps = new long[5];
    private int tapIdx = 0;
    private AlertDialog dialog;
    private LocationManager lm;
    private Location best;
    private static final int REQ_LOC = 24;

    // ------------------------------------------------------------------ vòng đời
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pi.versionName;
            appCode = pi.versionCode;
        } catch (Exception ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 27, 63));
        setContentView(root);
        buildOffline();
        createWebView();
        hideSystemUi();
        if (!prefs.configured()) {
            ui.postDelayed(new Runnable() { public void run() { showSettings(true); } }, 600);
        }
        load();
        initLocation();
        ui.postDelayed(checker, CHECK_MS);
        ui.postDelayed(watchdog, WATCHDOG_MS);
        Calendar c = Calendar.getInstance();
        lastReloadDay = c.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (web != null) web.onResume();
        if (prefs.lockTask() && Build.VERSION.SDK_INT >= 21) {
            try { startLockTask(); } catch (Exception e) { Log.w(TAG, "lockTask", e); }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // ------------------------------------------------------------------ định vị (GPS + Wi-Fi/mạng)
    private void initLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        startLocation();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC) startLocation();
        hideSystemUi();
    }

    private final LocationListener locListener = new LocationListener() {
        public void onLocationChanged(Location l) { offer(l); }
        public void onStatusChanged(String p, int s, Bundle b) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    @SuppressLint("MissingPermission")
    private void startLocation() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            for (String p : lm.getProviders(true)) {
                try { offer(lm.getLastKnownLocation(p)); } catch (Exception ignored) { }
                if (LocationManager.GPS_PROVIDER.equals(p) || LocationManager.NETWORK_PROVIDER.equals(p) || LocationManager.PASSIVE_PROVIDER.equals(p)) {
                    try { lm.requestLocationUpdates(p, 15_000, 3, locListener, Looper.getMainLooper()); } catch (Exception e) { Log.w(TAG, "loc " + p, e); }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "location", e);
        }
    }

    /** Giữ bản định vị tốt nhất: mới hơn 2 phút thì thay, cùng thời điểm thì lấy bản chính xác hơn. */
    private void offer(Location l) {
        if (l == null) return;
        boolean take = best == null
                || l.getTime() - best.getTime() > 120_000
                || (l.hasAccuracy() && (!best.hasAccuracy() || l.getAccuracy() <= best.getAccuracy() + 5));
        if (!take) return;
        best = l;
        if (web != null && pageOk) web.evaluateJavascript("window.X24NativeLocation&&window.X24NativeLocation(" + locJson() + ")", null);
    }

    private String locJson() {
        try {
            JSONObject o = new JSONObject();
            double[] pin = prefs.pinned();
            if (pin != null) {
                o.put("lat", pin[0]); o.put("lng", pin[1]); o.put("acc", 0); o.put("src", "pinned"); o.put("pinned", true);
            } else if (best != null) {
                o.put("lat", best.getLatitude()); o.put("lng", best.getLongitude());
                o.put("acc", best.hasAccuracy() ? Math.round(best.getAccuracy()) : 999);
                o.put("src", best.getProvider()); o.put("pinned", false);
                o.put("age", Math.max(0, (System.currentTimeMillis() - best.getTime()) / 1000));
            } else {
                return "null";
            }
            return o.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    @Override
    protected void onDestroy() {
        if (lm != null) try { lm.removeUpdates(locListener); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Kiosk công cộng: không cho thoát bằng nút Quay lại. Chuyển thành "quay lại" trong ứng dụng web.
        if (web != null) web.evaluateJavascript("(function(){var b=document.querySelector('#panelBody [data-act=back]');if(b)b.click();})()", null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { askPin(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // ------------------------------------------------------------------ cử chỉ mở menu ẩn
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float zone = dp(90);
            if (ev.getX() > root.getWidth() - zone && ev.getY() < zone) {
                long now = SystemClock.uptimeMillis();
                taps[tapIdx % 5] = now;
                tapIdx++;
                long oldest = taps[tapIdx % 5];
                if (tapIdx >= 5 && oldest > 0 && now - oldest < 3000) {
                    tapIdx = 0;
                    for (int i = 0; i < 5; i++) taps[i] = 0;
                    askPin();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ WebView
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        if (web != null) { root.removeView(web); web.destroy(); }
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 27, 63));
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { return true; } });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " Xanh24Kiosk/" + appVersion);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new Bridge(), "X24Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void load() {
        mainFrameError = false;
        pageOk = false;
        String url = prefs.startUrl();
        Log.i(TAG, "load " + url);
        if (web != null) web.loadUrl(url);
    }

    private boolean sameHost(Uri u) {
        String h = prefs.host();
        return u != null && u.getHost() != null && (u.getHost().equalsIgnoreCase(h)
                || u.getHost().equalsIgnoreCase(h.startsWith("www.") ? h.substring(4) : "www." + h));
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String sc = u.getScheme() == null ? "" : u.getScheme();
            if ((sc.equals("http") || sc.equals("https")) && sameHost(u)) return false;
            // Kiosk công cộng: không mở trang/ứng dụng bên ngoài (người dùng quét QR bằng điện thoại).
            Log.i(TAG, "blocked " + u);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameError = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mainFrameError) {
                pageOk = true;
                lastPong = SystemClock.uptimeMillis();
                showOffline(false, null);
                if (best != null || prefs.pinned() != null) view.evaluateJavascript("window.X24NativeLocation&&window.X24NativeLocation(" + locJson() + ")", null);
            }
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) {
                mainFrameError = true;
                pageOk = false;
                showOffline(true, "Không kết nối được máy chủ (" + err.getDescription() + ").");
                scheduleRetry();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse res) {
            if (req.isForMainFrame() && res.getStatusCode() >= 500) {
                mainFrameError = true;
                pageOk = false;
                showOffline(true, "Máy chủ đang bận (mã " + res.getStatusCode() + ").");
                scheduleRetry();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Trình hiển thị bị dừng (thiếu bộ nhớ…) → tạo lại WebView, không để ứng dụng thoát.
            Log.w(TAG, "render process gone");
            createWebView();
            load();
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, true, false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "js: " + m.message());
            return true;
        }
    }

    /** Cầu nối cho trang web: window.X24Android.* */
    private class Bridge {
        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("app", "xanh24-kiosk-android");
                o.put("version", appVersion);
                o.put("versionCode", appCode);
                o.put("device", prefs.device());
                o.put("server", prefs.server());
                o.put("android", Build.VERSION.RELEASE);
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String location() {
            return locJson();
        }

        @JavascriptInterface
        public void openSettings() {
            ui.post(new Runnable() { public void run() { askPin(); } });
        }

        @JavascriptInterface
        public void reload() {
            ui.post(new Runnable() { public void run() { load(); } });
        }

        @JavascriptInterface
        public void pong() {
            lastPong = SystemClock.uptimeMillis();
        }
    }

    // ------------------------------------------------------------------ mất mạng / tự phục hồi
    private void buildOffline() {
        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setBackgroundColor(Color.rgb(11, 27, 63));
        offline.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView title = new TextView(this);
        title.setText("Xanh24 - Maps for Life");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setGravity(Gravity.CENTER);
        offline.addView(title);
        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(20);
        offline.addView(pb, lp);
        offlineText = new TextView(this);
        offlineText.setTextColor(Color.rgb(200, 214, 235));
        offlineText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        offlineText.setGravity(Gravity.CENTER);
        offlineText.setText("Đang kết nối…");
        offline.addView(offlineText);
        TextView foot = new TextView(this);
        foot.setText("Bản quyền thuộc về Công ty TNHH Công nghệ và Truyền thông Xanh24");
        foot.setTextColor(Color.rgb(140, 160, 195));
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = dp(48);
        offline.addView(foot, fl);
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showOffline(boolean show, String msg) {
        offline.setVisibility(show ? View.VISIBLE : View.GONE);
        if (msg != null) offlineText.setText(msg + "\nTự động thử lại sau " + (RETRY_MS / 1000) + " giây…\n" + prefs.server());
    }

    private final Runnable retry = new Runnable() {
        public void run() { if (!pageOk) load(); }
    };

    private void scheduleRetry() {
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, RETRY_MS);
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            @SuppressWarnings("deprecation") NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /** Mỗi phút: hỏi máy chủ phiên bản dữ liệu; mất mạng → giữ nguyên bản đã lưu; tải lại toàn bộ lúc 3 giờ sáng. */
    private final Runnable checker = new Runnable() {
        public void run() {
            ui.postDelayed(this, CHECK_MS);
            Calendar c = Calendar.getInstance();
            int day = c.get(Calendar.DAY_OF_YEAR);
            if (c.get(Calendar.HOUR_OF_DAY) == prefs.reloadHour() && day != lastReloadDay && online()) {
                lastReloadDay = day;
                Log.i(TAG, "daily reload");
                if (web != null) web.clearCache(false);
                load();
                return;
            }
            if (!online()) return;
            new Thread(new Runnable() {
                public void run() {
                    JSONObject vv = fetchJson(prefs.server() + "/api/public/version");
                    // Máy chủ bản cũ chưa có /version → dùng /api/health để biết máy chủ còn hoạt động
                    if (vv == null && fetchJson(prefs.server() + "/api/health") != null) vv = new JSONObject();
                    final JSONObject v = vv;
                    ui.post(new Runnable() {
                        public void run() {
                            if (v == null) return;
                            if (!pageOk) { load(); return; }         // máy chủ đã hoạt động lại
                            long dv = v.optLong("data_version", -1);
                            if (dv == -1) return;
                            long old = prefs.lastDataVersion();
                            prefs.setDataVersion(dv);
                            // Trang web tự nạp lại dữ liệu mới khi về chế độ chờ; ứng dụng chỉ báo nhắc nếu trang cũ không hỗ trợ.
                            if (old != -1 && dv != old && web != null)
                                web.evaluateJavascript("window.X24DataChanged&&window.X24DataChanged(" + dv + ")", null);
                            JSONObject app = v.optJSONObject("kiosk_app");
                            if (app != null) latestApp = app;
                        }
                    });
                }
            }).start();
        }
    };

    /** Phát hiện trang bị treo (JavaScript không phản hồi) → tải lại. */
    private final Runnable watchdog = new Runnable() {
        public void run() {
            ui.postDelayed(this, WATCHDOG_MS);
            if (web == null || !pageOk) return;
            if (lastPong > 0 && SystemClock.uptimeMillis() - lastPong > WATCHDOG_MS * 2) {
                Log.w(TAG, "watchdog reload");
                lastPong = SystemClock.uptimeMillis();
                load();
                return;
            }
            web.evaluateJavascript("(function(){try{X24Android.pong()}catch(e){}return 1})()", null);
        }
    };

    private JSONObject latestApp;

    static JSONObject fetchJson(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ menu cài đặt (bảo vệ bằng PIN)
    private void askPin() {
        if (dialog != null && dialog.isShowing()) return;
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("Mã PIN quản trị");
        pin.setGravity(Gravity.CENTER);
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(pin);
        dialog = new AlertDialog.Builder(this)
                .setTitle("Cài đặt kiosk")
                .setView(box)
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Mở", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (pin.getText().toString().equals(prefs.pin())) showSettings(false);
                        else Toast.makeText(MainActivity.this, "Sai mã PIN", Toast.LENGTH_SHORT).show();
                        hideSystemUi();
                    }
                }).create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { public void onDismiss(DialogInterface d) { hideSystemUi(); } });
        dialog.show();
        pin.requestFocus();
    }

    private void showSettings(final boolean firstRun) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(24), dp(8), dp(24), dp(8));
        if (firstRun) f.addView(note("Chào mừng! Nhập địa chỉ máy chủ và mã màn hình (tạo tại Dashboard → Màn hình kiosk). Để trống mã nếu chỉ chạy chế độ kiosk chung."));
        final EditText server = field(f, "Địa chỉ máy chủ", prefs.server(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText device = field(f, "Mã màn hình (VD: HK-01)", prefs.device(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText pin = field(f, "Mã PIN mở cài đặt", prefs.pin(), InputType.TYPE_CLASS_NUMBER);
        final EditText loc = field(f, "Vị trí đặt máy (vĩ độ, kinh độ) — để trống: tự động theo GPS / toạ độ quản trị", prefs.pinnedText(), InputType.TYPE_CLASS_TEXT);
        loc.setHint("21.028511, 105.854167");
        final TextView gpsInfo = note(best != null ? String.format(java.util.Locale.US, "GPS hiện tại: %.6f, %.6f · sai số ±%d m · nguồn %s",
                best.getLatitude(), best.getLongitude(), best.hasAccuracy() ? Math.round(best.getAccuracy()) : 999, best.getProvider())
                : "Chưa có tín hiệu định vị. Kiểm tra: Cài đặt → Vị trí đang bật, ứng dụng được cấp quyền Vị trí, có Wi-Fi.");
        f.addView(gpsInfo);
        LinearLayout lrow = new LinearLayout(this);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        lrow.addView(smallBtn("Dùng vị trí GPS hiện tại", new View.OnClickListener() {
            public void onClick(View v) {
                if (best == null) { Toast.makeText(MainActivity.this, "Chưa có tín hiệu GPS", Toast.LENGTH_SHORT).show(); return; }
                loc.setText(String.format(java.util.Locale.US, "%.6f, %.6f", best.getLatitude(), best.getLongitude()));
            }
        }));
        lrow.addView(smallBtn("Tự động (bỏ ghim)", new View.OnClickListener() {
            public void onClick(View v) { loc.setText(""); }
        }));
        lrow.addView(smallBtn("Cấp quyền Vị trí", new View.OnClickListener() {
            public void onClick(View v) { initLocation(); }
        }));
        f.addView(lrow);
        final CheckBox lock = new CheckBox(this);
        lock.setText("Khoá ứng dụng trên màn hình (Lock task / ghim màn hình)");
        lock.setChecked(prefs.lockTask());
        f.addView(lock);
        f.addView(note("Phiên bản ứng dụng " + appVersion + " (" + appCode + ") · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL
                + "\nMẹo: đặt ứng dụng làm \"Màn hình chính\" (Home) để tự chạy khi bật máy. Mở lại menu này: chạm nhanh 5 lần vào góc trên bên phải."));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn("Xoá bộ nhớ đệm", new View.OnClickListener() {
            public void onClick(View v) {
                if (web != null) web.clearCache(true);
                WebStorage.getInstance().deleteAllData();
                Toast.makeText(MainActivity.this, "Đã xoá bộ nhớ đệm — đang tải lại", Toast.LENGTH_SHORT).show();
                load();
            }
        }));
        row.addView(smallBtn("Cài đặt Android", new View.OnClickListener() {
            public void onClick(View v) {
                stopLock();
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        }));
        if (latestApp != null && latestApp.optInt("version_code", 0) > appCode && latestApp.optString("apk_url", "").length() > 0) {
            row.addView(smallBtn("Cập nhật " + latestApp.optString("version", ""), new View.OnClickListener() {
                public void onClick(View v) { downloadUpdate(latestApp.optString("apk_url")); }
            }));
        }
        f.addView(row);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Thiết lập Xanh24 Kiosk" : "Cài đặt kiosk")
                .setView(sv)
                .setCancelable(!firstRun)
                .setPositiveButton("Lưu & tải lại", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = server.getText().toString().trim();
                        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
                        String p = pin.getText().toString().trim();
                        prefs.save(s, device.getText().toString().trim().toUpperCase(), p.isEmpty() ? Prefs.DEFAULT_PIN : p, lock.isChecked());
                        prefs.setPinned(loc.getText().toString());
                        if (!lock.isChecked()) stopLock();
                        load();
                        hideSystemUi();
                    }
                });
        if (!firstRun) {
            b.setNegativeButton("Đóng", null);
            b.setNeutralButton("Thoát ứng dụng", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    stopLock();
                    finishAndRemoveTask();
                }
            });
        }
        dialog = b.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { public void onDismiss(DialogInterface d) { hideSystemUi(); } });
        dialog.show();
    }

    private void stopLock() {
        if (Build.VERSION.SDK_INT >= 21) {
            try { stopLockTask(); } catch (Exception ignored) { }
        }
    }

    private void downloadUpdate(String url) {
        try {
            final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (url.startsWith("/")) url = prefs.server() + url;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("Xanh24 Kiosk – bản cập nhật");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            final long id = dm.enqueue(r);
            Toast.makeText(this, "Đang tải bản cập nhật…", Toast.LENGTH_LONG).show();
            registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try { unregisterReceiver(this); } catch (Exception ignored) { }
                    Uri apk = dm.getUriForDownloadedFile(id);
                    if (apk == null) return;
                    stopLock();
                    Intent in = new Intent(Intent.ACTION_VIEW);
                    in.setDataAndType(apk, "application/vnd.android.package-archive");
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(in);
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            Toast.makeText(this, "Không tải được: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ tiện ích giao diện
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Color.rgb(90, 104, 130));
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setTextColor(Color.rgb(20, 52, 111));
        l.setPadding(0, dp(10), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        parent.addView(e);
        return e;
    }

    private Button smallBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setOnClickListener(l);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(Color.rgb(232, 240, 250));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(4), dp(8), dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }
}
