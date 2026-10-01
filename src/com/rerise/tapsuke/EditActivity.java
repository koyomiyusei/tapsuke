package com.rerise.tapsuke;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** シナリオの細かい編集：名前・繰り返し・ランダム化・各手順の数値 */
public class EditActivity extends Activity {

    private Scenario sc;
    private LinearLayout stepsBox;
    private EditText name, repeat, maxMin, loopWait, jitPx, jitPct;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sc = Scenario.load(this, getIntent().getStringExtra("id"));
        if (sc == null) {
            Toast.makeText(this, "シナリオが見つかりません", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
            return ins;
        });

        root.addView(Ui.text(this, "シナリオの編集", 22, true));

        name = new EditText(this);
        name.setText(sc.name);
        name.setTextColor(Ui.fg(this));
        root.addView(Ui.subText(this, "名前"), Ui.mw(this, 12));
        root.addView(name, Ui.mw());

        // 全体設定
        LinearLayout set = Ui.vbox(this);
        set.addView(Ui.text(this, "くり返しと停止", 16, true));
        repeat = Ui.number(this, sc.repeat);
        set.addView(Ui.field(this, "くり返す回数（0＝止めるまで）", repeat, "回"));
        maxMin = Ui.number(this, sc.maxMinutes);
        set.addView(Ui.field(this, "この時間で自動停止（0＝なし）", maxMin, "分"));
        loopWait = Ui.number(this, sc.loopWait);
        set.addView(Ui.field(this, "1周ごとの追加の待ち", loopWait, "ms"));
        set.addView(Ui.text(this, "ランダム化（人の操作に近づける）", 16, true), Ui.mw(this, 12));
        jitPx = Ui.number(this, sc.jitterPx);
        set.addView(Ui.field(this, "位置のズレ（半径）", jitPx, "px"));
        jitPct = Ui.number(this, sc.jitterPct);
        set.addView(Ui.field(this, "間隔のズレ（±）", jitPct, "%"));
        root.addView(Ui.cardView(this, set), Ui.mw(this, 14));

        // 手順
        LinearLayout head = Ui.hbox(this);
        head.addView(Ui.text(this, "手順", 16, true), Ui.weight(1));
        head.addView(Ui.button(this, "待ち時間を一括", v -> bulkAfter()));
        root.addView(head, Ui.mw(this, 16));
        root.addView(Ui.subText(this, "位置はパネルのマーカーをドラッグして合わせるのが簡単です。行をタップすると数値で直せます。"
                + "画像の手順は、パネルの「◫」で画面から登録します。"));
        stepsBox = Ui.vbox(this);
        root.addView(stepsBox, Ui.mw(this, 6));

        LinearLayout adds = Ui.hbox(this);
        adds.addView(Ui.button(this, "＋タップ", v -> add(Scenario.TAP)), Ui.weight(1));
        adds.addView(Ui.button(this, "＋長押し", v -> add(Scenario.LONG)), Ui.weight(1));
        root.addView(adds, Ui.mw(this, 8));
        LinearLayout adds2 = Ui.hbox(this);
        adds2.addView(Ui.button(this, "＋スワイプ", v -> add(Scenario.SWIPE)), Ui.weight(1));
        adds2.addView(Ui.button(this, "＋待機", v -> add(Scenario.WAIT)), Ui.weight(1));
        root.addView(adds2, Ui.mw());

        root.addView(Ui.primary(this, "保存して閉じる", v -> { save(); finish(); }), Ui.mw(this, 20));

