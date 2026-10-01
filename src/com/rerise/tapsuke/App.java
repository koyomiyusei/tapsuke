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
