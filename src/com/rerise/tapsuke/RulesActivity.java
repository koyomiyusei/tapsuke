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
        final boolean flow = sc.mode == Scenario.MODE_FLOW;
        root.addView(Ui.subText(this, flow
                        ? "手順や優先ボタンの追加、画像・範囲の取り直し、並べ替えは、対象のアプリの上でパネルの「＋手順」「手順一覧」から行います。"
                        : "場面の追加や、目印・範囲の取り直しは、対象のアプリの上でパネルの「＋場面」「場面一覧」から行います。"),
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
        set.addView(Ui.subText(this, flow ? "最後の手順まで進んで1に戻ったら1周と数えます。"
                : "周回は、下の場面で「1周として数える」にチェックした場面が出るたびに数えます。"));
        maxMin = Ui.number(this, sc.maxMinutes);
        set.addView(Ui.field(this, "この時間で止める（0＝止めない）", maxMin, "分"), Ui.mw(this, 6));
        stuck = Ui.number(this, sc.stuckSec);
        View stuckTitle = Ui.text(this, "どの場面も見つからないまま続いたら止める", 15, false);
        set.addView(stuckTitle, Ui.mw(this, 10));
        final int[] stuckVals = {0, 60, 180, 600};
        int si = -1;
        for (int i = 0; i < stuckVals.length; i++) if (stuckVals[i] == sc.stuckSec) si = i;
        set.addView(Ui.chips(this, new String[]{"止めない", "1分", "3分", "10分"}, si,
                k -> stuck.setText(String.valueOf(stuckVals[k]))), Ui.mw(this, 4));
        set.addView(Ui.field(this, "数字で決める（0＝止めない）", stuck, "秒"));
        View stuckNote = Ui.subText(this, "固まった・想定外の画面になった、を見つけるための設定です。クエストの長さより長めにしてください。");
        set.addView(stuckNote);
        if (flow) {
            // 流れ型は、手順ごとの「最大で待つ時間」で止まるので使わない
            for (int i = set.getChildCount() - 1; i >= 0; i--) {
                View c = set.getChildAt(i);
                set.removeView(c);
                if (c == stuckTitle) break;
            }
            set.addView(Ui.subText(this, "固まったときは、各手順の「最大で待つ時間」を過ぎた時点で止まります。"), Ui.mw(this, 8));
        }
        root.addView(Ui.cardView(this, set), Ui.mw(this, 14));
        root.addView(Ui.paceCard(this, sc), Ui.mw(this, 12));

        root.addView(Ui.button(this, flow ? "すべての手順のテンポをそろえる" : "すべての場面のテンポをそろえる", v -> bulkTempo()), Ui.mw(this, 12));
        root.addView(Ui.text(this, flow ? "手順（上から順番に進む）" : "場面（上にあるものを優先）", 16, true), Ui.mw(this, 16));
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
        if (sc.mode == Scenario.MODE_FLOW) { renderFlow(); return; }
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
                Bitmap bm = Templates.thumb(this, r.tpl);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
                ip.rightMargin = Ui.dp(this, 10);
                row.addView(iv, ip);
            }
            LinearLayout txt = Ui.vbox(this);
            txt.addView(Ui.text(this, isOther ? "どの場面でもないとき（クエスト中など）" : (i + 1) + ". " + r.name, 15, true));
            txt.addView(Ui.subText(this, "→ " + r.actLabel()));
            String sub = r.randomLabel();
            if (!isOther) sub += "\n判定：" + r.levelLabel() + (r.countLoop ? "・1周として数える" : "");
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

        // やること（範囲が必要なものへ変えるときは、パネルの「場面一覧」から変える）
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
        if (!r.hasArea()) box.addView(Ui.subText(this, "※「範囲の中を…」を選んだら、パネルの「場面一覧」→この場面→「操作する範囲を決め直す」で範囲を囲んでください。"));

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
        box.addView(Ui.chips(this, Ui.TEMPO_NAMES, Ui.tempoIndex(r.waitMin, r.waitMax), k -> {
            wMin.setText(sec(Ui.TEMPO[k][0]));
            wMax.setText(sec(Ui.TEMPO[k][1]));
        }));
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

    private void bulkTempo() {
        new AlertDialog.Builder(this).setTitle("操作のあと待つ時間をそろえる")
                .setItems(new String[]{
                        "速め（0.3〜0.7秒）", "普通（0.8〜1.5秒）", "ゆっくり（1.5〜3秒）", "かなりゆっくり（3〜6秒）"},
                        (d, k) -> {
                            java.util.List<Scenario.Rule> all = new java.util.ArrayList<>(sc.rules);
                            all.add(sc.other);
                            for (Scenario.Rule fr : sc.flow) { all.add(fr); all.addAll(fr.extras); }
                            for (Scenario.Rule r : all) { r.waitMin = Ui.TEMPO[k][0]; r.waitMax = Ui.TEMPO[k][1]; }
                            render();
                        })
                .setNegativeButton("やめる", null).show();
    }

    // ================= 流れ型 =================

    private View thumbRow(Scenario.Rule r, String title, String sub, View.OnClickListener l, int indentDp) {
        LinearLayout row = Ui.hbox(this);
        if (r.tpl != null) {
            ImageView iv = new ImageView(this);
            Bitmap bm = Templates.thumb(this, r.tpl);
            if (bm != null) iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
            ip.rightMargin = Ui.dp(this, 10);
            row.addView(iv, ip);
        }
        LinearLayout txt = Ui.vbox(this);
        txt.addView(Ui.text(this, title, 15, true));
        txt.addView(Ui.subText(this, sub));
        row.addView(txt, Ui.weight(1));
        int p = Ui.dp(this, 10);
        row.setPadding(p, p, p, p);
        row.setBackground(Ui.round(Ui.card(this), Ui.dp(this, 10)));
        row.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.mw(this, 6);
        lp.leftMargin = Ui.dp(this, indentDp);
        row.setLayoutParams(lp);
        return row;
    }

    private void renderFlow() {
        if (sc.flow.isEmpty()) listBox.addView(Ui.subText(this, "まだ手順がありません。対象のアプリを開いて、パネルの「＋手順」から追加してください。"));
        for (int i = 0; i < sc.flow.size(); i++) {
            final int idx = i;
            final Scenario.Rule r = sc.flow.get(i);
            boolean nextImg = sc.flow.size() > 1 && sc.flow.get((i + 1) % sc.flow.size()).kind == Scenario.K_IMAGE;
            String sub = r.flowLabel() + "\n" + r.randomLabel() + "\n" + timeoutText(r, nextImg)
                    + (r.kind == Scenario.K_IMAGE ? "・判定 " + r.levelLabel() : "");
            listBox.addView(thumbRow(r, (i + 1) + ". " + r.name, sub, v -> editFlow(r, idx), 0));
            for (int j = 0; j < r.extras.size(); j++) {
                final Scenario.Rule e = r.extras.get(j);
                final int ej = j;
                listBox.addView(thumbRow(e, "優先" + (j + 1) + "：" + e.name,
                        e.extraLabel() + "\n判定 " + e.levelLabel() + "・押したあと " + sec(e.waitMin) + "〜" + sec(e.waitMax) + "秒待つ",
                        v -> editExtra(r, e, ej), 24));
            }
        }
        TextView h = Ui.text(this, "割り込み（どの手順の途中でも最優先）", 16, true);
        listBox.addView(h, Ui.mw(this, 18));
        if (sc.rules.isEmpty()) listBox.addView(Ui.subText(this, "なし"));
        for (int i = 0; i < sc.rules.size(); i++) {
            final int idx = i;
            final Scenario.Rule r = sc.rules.get(i);
            listBox.addView(thumbRow(r, (i + 1) + ". " + r.name, "→ " + r.actLabel() + "\n判定 " + r.levelLabel(),
                    v -> edit(r, false, idx), 0));
        }
    }

    private static String timeoutText(Scenario.Rule r, boolean nextImg) {
        if (r.kind == Scenario.K_FILL && !nextImg) return r.timeoutSec + "秒続けたら次の手順へ";
        return "最大" + r.timeoutSec + "秒待つ・出なければ" + (r.skipOnTimeout ? "次の手順へ" : "止める");
    }

    private void editFlow(final Scenario.Rule r, final int idx) {
        final boolean img = r.kind == Scenario.K_IMAGE;
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final EditText nm = new EditText(this);
        nm.setText(r.name);
        nm.setTextColor(Ui.fg(this));
        box.addView(Ui.subText(this, "名前（分かりやすいものに）"));
        box.addView(nm);

        final String[] names = img ? Scenario.IMG_ACT_NAMES : Scenario.FILL_ACT_NAMES;
        final int[] acts = img ? Scenario.IMG_ACTS : Scenario.FILL_ACTS;
        int cur = 0;
        for (int i = 0; i < acts.length; i++) if (acts[i] == r.act) cur = i;
        box.addView(Ui.text(this, img ? "この画像が出たら" : "次の画像が出るまで", 15, true), Ui.mw(this, 10));
        final RadioGroup actG = radios(names, cur);
        box.addView(actG);
        box.addView(Ui.subText(this, "※範囲を使うものに変えたときは、パネルの「手順一覧」→この手順の「編集」→「操作する範囲を決め直す」で範囲を囲んでください。"));

        final RadioGroup dirG = radios(Scenario.DIR_NAMES, r.dir);
        if (!img) {
            box.addView(Ui.text(this, "スワイプの向き", 15, true), Ui.mw(this, 10));
            box.addView(dirG);
        }

        int lv = -1;
        for (int i = 0; i < Scenario.LEVELS.length; i++) if (Scenario.LEVELS[i] == r.threshold) lv = i;
        final RadioGroup lvG = radios(Scenario.LEVEL_NAMES, lv);
        final EditText thr = Ui.number(this, r.threshold);
        if (img) {
            box.addView(Ui.text(this, "画面の判定", 15, true), Ui.mw(this, 10));
            box.addView(lvG);
            box.addView(Ui.field(this, "数字で決める", thr, "%"));
            lvG.setOnCheckedChangeListener((g, id) -> {
                int k = id - 1000;
                if (k >= 0 && k < Scenario.LEVELS.length) thr.setText(String.valueOf(Scenario.LEVELS[k]));
            });
        }

        box.addView(Ui.text(this, img ? "押したあと待つ時間（この間でランダム）" : "操作の間隔（この間でランダム）", 15, true), Ui.mw(this, 10));
        final EditText wMin = decimal(r.waitMin), wMax = decimal(r.waitMax);
        box.addView(Ui.chips(this, Ui.TEMPO_NAMES, Ui.tempoIndex(r.waitMin, r.waitMax), k -> {
            wMin.setText(sec(Ui.TEMPO[k][0]));
            wMax.setText(sec(Ui.TEMPO[k][1]));
        }));
        box.addView(Ui.field(this, "最短", wMin, "秒"));
        box.addView(Ui.field(this, "最長", wMax, "秒"));

        box.addView(Ui.text(this, img ? "最大で待つ時間（画像が出るまで）" : "最大で続ける時間（次の画像が出るまで）", 15, true), Ui.mw(this, 10));
        final EditText to = Ui.number(this, r.timeoutSec);
        final int[] toVals = {30, 60, 180, 600, 1800};
        int ti = -1;
        for (int i = 0; i < toVals.length; i++) if (toVals[i] == r.timeoutSec) ti = i;
        box.addView(Ui.chips(this, new String[]{"30秒", "1分", "3分", "10分", "30分"}, ti, k -> to.setText(String.valueOf(toVals[k]))));
        box.addView(Ui.field(this, "数字で決める", to, "秒"));
        final RadioGroup skipG = radios(new String[]{"過ぎたら止める（不具合として記録）", "過ぎたら次の手順へ進む"}, r.skipOnTimeout ? 1 : 0);
        box.addView(skipG);
        if (!img) box.addView(Ui.subText(this, "次の手順が画像を使わない手順のときは、この時間だけ続けて次へ進みます。"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle((idx + 1) + ". " + r.name)
                .setView(sv)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    String n = nm.getText().toString().trim();
                    if (!n.isEmpty()) r.name = n;
                    int ai = actG.getCheckedRadioButtonId() - 1000;
                    if (ai >= 0 && ai < acts.length) r.act = acts[ai];
                    int di = dirG.getCheckedRadioButtonId() - 1000;
                    if (!img && di >= 0 && di < Scenario.DIR_NAMES.length) r.dir = di;
                    if (img) r.threshold = Math.max(50, Math.min(99, Ui.parse(thr, r.threshold)));
                    r.waitMin = Math.max(0, Math.min(3_600_000, parseMs(wMin, r.waitMin)));
                    r.waitMax = Math.max(r.waitMin, Math.min(3_600_000, parseMs(wMax, r.waitMax)));
                    r.timeoutSec = Math.max(1, Math.min(86400, Ui.parse(to, r.timeoutSec)));
                    r.skipOnTimeout = skipG.getCheckedRadioButtonId() - 1000 == 1;
                    render();
                })
                .setNeutralButton("削除", (d, w) -> { sc.flow.remove(r); render(); })
                .show();
    }

    private void editExtra(final Scenario.Rule step, final Scenario.Rule e, final int j) {
        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText nm = new EditText(this);
        nm.setText(e.name);
        nm.setTextColor(Ui.fg(this));
        box.addView(Ui.subText(this, "名前"));
        box.addView(nm);

        int cur = e.extraChoice();
        box.addView(Ui.text(this, "このボタンが出たら", 15, true), Ui.mw(this, 10));
        final RadioGroup actG = radios(Scenario.Rule.EXTRA_NAMES, cur);
        box.addView(actG);
        final EditText jump = Ui.number(this, e.jumpTo >= 0 ? e.jumpTo + 1 : 1);
        box.addView(Ui.field(this, "分かれ道のとき、進む手順の番号", jump, "番"));

        int lv = -1;
        for (int i = 0; i < Scenario.LEVELS.length; i++) if (Scenario.LEVELS[i] == e.threshold) lv = i;
        final RadioGroup lvG = radios(Scenario.LEVEL_NAMES, lv);
        final EditText thr = Ui.number(this, e.threshold);
        box.addView(Ui.text(this, "画面の判定", 15, true), Ui.mw(this, 10));
        box.addView(lvG);
        box.addView(Ui.field(this, "数字で決める", thr, "%"));
        lvG.setOnCheckedChangeListener((g, id) -> {
            int k = id - 1000;
            if (k >= 0 && k < Scenario.LEVELS.length) thr.setText(String.valueOf(Scenario.LEVELS[k]));
        });

        box.addView(Ui.text(this, "押したあと待つ時間（この間でランダム）", 15, true), Ui.mw(this, 10));
        final EditText wMin = decimal(e.waitMin), wMax = decimal(e.waitMax);
        box.addView(Ui.chips(this, Ui.TEMPO_NAMES, Ui.tempoIndex(e.waitMin, e.waitMax), k -> {
            wMin.setText(sec(Ui.TEMPO[k][0]));
            wMax.setText(sec(Ui.TEMPO[k][1]));
        }));
        box.addView(Ui.field(this, "最短", wMin, "秒"));
        box.addView(Ui.field(this, "最長", wMax, "秒"));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("優先" + (j + 1) + "：" + e.name)
                .setView(sv)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    String n = nm.getText().toString().trim();
                    if (!n.isEmpty()) e.name = n;
                    int ai = actG.getCheckedRadioButtonId() - 1000;
                    if (ai >= 0 && ai < Scenario.Rule.EXTRA_NAMES.length) e.applyExtraChoice(ai);
                    if (ai == 5) e.jumpTo = Math.max(0, Math.min(sc.flow.size() - 1, Ui.parse(jump, 1) - 1));
                    e.threshold = Math.max(50, Math.min(99, Ui.parse(thr, e.threshold)));
                    e.waitMin = Math.max(0, Math.min(3_600_000, parseMs(wMin, e.waitMin)));
                    e.waitMax = Math.max(e.waitMin, Math.min(3_600_000, parseMs(wMax, e.waitMax)));
                    render();
                })
                .setNeutralButton("削除", (d, w) -> { step.extras.remove(e); render(); })
                .show();
    }
}
