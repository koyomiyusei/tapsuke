package com.rerise.tapsuke;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 画面部品を手で組むための小道具（AndroidX 不使用） */
public class Ui {

    public static final int ACCENT = 0xFF2F80ED;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static boolean dark(Context c) {
        int m = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static int fg(Context c) { return dark(c) ? 0xFFEDEDED : 0xFF1A1A1A; }
    public static int sub(Context c) { return dark(c) ? 0xFFA8A8A8 : 0xFF666666; }
    public static int card(Context c) { return dark(c) ? 0xFF262626 : 0xFFF2F3F5; }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(fg(c));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static TextView subText(Context c, String s) {
        TextView t = text(c, s, 13, false);
        t.setTextColor(sub(c));
        return t;
    }

    public static Button button(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    public static Button primary(Context c, String s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(ACCENT, dp(c, 10)));
        return b;
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public static View cardView(Context c, View inner) {
        LinearLayout box = vbox(c);
        int p = dp(c, 14);
        box.setPadding(p, p, p, p);
        box.setBackground(round(card(c), dp(c, 12)));
        box.addView(inner);
        return box;
    }

    public static LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams mw(Context c, int topDp) {
        LinearLayout.LayoutParams p = mw();
        p.topMargin = dp(c, topDp);
        return p;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static EditText number(Context c, int value) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        e.setTextColor(fg(c));
        return e;
    }

    public static int parse(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return def;
        }
    }

    /** 「ラベル ［入力欄］ 単位」の1行 */
    public static LinearLayout field(Context c, String label, EditText e, String unit) {
        LinearLayout row = hbox(c);
        TextView l = text(c, label, 15, false);
        row.addView(l, weight(1));
        row.addView(e, new LinearLayout.LayoutParams(dp(c, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (unit != null) {
            TextView u = subText(c, unit);
            u.setPadding(dp(c, 6), 0, 0, 0);
            u.setMinWidth(dp(c, 36));
            row.addView(u);
        }
        return row;
    }

    public interface OnPick { void pick(int which); }

    /** 選択肢ボタンを横に並べる（押したものが青くなる）。数字を入れなくても選ぶだけで決まる */
    public static LinearLayout chips(final Context c, String[] labels, int selected, final OnPick onPick) {
        final LinearLayout row = hbox(c);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = new Button(c);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setMinHeight(dp(c, 40));
            b.setMinimumHeight(dp(c, 40));
            b.setPadding(dp(c, 4), 0, dp(c, 4), 0);
            b.setOnClickListener(v -> {
                markChip(c, row, idx);
                onPick.pick(idx);
            });
            LinearLayout.LayoutParams lp = weight(1);
            lp.rightMargin = dp(c, 4);
            row.addView(b, lp);
        }
        markChip(c, row, selected);
        return row;
    }

    public static void markChip(Context c, LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof Button)) continue;
            Button b = (Button) v;
            boolean on = i == selected;
            b.setBackground(round(on ? ACCENT : card(c), dp(c, 10)));
            b.setTextColor(on ? Color.WHITE : fg(c));
        }
    }

    // ---- 言葉で選べる目安（数字はこの値が自動で入る） ----

    /** ばらつき：位置のズレ(px) と 間隔のズレ(%) */
    public static final String[] SPREAD_NAMES = {"なし", "小", "中", "大"};
    public static final int[][] SPREAD = {{0, 0}, {8, 10}, {20, 25}, {40, 45}};

    /** テンポ：待ち時間の最短〜最長(ms) */
    public static final String[] TEMPO_NAMES = {"速め", "普通", "ゆっくり", "かなりゆっくり"};
    public static final int[][] TEMPO = {{300, 700}, {800, 1500}, {1500, 3000}, {3000, 6000}};

    public static int spreadIndex(int px, int pct) {
        for (int i = 0; i < SPREAD.length; i++) if (SPREAD[i][0] == px && SPREAD[i][1] == pct) return i;
        return -1;
    }

    public static int tempoIndex(int min, int max) {
        for (int i = 0; i < TEMPO.length; i++) if (TEMPO[i][0] == min && TEMPO[i][1] == max) return i;
        return -1;
    }

    /** テンポのゆらぎ（人の操作に近づける）の設定カード。選ぶとその場で sc に入る（保存は呼び出し側） */
    public static View paceCard(Context c, final Scenario sc) {
        LinearLayout box = vbox(c);
        box.addView(text(c, "テンポのゆらぎ（人の操作に近づける）", 16, true));
        box.addView(subText(c, "押す位置だけでなく、押すペースも毎回変えます。"));
        box.addView(text(c, "待ち時間の散らし方", 14, false), mw(c, 10));
        box.addView(subText(c, "人っぽく：短めの間隔が多く、たまに長めになる"));
        box.addView(chips(c, new String[]{"均等", "人っぽく"}, sc.paceMode, k -> sc.paceMode = k), mw(c, 4));
        box.addView(text(c, "たまに手を止める", 14, false), mw(c, 10));
        box.addView(subText(c, "ときどき：約3%の確率で2〜6秒止まる／よく：約8%の確率で3〜12秒止まる"));
        box.addView(chips(c, Scenario.PAUSE_NAMES, sc.pauseLevel, k -> sc.pauseLevel = k), mw(c, 4));
        box.addView(text(c, "長めの休憩", 14, false), mw(c, 10));
        box.addView(subText(c, "15〜30分ごと：1〜3分休む／30〜60分ごと：3〜8分休む（休憩は実行ログに残ります）"));
        box.addView(chips(c, Scenario.BREAK_NAMES, sc.breakLevel, k -> sc.breakLevel = k), mw(c, 4));
        box.addView(text(c, "押している長さ", 14, false), mw(c, 10));
        box.addView(subText(c, "ばらつく：0.03〜0.25秒の間で毎回変わる（短めが多い）"));
        box.addView(chips(c, Scenario.HOLD_NAMES, sc.tapHold, k -> sc.tapHold = k), mw(c, 4));
        return cardView(c, box);
    }
}
