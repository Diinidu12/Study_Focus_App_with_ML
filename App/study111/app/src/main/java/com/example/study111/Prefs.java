package com.example.study111;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    private static final String PREFS_NAME = "pomodoro_prefs";
    public static final String KEY_FOCUS_MIN  = "focus_min";
    public static final String KEY_BREAK_MIN  = "break_min";

    public static final int DEFAULT_FOCUS_MIN = 25;
    public static final int DEFAULT_BREAK_MIN = 5;

    private static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getFocusMin(Context ctx) {
        return get(ctx).getInt(KEY_FOCUS_MIN, DEFAULT_FOCUS_MIN);
    }

    public static int getBreakMin(Context ctx) {
        return get(ctx).getInt(KEY_BREAK_MIN, DEFAULT_BREAK_MIN);
    }

    public static void setFocusMin(Context ctx, int min) {
        get(ctx).edit().putInt(KEY_FOCUS_MIN, Math.max(1, min)).apply();
    }

    public static void setBreakMin(Context ctx, int min) {
        get(ctx).edit().putInt(KEY_BREAK_MIN, Math.max(1, min)).apply();
    }
}