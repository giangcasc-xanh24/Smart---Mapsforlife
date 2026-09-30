package com.xanh24.kiosk;

import android.content.Context;
import android.content.SharedPreferences;

/** Cấu hình lưu trên máy kiosk. */
final class Prefs {
    static final String DEFAULT_SERVER = "https://www.xanh24.com";
    static final String DEFAULT_PIN = "2424";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("x24_kiosk", Context.MODE_PRIVATE);
    }

    String server() {
        String s = sp.getString("server", DEFAULT_SERVER).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? DEFAULT_SERVER : s;
    }

    String device() { return sp.getString("device", "").trim(); }
    String pin() { return sp.getString("pin", DEFAULT_PIN); }
    boolean lockTask() { return sp.getBoolean("lock_task", false); }
    boolean configured() { return sp.getBoolean("configured", false); }
    int reloadHour() { return sp.getInt("reload_hour", 3); }
    long lastDataVersion() { return sp.getLong("data_version", -1); }

    void save(String server, String device, String pin, boolean lockTask) {
        sp.edit().putString("server", server).putString("device", device).putString("pin", pin)
                .putBoolean("lock_task", lockTask).putBoolean("configured", true).apply();
    }

    void setDataVersion(long v) { sp.edit().putLong("data_version", v).apply(); }

    /** Vị trí đặt máy do kỹ thuật viên ghim (null = tự động theo GPS / máy chủ). */
    double[] pinned() {
        String s = sp.getString("pin_loc", "").trim();
        if (s.isEmpty()) return null;
        try {
            String[] a = s.split("[,;\\s]+");
            double la = Double.parseDouble(a[0]), ln = Double.parseDouble(a[1]);
            if (la < -90 || la > 90 || ln < -180 || ln > 180) return null;
            return new double[]{la, ln};
        } catch (Exception e) {
            return null;
        }
    }

    String pinnedText() { return sp.getString("pin_loc", ""); }

    void setPinned(String s) { sp.edit().putString("pin_loc", s == null ? "" : s.trim()).apply(); }

    /** Trang bản đồ ở chế độ kiosk. */
    String startUrl() {
        String d = device();
        try {
            return server() + "/?" + (d.isEmpty() ? "mode=kiosk" : "device=" + java.net.URLEncoder.encode(d, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            return server() + "/?mode=kiosk";
        }
    }

    String host() {
        try {
            return new java.net.URL(server()).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
