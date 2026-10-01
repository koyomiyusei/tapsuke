package com.rerise.tapsuke;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class App extends Application {

    public static final String PREF = "tapsuke";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log(this, e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 実行ログ（日時・周回数・手順番号・秒数などの数字だけ。画面の内容は残さない）。新しい順に最大200行 */
    public static void runLog(Context c, String line) {
        try {
            String when = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(new Date());
            String old = prefs(c).getString("run_log", "");
            String s = when + " " + line + (old.isEmpty() ? "" : "\n" + old);
            String[] lines = s.split("\n");
            if (lines.length > 200) s = String.join("\n", java.util.Arrays.copyOf(lines, 200));
            prefs(c).edit().putString("run_log", s).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 最後のエラーを1件だけ残す（メイン画面から見られる） */
    public static void log(Context c, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            if (s.length() > 4000) s = s.substring(0, 4000);
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(new Date());
            prefs(c).edit().putString("last_error", when + "\n" + s).commit();
        } catch (Throwable ignored) {
        }
    }
}
