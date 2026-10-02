package com.rerise.tapsuke;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 場面モードのくわしい設定：止め方、場面ごとの判定・待ち時間・名前など */
public class RulesActivity extends Activity {

    private Scenario sc;
    private LinearLayout listBox;
    private EditText name, repeat, maxMin, stuck;

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

        root.addView(Ui.text(this, "くわしい設定", 22, true));
        root.addView(Ui.subText(this, "場面の追加や、目印・範囲の取り直しは、対象のアプリの上でパネルの「＋場面」「一覧」から行います。"),
                Ui.mw(this, 4));

        name = new EditText(this);
        name.setText(sc.name);
        name.setTextColor(Ui.fg(this));
        root.addView(Ui.subText(this, "シナリオの名前"), Ui.mw(this, 12));
        root.addView(name, Ui.mw());

        LinearLayout set = Ui.vbox(this);
        set.addView(Ui.text(this, "いつ止めるか", 16, true));
        repeat = Ui.number(this, sc.repeat);
        set.addView(Ui.field(this, "この周回数で止める（0＝止めない）", repeat, "周"));
        set.addView(Ui.subText(this, "周回は、下の場面で「1周として数える」にチェックした場面が出るたびに数えます。"));
        maxMin = Ui.number(this, sc.maxMinutes);
        set.addView(Ui.field(this, "この時間で止める（0＝止めない）", maxMin, "分"), Ui.mw(this, 6));
        stuck = Ui.number(this, sc.stuckSec);
        set.addView(Ui.field(this, "どの場面も見つからないまま続いたら止める（0＝止めない）", stuck, "秒"), Ui.mw(this, 6));
        set.addView(Ui.subText(this, "固まった・想定外の画面になった、を見つけるための設定です。クエストの長さより長めにしてください。"));
        root.addView(Ui.cardView(this, set), Ui.mw(this, 14));

        root.addView(Ui.text(this, "場面（上にあるものを優先）", 16, true), Ui.mw(this, 16));
        root.addView(Ui.subText(this, "行をタップすると、名前・判定のゆるさ・待ち時間などを変えられます。"));
        listBox = Ui.vbox(this);
        root.addView(listBox, Ui.mw(this, 6));

