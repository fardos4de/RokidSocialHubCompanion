package com.rokidsocialhub.companion;

import android.util.Log;

public final class AppLog {
    private static final String TAG = "RokidSocialHub";
    private AppLog() {}

    public static void d(String message) {
        Log.d(TAG, message);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}
