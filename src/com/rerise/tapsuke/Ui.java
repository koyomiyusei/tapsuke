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
}