        root.addView(Ui.primary(this, "保存して閉じる", v -> { save(); finish(); }), Ui.mw(this, 20));
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sc != null) save();
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (!n.isEmpty()) sc.name = n;
        sc.repeat = Math.max(0, Ui.parse(repeat, sc.repeat));
        sc.maxMinutes = Math.max(0, Ui.parse(maxMin, sc.maxMinutes));
        sc.stuckSec = Math.max(0, Ui.parse(stuck, sc.stuckSec));
        sc.save(this);
        Templates.cleanup(this);
        if (TapService.isOn()) TapService.get().reload(sc.id);
    }

    private static String sec(int ms) {
        String s = String.valueOf(ms / 1000.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private void render() {
        listBox.removeAllViews();
        if (sc.rules.isEmpty()) {
            listBox.addView(Ui.subText(this, "まだ場面がありません。対象のアプリを開いて、パネルの「＋場面」から追加してください。"));
        }
        for (int i = 0; i <= sc.rules.size(); i++) {
            final int idx = i;
            final boolean isOther = i == sc.rules.size();
            final Scenario.Rule r = isOther ? sc.other : sc.rules.get(i);
            LinearLayout row = Ui.hbox(this);
            if (!isOther) {
                ImageView iv = new ImageView(this);
                Bitmap bm = Templates.bitmap(this, r.tpl);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
                ip.rightMargin = Ui.dp(this, 10);
                row.addView(iv, ip);
            }
            LinearLayout txt = Ui.vbox(this);
            txt.addView(Ui.text(this, isOther ? "どの場面でもないとき（クエスト中など）" : (i + 1) + ". " + r.name, 15, true));
            txt.addView(Ui.subText(this, "→ " + r.actLabel()));
            String sub = "そのあと " + sec(r.waitMin) + "〜" + sec(r.waitMax) + "秒待つ";
            if (!isOther) sub += "・判定 " + r.levelLabel() + (r.countLoop ? "・1周として数える" : "");
            txt.addView(Ui.subText(this, sub));
            row.addView(txt, Ui.weight(1));
            if (!isOther) {
                row.addView(small("↑", v -> move(idx, -1)));
                row.addView(small("↓", v -> move(idx, 1)));
            }
            int p = Ui.dp(this, 10);
            row.setPadding(p, p, p / 2, p);
            row.setBackground(Ui.round(Ui.card(this), Ui.dp(this, 10)));
            row.setOnClickListener(v -> edit(r, isOther, idx));
            listBox.addView(row, Ui.mw(this, isOther ? 14 : 6));
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

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= sc.rules.size()) return;
        Scenario.Rule t = sc.rules.get(i);
        sc.rules.set(i, sc.rules.get(j));
        sc.rules.set(j, t);
        render();
    }

    private EditText decimal(int ms) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(sec(ms));
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.fg(this));
        return e;
    }

    private static int parseMs(EditText e, int def) {
        try {
            return (int) Math.round(Double.parseDouble(e.getText().toString().trim()) * 1000);
        } catch (Exception ex) {
            return def;
        }
    }

    private RadioGroup radios(String[] labels, int checked) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Ui.fg(this));
            rb.setId(1000 + i);
            g.addView(rb);
        }
        if (checked >= 0 && checked < labels.length) g.check(1000 + checked);
        return g;
    }

    private void edit(final Scenario.Rule r, final boolean isOther, final int idx) {
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final EditText nm = new EditText(this);
        nm.setText(r.name);
        nm.setTextColor(Ui.fg(this));
        if (!isOther) {
            box.addView(Ui.subText(this, "名前（分かりやすいものに）"));
            box.addView(nm);
        }

        // やること（範囲が必要なものへ変えるときは、パネルの「一覧」から変える）
        final int[] acts = isOther
                ? new int[]{Scenario.R_SWIPE_AREA, Scenario.R_TAP_AREA, Scenario.R_WAIT}
                : new int[]{0, 1, 2, 3, 4};
        String[] actNames = new String[acts.length];
        int cur = 0;
        for (int i = 0; i < acts.length; i++) {
            actNames[i] = Scenario.R_NAMES[acts[i]];
            if (acts[i] == r.act) cur = i;
        }
        box.addView(Ui.text(this, "やること", 15, true), Ui.mw(this, 10));
        final RadioGroup actG = radios(actNames, cur);
        box.addView(actG);
        if (!r.hasArea()) box.addView(Ui.subText(this, "※「範囲の中を…」を選んだら、パネルの「一覧」→この場面→「操作する範囲を決め直す」で範囲を囲んでください。"));

        box.addView(Ui.text(this, "スワイプの向き", 15, true), Ui.mw(this, 10));
        final RadioGroup dirG = radios(Scenario.DIR_NAMES, r.dir);
        dirG.setOrientation(RadioGroup.VERTICAL);
        box.addView(dirG);

        int lv = -1;
        for (int i = 0; i < Scenario.LEVELS.length; i++) if (Scenario.LEVELS[i] == r.threshold) lv = i;
        final RadioGroup lvG = radios(Scenario.LEVEL_NAMES, lv);
        final EditText thr = Ui.number(this, r.threshold);
        final CheckBox loop = new CheckBox(this);
        if (!isOther) {
            box.addView(Ui.text(this, "画面の判定", 15, true), Ui.mw(this, 10));
            box.addView(lvG);
            box.addView(Ui.field(this, "数字で決める（上を選ぶと上書き）", thr, "%"));
            lvG.setOnCheckedChangeListener((g, id) -> {
                int k = id - 1000;
                if (k >= 0 && k < Scenario.LEVELS.length) thr.setText(String.valueOf(Scenario.LEVELS[k]));
            });
            box.addView(Ui.subText(this, "パネルの「確認」で、いまの画面が何%一致しているかを見られます。"));
        }

        box.addView(Ui.text(this, "操作のあと待つ時間（この間でランダム）", 15, true), Ui.mw(this, 10));
        final EditText wMin = decimal(r.waitMin), wMax = decimal(r.waitMax);
        box.addView(Ui.field(this, "最短", wMin, "秒"));
        box.addView(Ui.field(this, "最長", wMax, "秒"));

        if (!isOther) {
            loop.setText("この場面が出るたびに「1周」と数える");
            loop.setTextColor(Ui.fg(this));
            loop.setChecked(r.countLoop);
            box.addView(loop, Ui.mw(this, 8));
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isOther ? "どの場面でもないとき" : (idx + 1) + ". " + r.name)
                .setView(sv)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    if (!isOther) {
                        String n = nm.getText().toString().trim();
                        if (!n.isEmpty()) r.name = n;
                        r.threshold = Math.max(50, Math.min(99, Ui.parse(thr, r.threshold)));
                        r.countLoop = loop.isChecked();
                    }
                    int ai = actG.getCheckedRadioButtonId() - 1000;
                    if (ai >= 0 && ai < acts.length) r.act = acts[ai];
                    int di = dirG.getCheckedRadioButtonId() - 1000;
                    if (di >= 0 && di < Scenario.DIR_NAMES.length) r.dir = di;
                    r.waitMin = Math.max(0, Math.min(3_600_000, parseMs(wMin, r.waitMin)));
                    r.waitMax = Math.max(r.waitMin, Math.min(3_600_000, parseMs(wMax, r.waitMax)));
                    render();
                });
        if (!isOther) b.setNeutralButton("削除", (d, w) -> { sc.rules.remove(r); render(); });
        b.show();
    }
}
