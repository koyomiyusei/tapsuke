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

    // 下の配列の初期化で使う（R_ の値と同じ）
    private static final int R_TAP_IMAGE_ = 0, R_TAP_AREA_ = 1, R_SWIPE_AREA_ = 2, R_WAIT_ = 3, R_STOP_ = 4;

    public static final int TAP = 0;
    public static final int LONG = 1;
    public static final int SWIPE = 2;
    public static final int WAIT = 3;
    public static final int IMAGE = 4;   // 画像を見つけたらタップ
    public static final int UNTIL = 5;   // 画像が出るまで、操作をくり返す（または待つ）

    // UNTIL のくり返し操作
    public static final int ACT_NONE = 0, ACT_TAP = 1, ACT_SWIPE = 2;

    public static final String[] TYPE_NAMES = {"タップ", "長押し", "スワイプ", "待機", "画像をタップ", "画像が出るまで"};
    public static final String[] ACT_NAMES = {"待つだけ", "タップをくり返す", "スワイプをくり返す"};

    public static class Step {
        public int type = TAP;
        public float x, y;        // 画面上の座標（px）
        public float x2, y2;      // スワイプの終点
        public int duration = 60; // 押している時間 / スワイプにかける時間（ms）
        public int after = 500;   // この操作のあとに待つ時間（ms）
        public List<float[]> path; // 記録したスワイプの軌跡（任意）
        public int afterMax = 0;  // 0 より大きければ after〜afterMax の間でランダムに待つ

        // 画像の手順（IMAGE / UNTIL）
        public String tpl;        // お手本画像のID
        public int timeoutSec = 30;   // この秒数見つからなければ…
        public boolean skipOnTimeout; // true=次の手順へ / false=止める
        public int threshold = 85;    // 一致の判定（%）
        public int act = ACT_NONE;    // UNTIL でくり返す操作
        public boolean tapFound;      // UNTIL で、見つけた画像もタップする

        public boolean isImage() { return type == IMAGE || type == UNTIL; }

        /** マーカー（画面上の位置）を持つか */
        public boolean hasPoint() {
            return type == TAP || type == LONG || type == SWIPE || (type == UNTIL && act != ACT_NONE);
        }

        public boolean isSwipeLike() {
            return type == SWIPE || (type == UNTIL && act == ACT_SWIPE);
        }

        public Step copy() {
            Step s = new Step();
            s.type = type; s.x = x; s.y = y; s.x2 = x2; s.y2 = y2;
            s.duration = duration; s.after = after; s.afterMax = afterMax;
            s.tpl = tpl; s.timeoutSec = timeoutSec; s.skipOnTimeout = skipOnTimeout;
            s.threshold = threshold; s.act = act; s.tapFound = tapFound;
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
                case IMAGE:
                    return "画像をタップ（最大" + timeoutSec + "秒待つ）";
                case UNTIL:
                    return "画像が出るまで" + ACT_NAMES[act] + (tapFound ? "→出たらタップ" : "")
                            + "（最大" + timeoutSec + "秒）";
                default:
                    return "待機";
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("x", x); o.put("y", y);
            o.put("x2", x2); o.put("y2", y2);
            o.put("duration", duration);
            o.put("after", after);
            if (afterMax > 0) o.put("afterMax", afterMax);
            if (isImage()) {
                o.put("tpl", tpl == null ? "" : tpl);
                o.put("timeoutSec", timeoutSec);
                o.put("skipOnTimeout", skipOnTimeout);
                o.put("threshold", threshold);
                o.put("act", act);
                o.put("tapFound", tapFound);
            }
            if (path != null && path.size() > 2) {
                JSONArray a = new JSONArray();
                for (float[] p : path) { a.put((double) p[0]); a.put((double) p[1]); }
                o.put("path", a);
            }
            return o;
        }

        static Step fromJson(JSONObject o) {
            Step s = new Step();
            s.type = Math.max(0, Math.min(5, o.optInt("type", TAP)));
            s.x = (float) o.optDouble("x", 0); s.y = (float) o.optDouble("y", 0);
            s.x2 = (float) o.optDouble("x2", s.x); s.y2 = (float) o.optDouble("y2", s.y);
            s.duration = Math.max(1, o.optInt("duration", 60));
            s.after = Math.max(0, o.optInt("after", 500));
            s.afterMax = Math.max(0, o.optInt("afterMax", 0));
            String t = o.optString("tpl", "");
            s.tpl = t.isEmpty() ? null : t.replaceAll("[^A-Za-z0-9_-]", "");
            s.timeoutSec = Math.max(1, o.optInt("timeoutSec", 30));
            s.skipOnTimeout = o.optBoolean("skipOnTimeout", false);
            s.threshold = Math.max(50, Math.min(99, o.optInt("threshold", 85)));
            s.act = Math.max(0, Math.min(2, o.optInt("act", ACT_NONE)));
            s.tapFound = o.optBoolean("tapFound", false);
            JSONArray a = o.optJSONArray("path");
            if (a != null && a.length() >= 6) {
                s.path = new ArrayList<>();
                for (int i = 0; i + 1 < a.length(); i += 2)
                    s.path.add(new float[]{(float) a.optDouble(i), (float) a.optDouble(i + 1)});
            }
            return s;
        }
    }

    // ---------------- 場面モード（この画面が見えたら、これをする） ----------------

    public static final int MODE_SEQ = 0, MODE_RULES = 1, MODE_FLOW = 2;
    public static final String[] MODE_NAMES = {"座標型（決まった位置を順に押す）", "場面型（見えた画面に応じて動く）", "流れ型（画像を目印に順番に進む）"};

    // 流れ型の手順の種類
    public static final int K_IMAGE = 0;  // 画像が出たら、押す（または確認だけ・停止）
    public static final int K_FILL = 1;   // 次の画像が出るまで、範囲の中を操作し続ける
    public static final String[] IMG_ACT_NAMES = {"その画像の中をタップ", "決めた範囲の中をタップ", "押さずに次へ（出たことの確認だけ）", "実行を止める"};
    public static final int[] IMG_ACTS = {R_TAP_IMAGE_, R_TAP_AREA_, R_WAIT_, R_STOP_};
    public static final String[] FILL_ACT_NAMES = {"範囲の中をランダムにスワイプ", "範囲の中をランダムにタップ", "何もせず待つ"};
    public static final int[] FILL_ACTS = {R_SWIPE_AREA_, R_TAP_AREA_, R_WAIT_};
    public static final int R_TAP_IMAGE = 0, R_TAP_AREA = 1, R_SWIPE_AREA = 2, R_WAIT = 3, R_STOP = 4;
    public static final String[] R_NAMES = {"その画像の中をタップ", "決めた範囲の中をタップ", "決めた範囲の中をスワイプ",
            "何もしない（待つ）", "実行を止める"};
    public static final String[] DIR_NAMES = {"向きはランダム", "上へ", "下へ", "左へ", "右へ"};
    public static final int[] LEVELS = {70, 82, 92};
    public static final String[] LEVEL_NAMES = {"ゆるめ（少し違っても同じとみなす）", "ふつう", "きびしめ（ほぼ同じときだけ）"};

    public static class Rule {
        public String name = "";
        public String tpl;            // 目印の画像（「どの場面でもないとき」は null）
        public int act = R_TAP_IMAGE;
        public int al, at, ar, ab;    // 操作する範囲（画面の座標）
        public int dir = 0;           // スワイプの向き
        public int threshold = 82;    // 一致の判定（%）
        public int waitMin = 800, waitMax = 1500; // 操作のあと待つ時間（ms）この間でランダム
        public boolean countLoop;     // この場面が出るたびに「1周」と数える
        // 流れ型の手順として使うとき
        public int kind = K_IMAGE;
        public int timeoutSec = 60;       // この秒数で見つからない（FILLは続ける上限）
        public boolean skipOnTimeout;     // true=次の手順へ / false=止める
        public List<Rule> extras = new ArrayList<>(); // この手順の間の「優先ボタン」（上ほど優先）
        public boolean advance;           // 優先ボタンとして：押したら次の手順へ進む
        public int jumpTo = -1;           // 優先ボタンとして：押したらこの手順（0始まり）へ進む
        public List<String> alts = new ArrayList<>(); // 目印の画像の追加分（どれか1つが出たら）
        public boolean search;            // 見つからないとき、範囲をスワイプして探す
        public int sl, st, sr, sb, sdir = 1, searchMax = 5; // 探すときにスワイプする範囲・向き・最大回数

        public boolean hasSearchArea() { return sr - sl > 4 && sb - st > 4; }

        /** 目印の画像すべて（1枚目＋追加分） */
        public List<String> allTpls() {
            List<String> l = new ArrayList<>();
            if (tpl != null) l.add(tpl);
            l.addAll(alts);
            return l;
        }

        public boolean hasArea() { return ar - al > 4 && ab - at > 4; }

        public boolean needsArea() { return act == R_TAP_AREA || act == R_SWIPE_AREA; }

        public String actLabel() {
            String a = R_NAMES[act];
            if (act == R_SWIPE_AREA) a += "（" + DIR_NAMES[dir] + "）";
            if (needsArea() && !hasArea()) a += " ※範囲が未設定";
            return a;
        }

        /** 優先ボタンとしての説明 */
        public String extraLabel() {
            String what = act == R_TAP_IMAGE_ ? "画像の中をタップ" : act == R_TAP_AREA_ ? "決めた範囲の中をタップ"
                    : act == R_STOP_ ? "実行を止める" : "押さない";
            if (needsArea() && !hasArea()) what += " ※範囲が未設定";
            if (act == R_STOP_) return "出たら → " + what;
            if (jumpTo >= 0) return "出たら → " + what + "、手順" + (jumpTo + 1) + "へ進む";
            return "出たら → " + what + "、" + (advance ? "次の手順へ進む" : "この手順を続ける");
        }

        public static final String[] EXTRA_NAMES = {
                "タップして、次の手順へ進む", "タップして、この手順を続ける",
                "決めた範囲をタップして、次の手順へ進む", "決めた範囲をタップして、この手順を続ける",
                "実行を止める", "タップして、指定した手順へ進む（分かれ道）"};

        /** EXTRA_NAMES の選択を設定に反映 */
        public void applyExtraChoice(int w) {
            act = (w == 0 || w == 1 || w == 5) ? R_TAP_IMAGE_ : (w == 2 || w == 3) ? R_TAP_AREA_ : R_STOP_;
            advance = (w == 0 || w == 2);
            if (w != 5) jumpTo = -1;
        }

        public int extraChoice() {
            if (act == R_STOP_) return 4;
            if (jumpTo >= 0) return 5;
            return (act == R_TAP_AREA_ ? 2 : 0) + (advance ? 0 : 1);
        }

        /** 流れ型の手順としての説明 */
        public String flowLabel() {
            if (kind == K_FILL) {
                int a = act;
                String what = a == R_SWIPE_AREA_ ? "範囲の中をランダムにスワイプ" + (dir > 0 ? "（" + DIR_NAMES[dir] + "）" : "")
                        : a == R_TAP_AREA_ ? "範囲の中をランダムにタップ" : "何もせず待つ";
                if (needsArea() && !hasArea()) what += " ※範囲が未設定";
                return "次の画像が出るまで → " + what;
            }
            String what = act == R_TAP_IMAGE_ ? "画像の中をタップ" : act == R_TAP_AREA_ ? "決めた範囲の中をタップ"
                    : act == R_STOP_ ? "実行を止める" : "押さずに次へ";
            if (needsArea() && !hasArea()) what += " ※範囲が未設定";
            String imgs = alts.isEmpty() ? "画像" : "画像" + (alts.size() + 1) + "枚のどれか";
            String srch = search && hasSearchArea() ? "（見つからなければスワイプして探す・最大" + searchMax + "回）" : "";
            return imgs + "が出たら → " + what + srch;
        }

        /** ランダムの中身を一言で（一覧に出す） */
        public String randomLabel() {
            String pos;
            switch (act) {
                case R_TAP_IMAGE: pos = "押す位置：画像の中央寄りでランダム"; break;
                case R_TAP_AREA: pos = "押す位置：範囲内のどこでもランダム"; break;
                case R_SWIPE_AREA: pos = "スワイプ：位置・長さ・曲がり・速さ(0.2〜0.6秒)がランダム"; break;
                default: pos = null;
            }
            int ti = Ui.tempoIndex(waitMin, waitMax);
            String wait = "待ち：" + secText(waitMin) + "〜" + secText(waitMax) + "秒"
                    + (ti >= 0 ? "（" + Ui.TEMPO_NAMES[ti] + "）" : "") + (waitMax > waitMin ? "でランダム" : "で固定");
            return pos == null ? wait : pos + "\n" + wait;
        }

        static String secText(int ms) {
            String t = String.valueOf(ms / 1000.0);
            return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
        }

        public String levelLabel() {
            for (int i = 0; i < LEVELS.length; i++) if (threshold == LEVELS[i]) return LEVEL_NAMES[i].replaceAll("（.*", "");
            return threshold + "%";
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("tpl", tpl == null ? "" : tpl);
            o.put("act", act);
            o.put("al", al); o.put("at", at); o.put("ar", ar); o.put("ab", ab);
            o.put("dir", dir);
            o.put("threshold", threshold);
            o.put("waitMin", waitMin); o.put("waitMax", waitMax);
            o.put("countLoop", countLoop);
            o.put("kind", kind);
            o.put("timeoutSec", timeoutSec);
            o.put("skipOnTimeout", skipOnTimeout);
            o.put("advance", advance);
            o.put("jumpTo", jumpTo);
            if (!alts.isEmpty()) o.put("alts", new JSONArray(alts));
            o.put("search", search);
            o.put("sl", sl); o.put("st", st); o.put("sr", sr); o.put("sb", sb);
            o.put("sdir", sdir); o.put("searchMax", searchMax);
            if (!extras.isEmpty()) {
                JSONArray ea = new JSONArray();
                for (Rule e : extras) ea.put(e.toJson());
                o.put("extras", ea);
            }
            return o;
        }

        static Rule fromJson(JSONObject o) {
            Rule r = new Rule();
            r.name = o.optString("name", "");
            String t = o.optString("tpl", "");
            r.tpl = t.isEmpty() ? null : t.replaceAll("[^A-Za-z0-9_-]", "");
            r.act = Math.max(0, Math.min(4, o.optInt("act", R_TAP_IMAGE)));
            r.al = o.optInt("al"); r.at = o.optInt("at"); r.ar = o.optInt("ar"); r.ab = o.optInt("ab");
            r.dir = Math.max(0, Math.min(4, o.optInt("dir", 0)));
            r.threshold = Math.max(50, Math.min(99, o.optInt("threshold", 82)));
            r.waitMin = Math.max(0, o.optInt("waitMin", 800));
            r.waitMax = Math.max(r.waitMin, o.optInt("waitMax", 1500));
            r.countLoop = o.optBoolean("countLoop", false);
            r.kind = o.optInt("kind", K_IMAGE) == K_FILL ? K_FILL : K_IMAGE;
            r.timeoutSec = Math.max(1, o.optInt("timeoutSec", 60));
            r.skipOnTimeout = o.optBoolean("skipOnTimeout", false);
            r.advance = o.optBoolean("advance", false);
            r.jumpTo = o.optInt("jumpTo", -1);
            JSONArray aa = o.optJSONArray("alts");
            if (aa != null) for (int i = 0; i < aa.length(); i++) {
                String a = aa.optString(i, "").replaceAll("[^A-Za-z0-9_-]", "");
                if (!a.isEmpty()) r.alts.add(a);
            }
            r.search = o.optBoolean("search", false);
            r.sl = o.optInt("sl"); r.st = o.optInt("st"); r.sr = o.optInt("sr"); r.sb = o.optInt("sb");
            r.sdir = Math.max(0, Math.min(4, o.optInt("sdir", 1)));
            r.searchMax = Math.max(1, Math.min(50, o.optInt("searchMax", 5)));
            JSONArray ea = o.optJSONArray("extras");
            if (ea != null) for (int i = 0; i < ea.length(); i++) {
                JSONObject eo = ea.optJSONObject(i);
                if (eo != null) r.extras.add(Rule.fromJson(eo));
            }
            return r;
        }
    }

    public int mode = MODE_SEQ;
    public List<Rule> rules = new ArrayList<>();   // 場面型の場面／流れ型では「割り込み」
    public List<Rule> flow = new ArrayList<>();    // 流れ型の手順（上から順番）
    public Rule other = newOther();   // どの場面でもないときの操作
    public int stuckSec = 0;          // どの場面も見つからない状態がこの秒数続いたら止める（0=止めない）

    // テンポのゆらぎ（人の操作に近づける）
    public int paceMode = 1;   // 待ち時間の散らし方 0=均等 1=人っぽく（短めが多く、たまに長め）
    public int pauseLevel = 1; // たまに手を止める 0=なし 1=ときどき 2=よく
    public int breakLevel = 0; // 長めの休憩 0=なし 1=15〜30分ごとに1〜3分 2=30〜60分ごとに3〜8分
    public int tapHold = 1;    // 押す長さ 0=一定 1=ばらつく
    public static final String[] PACE_NAMES = {"均等にばらつく", "人っぽく（短めが多く、たまに長め）"};
    public static final String[] PAUSE_NAMES = {"なし", "ときどき", "よく"};
    public static final String[] BREAK_NAMES = {"なし", "15〜30分ごと", "30〜60分ごと"};
    public static final String[] HOLD_NAMES = {"ほぼ一定", "ばらつく"};

    private static Rule newOther() {
        Rule r = new Rule();
        r.name = "どの場面でもないとき";
        r.act = R_WAIT;
        r.waitMin = 400;
        r.waitMax = 800;
        return r;
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
        o.put("mode", mode);
        JSONArray ra = new JSONArray();
        for (Rule r : rules) ra.put(r.toJson());
        o.put("rules", ra);
        o.put("other", other.toJson());
        o.put("stuckSec", stuckSec);
        o.put("paceMode", paceMode); o.put("pauseLevel", pauseLevel);
        o.put("breakLevel", breakLevel); o.put("tapHold", tapHold);
        JSONArray fa = new JSONArray();
        for (Rule r : flow) fa.put(r.toJson());
        o.put("flow", fa);
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
        int md = o.optInt("mode", MODE_SEQ);
        sc.mode = (md == MODE_RULES || md == MODE_FLOW) ? md : MODE_SEQ;
        JSONArray fa = o.optJSONArray("flow");
        if (fa != null) for (int i = 0; i < fa.length(); i++) {
            JSONObject fo = fa.optJSONObject(i);
            if (fo != null) sc.flow.add(Rule.fromJson(fo));
        }
        JSONArray ra = o.optJSONArray("rules");
        if (ra != null) for (int i = 0; i < ra.length(); i++) {
            JSONObject ro = ra.optJSONObject(i);
            if (ro != null) sc.rules.add(Rule.fromJson(ro));
        }
        JSONObject oo = o.optJSONObject("other");
        if (oo != null) {
            sc.other = Rule.fromJson(oo);
            sc.other.tpl = null;
            if (sc.other.act == R_TAP_IMAGE) sc.other.act = R_WAIT;
            sc.other.name = "どの場面でもないとき";
        }
        sc.stuckSec = Math.max(0, o.optInt("stuckSec", 0));
        sc.paceMode = o.optInt("paceMode", 1) == 0 ? 0 : 1;
        sc.pauseLevel = Math.max(0, Math.min(2, o.optInt("pauseLevel", 1)));
        sc.breakLevel = Math.max(0, Math.min(2, o.optInt("breakLevel", 0)));
        sc.tapHold = o.optInt("tapHold", 1) == 0 ? 0 : 1;
        return sc;
    }

    /** このシナリオが使っているお手本画像のID */
    public java.util.Set<String> templateIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Step st : steps) if (st.tpl != null) ids.add(st.tpl);
        for (Rule r : rules) if (r.tpl != null) ids.add(r.tpl);
        for (Rule r : flow) {
            if (r.tpl != null) ids.add(r.tpl);
            ids.addAll(r.alts);
            for (Rule e : r.extras) if (e.tpl != null) ids.add(e.tpl);
        }
        return ids;
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
        if (mode == MODE_FLOW) {
            String r = repeat == 0 ? "止めるまで" : repeat + "周";
            return "手順" + flow.size() + "個" + (rules.isEmpty() ? "" : "・割り込み" + rules.size() + "個") + "・" + r
                    + (maxMinutes > 0 ? "・最大" + maxMinutes + "分" : "");
        }
        if (mode == MODE_RULES) {
            String r = repeat == 0 ? "止めるまで" : repeat + "周";
            return "場面" + rules.size() + "個・" + r + (maxMinutes > 0 ? "・最大" + maxMinutes + "分" : "");
        }
        return seqSummary();
    }

    private String seqSummary() {
        int n = 0, img = 0;
        for (Step s : steps) { if (s.type != WAIT) n++; if (s.isImage()) img++; }
        String r = repeat == 0 ? "止めるまで" : repeat + "回";
        return n + "手順" + (img > 0 ? "（画像" + img + "）" : "") + "・" + r + (maxMinutes > 0 ? "・最大" + maxMinutes + "分" : "")
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
