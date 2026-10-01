package com.rerise.tapsuke;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * お手本画像の保管。アプリ専用の領域（他のアプリから見えない）にだけ置く。
 * 画像は端末の外へは送らない。
 */
public class Templates {

    private static final Map<String, Matcher.Tpl> cache = new HashMap<>();

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "templates");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c, String id) {
        return new File(dir(c), id + ".png");
    }

    public static String save(Context c, Bitmap crop) {
        String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        writePng(file(c, id), crop);
        return id;
    }

    private static void writePng(File f, Bitmap b) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static boolean exists(Context c, String id) {
        return id != null && file(c, id).exists();
    }

    public static Bitmap bitmap(Context c, String id) {
        if (!exists(c, id)) return null;
        return BitmapFactory.decodeFile(file(c, id).getAbsolutePath());
    }

    /** 照合用に前処理したお手本（読み込みは1回だけ） */
    public static synchronized Matcher.Tpl tpl(Context c, String id) {
        Matcher.Tpl t = cache.get(id);
        if (t != null) return t;
        Bitmap b = bitmap(c, id);
        if (b == null) return null;
        t = Matcher.prepare(b);
        b.recycle();
        cache.put(id, t);
        return t;
    }

    /** どのシナリオからも使われていない画像を消す */
    public static void cleanup(Context c) {
        Set<String> used = new HashSet<>();
        for (Scenario s : Scenario.all(c))
            for (Scenario.Step st : s.steps) if (st.tpl != null) used.add(st.tpl);
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String id = f.getName().replace(".png", "");
            if (!used.contains(id)) {
                f.delete();
                synchronized (Templates.class) { cache.remove(id); }
            }
        }
    }

    // ---------------- 書き出し・読み込み用 ----------------

    public static JSONObject export(Context c, Scenario s) throws Exception {
        JSONObject o = new JSONObject();
        for (Scenario.Step st : s.steps) {
            if (st.tpl == null || o.has(st.tpl) || !exists(c, st.tpl)) continue;
            byte[] bytes = java.nio.file.Files.readAllBytes(file(c, st.tpl).toPath());
            o.put(st.tpl, Base64.encodeToString(bytes, Base64.NO_WRAP));
        }
        return o;
    }

    public static void importAll(Context c, JSONObject o) {
        if (o == null) return;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String id = it.next().replaceAll("[^A-Za-z0-9_-]", "");
            if (id.isEmpty()) continue;
            try {
                byte[] bytes = Base64.decode(o.getString(id), Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (b != null) writePng(file(c, id), b);
            } catch (Exception ignored) {
            }
        }
    }

    public static byte[] thumbPng(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }
}
