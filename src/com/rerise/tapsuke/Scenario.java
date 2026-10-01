package com.rerise.tapsuke;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** シナリオ＝手順（ステップ）の並びと、繰り返し・ランダム化の設定 */
public class Scenario {

    public static final int TAP = 0;
    public static final int LONG = 1;
    public static final int SWIPE = 2;
    public static final int WAIT = 3;

    public static final String[] TYPE_NAMES = {"タップ", "長押し", "スワイプ", "待機"};

    public static class Step {
        public int type = TAP;
        public float x, y;        // 画面上の座標（px）
        public float x2, y2;      // スワイプの終点
        public int duration = 60; // 押している時間 / スワイプにかける時間（ms）
        public int after = 500;   // この操作のあとに待つ時間（ms）
        public List<float[]> path; // 記録したスワイプの軌跡（任意）

        public Step copy() {
            Step s = new Step();
            s.type = type; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
            s.duration = duration; s.after = after;
            if (path != null) {
                s.path = new ArrayList<>();
                for (float[] p : path) s.path.add(new float[]{p[0], p[1]});
            }
            return s;
        }

        public String label() {
            switch (type) {
                case TAP:
                    return "タップ (" + (int) x + ", " + (int) y + ")";
                case LONG:
                    return "長押し (" + (int) x + ", " + (int) y + ") " + duration + "ms";
                case SWIPE:
                    return "スワイプ (" + (int) x + ", " + (int) y + ") → (" + (int) x2 + ", " + (int) y2 + ") "
                            + duration + "ms" + (path != null && path.size() > 2 ? " 軌跡" : "");
                default:
                    return "待機";
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("x", x); o.put("y", y);
            if (type == SWIPE) { o.put("x2", x2); o.put("y2", y2); }
            o.put("duration", duration);
            o.put("after", after);
            if (path != null && path.size() > 2) {
                JSONArray a = new JSONArray();
                for (float[] p : path) { a.put((double) p[0]); a.put((double) p[1]); }
                o.put("path", a);
            }
            return o;
        }

        static Step fromJson(JSONObject o) {
            Step s = new Step();
            s.type = Math.max(0, Math.min(3, o.optInt("type", TAP)));
            s.x = (float) o.optDouble("x", 0); s.y = (float) o.optDouble("y", 0);
            s.x2 = (float) o.optDouble("x2", s.x); s.y2 = (float) o.optDouble("y2", s.y);
            s.duration = Math.max(1, o.optInt("duration", 60));
            s.after = Math.max(0, o.optInt("after", 500));
            JSONArray a = o.optJSONArray("path");
            if (a != null && a.length() >= 6) {
                s.path = new ArrayList<>();
                for (int i = 0; i + 1 < a.length(); i += 2)
                    s.path.add(new float[]{(float) a.optDouble(i), (float) a.optDouble(i + 1)});
            }
            return s;
        }
    }

    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String name = "新しいシナリオ";
    public List<Step> steps = new ArrayList<>();
    public int repeat = 0;        // 繰り返し回数（0 = 止めるまで）
    public int maxMinutes = 0;    // この時間で自動停止（0 = なし）
    public int loopWait = 0;      // 1周ごとの追加の待ち（ms）
    public int jitterPx = 0;      // 位置のランダムなズレ（半径px）
    public int jitterPct = 0;     // 間隔のランダムなズレ（±%）
    public int screenW, screenH;  // 作ったときの画面サイズ（違う画面では比率で直す）
    public long updated;

    // ---------------- JSON ----------------

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "tapsuke");
        o.put("format", 1);
        o.put("id", id);
        o.put("name", name);
        o.put("repeat", repeat);
        o.put("maxMinutes", maxMinutes);
        o.put("loopWait", loopWait);
        o.put("jitterPx", jitterPx);
        o.put("jitterPct", jitterPct);
        o.put("screenW", screenW);
        o.put("screenH", screenH);
        o.put("updated", updated);
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(s.toJson());
        o.put("steps", a);
        return o;
    }

    public static Scenario fromJson(JSONObject o) {
        Scenario sc = new Scenario();
        String id = o.optString("id", "");
        if (!id.isEmpty()) sc.id = id.replaceAll("[^A-Za-z0-9_-]", "");
        if (sc.id.isEmpty()) sc.id = UUID.randomUUID().toString().substring(0, 8);
        sc.name = o.optString("name", sc.name);
        sc.repeat = Math.max(0, o.optInt("repeat", 0));
        sc.maxMinutes = Math.max(0, o.optInt("maxMinutes", 0));
        sc.loopWait = Math.max(0, o.optInt("loopWait", 0));
        sc.jitterPx = Math.max(0, o.optInt("jitterPx", 0));
        sc.jitterPct = Math.max(0, Math.min(90, o.optInt("jitterPct", 0)));
        sc.screenW = o.optInt("screenW", 0);
        sc.screenH = o.optInt("screenH", 0);
        sc.updated = o.optLong("updated", 0);
        JSONArray a = o.optJSONArray("steps");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject so = a.optJSONObject(i);
            if (so != null) sc.steps.add(Step.fromJson(so));
        }
        return sc;
    }

    public Scenario copyAsNew() {
        try {
            Scenario s = fromJson(toJson());
            s.id = UUID.randomUUID().toString().substring(0, 8);
            s.name = name + " のコピー";
            return s;
        } catch (Exception e) {
            return new Scenario();
        }
    }

    public String summary() {
        int n = 0;
        for (Step s : steps) if (s.type != WAIT) n++;
        String r = repeat == 0 ? "止めるまで" : repeat + "回";
        return n + "操作・" + r + (maxMinutes > 0 ? "・最大" + maxMinutes + "分" : "")
                + (jitterPx > 0 || jitterPct > 0 ? "・ランダム" : "");
    }

    // ---------------- 保存場所 ----------------

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "scenarios");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Scenario> all(Context c) {
        List<Scenario> list = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                list.add(fromJson(new JSONObject(readAll(new FileInputStream(f)))));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.updated, a.updated));
        return list;
    }

    public static Scenario load(Context c, String id) {
        if (id == null) return null;
        File f = new File(dir(c), id + ".json");
        if (!f.exists()) return null;
        try {
            return fromJson(new JSONObject(readAll(new FileInputStream(f))));
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Context c) {
        updated = System.currentTimeMillis();
        try {
            File tmp = new File(dir(c), id + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            out.write(toJson().toString(1).getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.renameTo(new File(dir(c), id + ".json"));
        } catch (Exception e) {
            App.log(c, e);
        }
    }

    public static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
    }

    public static boolean exists(Context c, String id) {
        return new File(dir(c), id + ".json").exists();
    }

    public static String readAll(InputStream in) throws Exception {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
