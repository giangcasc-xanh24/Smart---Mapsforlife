package com.xanh24.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Tự mở ứng dụng khi màn hình kiosk khởi động (hoặc sau khi cập nhật ứng dụng). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(i);
        } catch (Exception ignored) {
            // Android 10+ có thể chặn mở từ nền — đặt ứng dụng làm Màn hình chính (Home) để luôn tự chạy.
        }
    }
}