        renderSteps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sc != null) save();
    }

    private void readFields() {
        String n = name.getText().toString().trim();
        if (!n.isEmpty()) sc.name = n;
        sc.repeat = Math.max(0, Ui.parse(repeat, sc.repeat));
        sc.maxMinutes = Math.max(0, Ui.parse(maxMin, sc.maxMinutes));
        sc.loopWait = Math.max(0, Ui.parse(loopWait, sc.loopWait));
        sc.jitterPx = Math.max(0, Math.min(500, Ui.parse(jitPx, sc.jitterPx)));
        sc.jitterPct = Math.max(0, Math.min(90, Ui.parse(jitPct, sc.jitterPct)));
    }

    private void save() {
        readFields();
        sc.save(this);
        Templates.cleanup(this);
        if (TapService.isOn()) TapService.get().reload(sc.id);
    }

    private void renderSteps() {
        stepsBox.removeAllViews();
        if (sc.steps.isEmpty()) {
            stepsBox.addView(Ui.subText(this, "手順はまだありません。"));
            return;
        }
        for (int i = 0; i < sc.steps.size(); i++) {
            final int idx = i;
            Scenario.Step s = sc.steps.get(i);
            LinearLayout row = Ui.hbox(this);
            if (s.isImage()) {
                ImageView iv = new ImageView(this);
                android.graphics.Bitmap bm = Templates.bitmap(this, s.tpl);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 40));
                ip.rightMargin = Ui.dp(this, 8);
                row.addView(iv, ip);
            }
            LinearLayout txt = Ui.vbox(this);
            txt.addView(Ui.text(this, (i + 1) + ". " + s.label(), 14, false));
            String w = s.afterMax > s.after ? s.after + "〜" + s.afterMax + "ms" : s.after + "ms";
            String sub = s.type == Scenario.WAIT ? "待つ " + w
                    : s.type == Scenario.UNTIL && s.act != Scenario.ACT_NONE ? "くり返しの間隔 " + w
                    : "そのあと " + w;
            if (s.isImage()) sub += "・一致" + s.threshold + "%以上・見つからなければ" + (s.skipOnTimeout ? "次へ" : "停止");
            txt.addView(Ui.subText(this, sub));
            txt.setOnClickListener(v -> editStep(idx));
            row.addView(txt, Ui.weight(1));
            row.addView(small("⧉", v -> duplicate(idx)));
            row.addView(small("↑", v -> move(idx, -1)));
            row.addView(small("↓", v -> move(idx, 1)));
            row.addView(small("✕", v -> remove(idx)));
            int p = Ui.dp(this, 8);
            row.setPadding(p, p, 0, p);
            row.setBackground(Ui.round(Ui.card(this), Ui.dp(this, 8)));
            stepsBox.addView(row, Ui.mw(this, 4));
        }
    }

    private View small(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 18, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 40));
        t.setMinHeight(Ui.dp(this, 40));
        t.setOnClickListener(l);
        return t;
    }

    private void duplicate(int i) {
        sc.steps.add(i + 1, sc.steps.get(i).copy());
        renderSteps();
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= sc.steps.size()) return;
        Scenario.Step t = sc.steps.get(i);
        sc.steps.set(i, sc.steps.get(j));
        sc.steps.set(j, t);
        renderSteps();
    }

    private void remove(int i) {
        sc.steps.remove(i);
        renderSteps();
    }

    private void add(int type) {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Scenario.Step s = new Scenario.Step();
        s.type = type;
        s.x = dm.widthPixels / 2f;
        s.y = dm.heightPixels * 0.45f;
        if (type == Scenario.LONG) s.duration = 800;
        if (type == Scenario.SWIPE) {
            s.y = dm.heightPixels * 0.70f;
            s.x2 = s.x;
            s.y2 = dm.heightPixels * 0.30f;
            s.duration = 300;
            s.after = 800;
        }
        if (type == Scenario.WAIT) s.after = 1000;
        if (sc.screenW <= 0) { sc.screenW = dm.widthPixels; sc.screenH = dm.heightPixels; }
        sc.steps.add(s);
        renderSteps();
        editStep(sc.steps.size() - 1);
    }

    private void editStep(final int i) {
        final Scenario.Step s = sc.steps.get(i);
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText x = Ui.number(this, (int) s.x), y = Ui.number(this, (int) s.y);
        final EditText x2 = Ui.number(this, (int) s.x2), y2 = Ui.number(this, (int) s.y2);
        final EditText dur = Ui.number(this, s.duration), after = Ui.number(this, s.after);
        final EditText afterMax = Ui.number(this, Math.max(s.after, s.afterMax));
        final EditText timeout = Ui.number(this, s.timeoutSec), thr = Ui.number(this, s.threshold);
        final CheckBox skip = new CheckBox(this), tapFound = new CheckBox(this);
        if (s.isImage()) {
            box.addView(Ui.field(this, "最大で待つ", timeout, "秒"));
            box.addView(Ui.field(this, "一致の判定", thr, "%"));
            skip.setText("見つからなければ次の手順へ（オフ＝停止）");
            skip.setTextColor(Ui.fg(this));
            skip.setChecked(s.skipOnTimeout);
            box.addView(skip);
            if (s.type == Scenario.UNTIL) {
                tapFound.setText("出てきた画像もタップする");
                tapFound.setTextColor(Ui.fg(this));
                tapFound.setChecked(s.tapFound);
                box.addView(tapFound);
            }
        }
        if (s.hasPoint()) {
            box.addView(Ui.field(this, s.type == Scenario.SWIPE ? "始点 X" : "X", x, "px"));
            box.addView(Ui.field(this, s.type == Scenario.SWIPE ? "始点 Y" : "Y", y, "px"));
        }
        if (s.isSwipeLike()) {
            box.addView(Ui.field(this, "終点 X", x2, "px"));
            box.addView(Ui.field(this, "終点 Y", y2, "px"));
        }
        if (s.type == Scenario.TAP || s.type == Scenario.LONG) box.addView(Ui.field(this, "押す時間", dur, "ms"));
        if (s.isSwipeLike()) box.addView(Ui.field(this, "動かす時間", dur, "ms"));
        String wl = s.type == Scenario.WAIT ? "待つ時間" : (s.type == Scenario.UNTIL && s.act != Scenario.ACT_NONE) ? "くり返しの間隔" : "そのあと待つ";
        box.addView(Ui.field(this, wl + "（最短）", after, "ms"));
        box.addView(Ui.field(this, wl + "（最長）", afterMax, "ms"));
        box.addView(Ui.subText(this, "最短〜最長の間でランダムに待ちます。同じにすると全体設定の「間隔のズレ」を使います。"));
        if (s.type == Scenario.SWIPE && s.path != null)
            box.addView(Ui.subText(this, "記録した軌跡があります。座標を変えると直線に置き換わります。"));

        ScrollView dsv = new ScrollView(this);
        dsv.addView(box);
        new AlertDialog.Builder(this).setTitle((i + 1) + ". " + Scenario.TYPE_NAMES[s.type]).setView(dsv)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    int nx = Ui.parse(x, (int) s.x), ny = Ui.parse(y, (int) s.y);
                    int nx2 = Ui.parse(x2, (int) s.x2), ny2 = Ui.parse(y2, (int) s.y2);
                    if (s.path != null && (nx != (int) s.x || ny != (int) s.y || nx2 != (int) s.x2 || ny2 != (int) s.y2))
                        s.path = null;
                    if (nx != (int) s.x) s.x = nx;
                    if (ny != (int) s.y) s.y = ny;
                    if (nx2 != (int) s.x2) s.x2 = nx2;
                    if (ny2 != (int) s.y2) s.y2 = ny2;
                    s.duration = Math.max(1, Math.min(60000, Ui.parse(dur, s.duration)));
                    s.after = Math.max(0, Math.min(3_600_000, Ui.parse(after, s.after)));
                    int mx = Math.max(0, Math.min(3_600_000, Ui.parse(afterMax, s.afterMax)));
                    s.afterMax = mx > s.after ? mx : 0;
                    if (s.isImage()) {
                        s.timeoutSec = Math.max(1, Math.min(86400, Ui.parse(timeout, s.timeoutSec)));
                        s.threshold = Math.max(50, Math.min(99, Ui.parse(thr, s.threshold)));
                        s.skipOnTimeout = skip.isChecked();
                        if (s.type == Scenario.UNTIL) s.tapFound = tapFound.isChecked();
                    }
                    renderSteps();
                }).show();
    }

    private void bulkAfter() {
        final EditText e = Ui.number(this, 500);
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(Ui.field(this, "すべての「そのあと待つ」", e, "ms"));
        new AlertDialog.Builder(this).setTitle("待ち時間を一括変更").setView(box)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    int v = Math.max(0, Ui.parse(e, 500));
                    for (Scenario.Step s : sc.steps) if (s.type != Scenario.WAIT) s.after = v;
                    renderSteps();
                }).show();
    }
}
