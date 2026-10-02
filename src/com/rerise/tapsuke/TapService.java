package com.rerise.tapsuke;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * タップ助の本体。ユーザー補助サービスとして動く。
 *
 * シナリオには2つの型がある。
 *  - 場面モード：「この画面が見えたら、これをする」を並べる。実行中は画面を見続けて、当てはまる場面の操作をする
 *  - 順番モード：決めた手順を上から順に行う
 *
 * プライバシー：撮った画面は照合に使ったらすぐ捨てる。保存も送信もしない。
 * 画面の文字や部品の情報（ウィンドウの中身）は取得しない設定にしてある。
 */
public class TapService extends AccessibilityService {

    private static TapService instance;

    public static TapService get() { return instance; }

    public static boolean isOn() { return instance != null; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager wm;

    // パネル・マーカー・範囲表示
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView btnPlay, btnRec, btnEye, status;
    private View[] extraButtons;
    private boolean collapsed;
    private final List<Marker> markers = new ArrayList<>();
    private boolean markersVisible = true;
    private int offX = Integer.MIN_VALUE, offY = Integer.MIN_VALUE;
    private AreaView areaView;

    private Scenario sc;

    // 実行
    private boolean playing;
    private int token;
    private int stepIndex, loopCount, notFoundSkips;
    private long playStart, stepStart;
    private float lastScore;
    // 場面モードの実行
    private int lastRule = -1;
    private long noneSince;
    private int[] fired;
    private int otherFired;
    private float[] bestWhileNone;

    // 記録
    private RecordView recView;
    private WindowManager.LayoutParams recLp;
    private boolean recording, passing;
    private long lastUpTime;

    // 画面の切り出し
    private View cropView, cropBar;
    private Bitmap cropShot;
    private boolean busy; // 撮影や登録の途中

    // ================= ライフサイクル =================

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            if (App.prefs(this).getBoolean("panel_on_connect", false)) {
                App.prefs(this).edit().putBoolean("panel_on_connect", false).apply();
                showPanel(App.prefs(this).getString("current", null));
            }
        } catch (Throwable e) {
            App.log(this, e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            stopPlay(null);
            closePanel();
        } catch (Throwable e) {
            App.log(this, e);
        }
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    /** 音量下キー：実行中・記録中なら停止（安全装置） */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if ((playing || recording) && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                && App.prefs(this).getBoolean("vol_stop", true)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                h.post(() -> {
                    if (playing) stopPlay("音量キーで停止しました");
                    if (recording) stopRecord();
                });
            }
            return true;
        }
        return false;
    }

    // ================= 共通の小道具 =================

    public int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private WindowManager.LayoutParams overlayLp(int w, int hgt) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, hgt,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    /** 重ね表示を外す。その場で外れる（すぐ付け直しても二重にならない） */
    private void removeSafe(View v) {
        if (v == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Exception ignored) {
            // まだ出していない・すでに外れている
        }
    }

    private void addSafe(View v, WindowManager.LayoutParams lp) {
        if (v == null) return;
        try {
            wm.addView(v, lp);
        } catch (IllegalStateException already) {
            // すでに出ている
        } catch (Exception e) {
            App.log(this, e);
        }
    }

    /** パネルを一番手前に出し直す */
    private void raisePanel() {
        if (panel == null) return;
        removeSafe(panel);
        addSafe(panel, panelLp);
    }

    // ---- 画面の上に出す選択メニュー（ダイアログの代わり。どのアプリの上でも確実に出せる） ----

    interface Pick { void pick(int which); }

    private View menuView;

    private void closeMenu() {
        removeSafe(menuView);
        menuView = null;
    }

    /** 選択肢を出す。外側をタップするか下のボタンで取り消し（onCancel） */
    private void showMenu(String title, String[] items, final Pick onPick, final Runnable onCancel, String cancelLabel) {
        showSheet(title, null, items, onPick, onCancel, cancelLabel);
    }

    private void showMessage(String title, String message) {
        showSheet(title, message, new String[0], null, null, "OK");
    }

    private void showSheet(String title, String message, String[] items, final Pick onPick,
                           final Runnable onCancel, String cancelLabel) {
        closeMenu();
        int[] sz = screenSize();
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        final Runnable cancel = () -> {
            closeMenu();
            try {
                if (onCancel != null) onCancel.run();
            } catch (Throwable e) {
                App.log(TapService.this, e);
            }
        };
        root.setOnClickListener(v -> cancel.run());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(0xFF2B2D31, dp(18)));
        card.setPadding(dp(6), dp(14), dp(6), dp(6));
        card.setOnClickListener(v -> { });

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(16), 0, dp(16), dp(10));
        card.addView(t);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (message != null) {
            TextView m = new TextView(this);
            m.setText(message);
            m.setTextColor(0xFFE6E6E6);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            m.setLineSpacing(dp(3), 1f);
            m.setPadding(dp(16), dp(4), dp(16), dp(12));
            list.addView(m);
        }
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView it = new TextView(this);
            it.setText(items[i]);
            it.setTextColor(Color.WHITE);
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            it.setPadding(dp(16), dp(14), dp(16), dp(14));
            it.setBackground(Ui.round(0xFF3A3D42, dp(12)));
            it.setOnClickListener(v -> {
                closeMenu();
                try {
                    onPick.pick(idx);
                } catch (Throwable e) {
                    App.log(TapService.this, e);
                    busy = false;
                    closeCrop();
                    restoreOverlays();
                    toast("エラーが起きました。「最後のエラー」を確認してください");
                }
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.setMargins(dp(8), dp(4), dp(8), dp(4));
            list.addView(it, ip);
        }
        sv.addView(list);
        card.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView c = new TextView(this);
        c.setText(cancelLabel);
        c.setTextColor(0xFFFFD166);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(14), dp(16), dp(12));
        c.setOnClickListener(v -> cancel.run());
        card.addView(c);

        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(
                Math.min(sz[0] - dp(40), dp(380)), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cp.topMargin = dp(70);
        cp.bottomMargin = dp(70);
        root.addView(card, cp);

        menuView = root;
        addSafe(root, overlayLp(sz[0], sz[1]));
    }

    private boolean rules() { return sc != null && sc.mode == Scenario.MODE_RULES; }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
        } catch (Exception ignored) {
        }
    }

    // ================= パネル =================

    public boolean panelShown() { return panel != null; }

    public String currentId() { return sc == null ? null : sc.id; }

    public void showPanel(String id) {
        try {
            if (panel != null) {
                if (sc != null && id != null && id.equals(sc.id)) return;
                closePanel();
            }
            Scenario s = Scenario.load(this, id);
            if (s == null) {
                s = new Scenario();
                s.name = "シナリオ" + (Scenario.all(this).size() + 1);
                s.mode = Scenario.MODE_RULES;
                int[] sz = screenSize();
                s.screenW = sz[0];
                s.screenH = sz[1];
                s.save(this);
            }
            sc = s;
            App.prefs(this).edit().putString("current", sc.id).apply();
            scaleIfNeeded(sc);
            buildPanel();
            refreshOverlays();
        } catch (Throwable e) {
            App.log(this, e);
            toast("パネルを出せませんでした。「最後のエラー」を確認してください");
        }
    }

    private void scaleIfNeeded(Scenario s) {
        int[] sz = screenSize();
        if (s.screenW <= 0 || s.screenH <= 0) { s.screenW = sz[0]; s.screenH = sz[1]; return; }
        if (s.screenW == sz[0] && s.screenH == sz[1]) return;
        float fx = sz[0] / (float) s.screenW, fy = sz[1] / (float) s.screenH;
        for (Scenario.Step st : s.steps) {
            st.x *= fx; st.y *= fy; st.x2 *= fx; st.y2 *= fy;
            if (st.path != null) for (float[] p : st.path) { p[0] *= fx; p[1] *= fy; }
        }
        List<Scenario.Rule> all = new ArrayList<>(s.rules);
        all.add(s.other);
        for (Scenario.Rule r : all) {
            r.al = Math.round(r.al * fx); r.ar = Math.round(r.ar * fx);
            r.at = Math.round(r.at * fy); r.ab = Math.round(r.ab * fy);
        }
        s.screenW = sz[0];
        s.screenH = sz[1];
    }

    private TextView pbtn(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(38));
        t.setMinHeight(dp(40));
        t.setOnClickListener(v -> {
            try {
                l.onClick(v);
            } catch (Throwable e) {
                App.log(TapService.this, e);
                toast("エラーが起きました。「最後のエラー」を確認してください");
            }
        });
        return t;
    }

    /** 文字つきのボタン（場面モード用。何のボタンか見て分かるように） */
    private TextView lbtn(String label, View.OnClickListener l) {
        TextView t = pbtn(label, l);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(0xE8202124, dp(18)));
        panel.setPadding(dp(4), dp(2), dp(6), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView handle = pbtn("≡", v -> toggleCollapse());
        attachDrag(handle, true);
        btnPlay = pbtn("▶", v -> { if (playing) stopPlay("停止しました"); else startPlay(); });
        row.addView(handle);
        row.addView(btnPlay);

        if (rules()) {
            TextView add = lbtn("＋場面", v -> addSceneFlow(-1));
            TextView chk = lbtn("確認", v -> checkNow());
            TextView list = lbtn("一覧", v -> sceneListDialog());
            btnEye = lbtn("範囲", v -> toggleMarkers());
            TextView close = pbtn("✕", v -> closePanel());
            row.addView(add);
            row.addView(chk);
            row.addView(list);
            row.addView(btnEye);
            row.addView(close);
            btnRec = null;
            extraButtons = new View[]{add, chk, list, btnEye, close};
        } else {
            TextView add = pbtn("＋", v -> addStep(Scenario.TAP));
            TextView swipe = pbtn("⇅", v -> addStep(Scenario.SWIPE));
            TextView cam = pbtn("◫", v -> addImageStepFlow());
            btnRec = pbtn("●", v -> { if (recording) stopRecord(); else startRecord(); });
            btnRec.setTextColor(0xFFFF6B6B);
            btnEye = pbtn("◉", v -> toggleMarkers());
            TextView edit = pbtn("✎", v -> openEditor());
            TextView close = pbtn("✕", v -> closePanel());
            row.addView(add);
            row.addView(swipe);
            row.addView(cam);
            row.addView(btnRec);
            row.addView(btnEye);
            row.addView(edit);
            row.addView(close);
            extraButtons = new View[]{add, swipe, cam, btnRec, btnEye, edit, close};
        }
        panel.addView(row);

        status = new TextView(this);
        status.setTextColor(0xFFCFCFCF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(8), 0, dp(4), 0);
        status.setSingleLine(true);
        panel.addView(status);

        panelLp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        panelLp.x = App.prefs(this).getInt("panel_x", dp(8));
        panelLp.y = App.prefs(this).getInt("panel_y", dp(120));
        addSafe(panel, panelLp);
        idleStatus();
    }

    private void idleStatus() {
        if (sc == null) return;
        setStatus(sc.name + "・" + (rules() ? "場面" + sc.rules.size() + "個" : sc.steps.size() + "手順"));
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        for (View v : extraButtons) v.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    public void closePanel() {
        if (playing) stopPlay(null);
        if (recording) stopRecord();
        closeCrop();
        closeMenu();
        busy = false;
        commitMarkers();
        if (sc != null) sc.save(this);
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        removeSafe(areaView);
        areaView = null;
        removeSafe(panel);
        panel = null;
        sc = null;
    }

    private void openEditor() {
        if (sc == null || playing) return;
        commitMarkers();
        sc.save(this);
        Class<?> cls = rules() ? RulesActivity.class : EditActivity.class;
        startActivity(new Intent(this, cls).putExtra("id", sc.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public void reload(String id) {
        try {
            if (sc == null || !sc.id.equals(id) || playing || recording || busy) return;
            Scenario s = Scenario.load(this, id);
            if (s == null) { closePanel(); return; }
            boolean modeChanged = s.mode != sc.mode;
            sc = s;
            scaleIfNeeded(sc);
            if (modeChanged) {
                removeSafe(panel);
                buildPanel();
            }
            refreshOverlays();
            idleStatus();
        } catch (Throwable e) {
            App.log(this, e);
        }
    }

    public void forget(String id) {
        if (sc != null && sc.id.equals(id)) {
            if (playing) stopPlay(null);
            for (Marker m : markers) removeSafe(m.view);
            markers.clear();
            removeSafe(areaView);
            areaView = null;
            removeSafe(panel);
            panel = null;
            sc = null;
        }
    }

    private void attachDrag(View v, final boolean isPanel) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;

            public boolean onTouch(View view, MotionEvent e) {
                WindowManager.LayoutParams lp = isPanel ? panelLp : ((Marker) view.getTag()).lp;
                View target = isPanel ? panel : view;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            lp.x = ox + Math.round(dx);
                            lp.y = oy + Math.round(dy);
                            try { wm.updateViewLayout(target, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) view.performClick();
                        else if (isPanel) {
                            App.prefs(TapService.this).edit().putInt("panel_x", lp.x).putInt("panel_y", lp.y).apply();
                        } else {
                            commitMarkers();
                            if (sc != null) sc.save(TapService.this);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ================= マーカー（順番モード）と範囲表示（場面モード） =================

    private class Marker {
        View view;
        WindowManager.LayoutParams lp;
        Scenario.Step step;
        boolean end;
    }

    private class MarkerView extends View {
        final String label; final int color;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MarkerView(Context c, String label, int color) {
            super(c);
            this.label = label;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor((color & 0x00FFFFFF) | 0x99000000);
            c.drawCircle(r, r, r - dp(2), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(Color.WHITE);
            c.drawCircle(r, r, r - dp(2), p);
            p.setStrokeWidth(dp(1));
            c.drawLine(r - dp(5), r, r + dp(5), r, p);
            c.drawLine(r, r - dp(5), r, r + dp(5), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(label.length() > 2 ? 10 : 12));
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            c.drawText(label, r, r - dp(8), p);
        }
    }

    /** 場面ごとの「操作する範囲」を枠で見せる（触れない） */
    private class AreaView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        AreaView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            if (sc == null) return;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            int[] colors = {0xFF2F80ED, 0xFF27AE60, 0xFFF2994A, 0xFF9B51E0, 0xFFEB5757, 0xFF00B8D9};
            for (int i = 0; i <= sc.rules.size(); i++) {
                Scenario.Rule r = i < sc.rules.size() ? sc.rules.get(i) : sc.other;
                if (!r.needsArea() || !r.hasArea()) continue;
                int col = i < sc.rules.size() ? colors[i % colors.length] : 0xFFFFFFFF;
                RectF rc = new RectF(r.al - loc[0], r.at - loc[1], r.ar - loc[0], r.ab - loc[1]);
                p.setStyle(Paint.Style.FILL);
                p.setColor((col & 0x00FFFFFF) | 0x22000000);
                c.drawRect(rc, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(col);
                c.drawRect(rc, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(dp(12));
                p.setFakeBoldText(true);
                String label = (i < sc.rules.size() ? (i + 1) + ". " + r.name : "どの場面でもないとき")
                        + "：" + (r.act == Scenario.R_SWIPE_AREA ? "スワイプ" : "タップ");
                float tw = p.measureText(label);
                p.setColor(0xCC000000);
                c.drawRect(rc.left, rc.top, rc.left + tw + dp(10), rc.top + dp(20), p);
                p.setColor(Color.WHITE);
                c.drawText(label, rc.left + dp(5), rc.top + dp(15), p);
            }
        }
    }

    private int markerSize() { return dp(46); }

    /** いまのシナリオに合わせて、マーカーや範囲の表示を作り直す */
    private void refreshOverlays() {
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        removeSafe(areaView);
        areaView = null;
        if (sc == null) return;
        if (rules()) {
            areaView = new AreaView(this);
            int[] sz = screenSize();
            WindowManager.LayoutParams lp = overlayLp(sz[0], sz[1]);
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            addSafe(areaView, lp);
        } else {
            int n = 0;
            for (Scenario.Step st : sc.steps) {
                n++;
                if (!st.hasPoint()) continue;
                int color = st.type == Scenario.TAP ? 0xFF2F80ED
                        : st.type == Scenario.LONG ? 0xFFF2994A
                        : st.type == Scenario.UNTIL ? 0xFF9B51E0 : 0xFF27AE60;
                addMarker(st, false, String.valueOf(n), color);
                if (st.isSwipeLike()) addMarker(st, true, n + "終", 0xFFEB5757);
            }
        }
        raisePanel();
        applyMarkerState();
        calibrate();
    }

    private void addMarker(Scenario.Step st, boolean end, String label, int color) {
        Marker m = new Marker();
        m.step = st;
        m.end = end;
        m.view = new MarkerView(this, label, color);
        m.view.setTag(m);
        int size = markerSize();
        m.lp = overlayLp(size, size);
        float x = end ? st.x2 : st.x, y = end ? st.y2 : st.y;
        m.lp.x = Math.round(x - size / 2f) - (offX == Integer.MIN_VALUE ? 0 : offX);
        m.lp.y = Math.round(y - size / 2f) - (offY == Integer.MIN_VALUE ? 0 : offY);
        attachDrag(m.view, false);
        m.view.setOnClickListener(v -> { });
        addSafe(m.view, m.lp);
        markers.add(m);
    }

    private void calibrate() {
        if (offX != Integer.MIN_VALUE || markers.isEmpty()) return;
        final Marker m = markers.get(0);
        m.view.post(() -> {
            try {
                if (!m.view.isAttachedToWindow() || offX != Integer.MIN_VALUE) return;
                int[] loc = new int[2];
                m.view.getLocationOnScreen(loc);
                offX = loc[0] - m.lp.x;
                offY = loc[1] - m.lp.y;
                if (offX != 0 || offY != 0) refreshOverlays();
            } catch (Throwable e) {
                App.log(TapService.this, e);
            }
        });
    }

    private void commitMarkers() {
        if (sc == null || rules()) return;
        int size = markerSize();
        for (Marker m : markers) {
            if (!m.view.isAttachedToWindow() || m.view.getVisibility() != View.VISIBLE) continue;
            int[] loc = new int[2];
            m.view.getLocationOnScreen(loc);
            float cx = loc[0] + size / 2f, cy = loc[1] + size / 2f;
            if (m.end) {
                if (m.step.path != null && (Math.abs(cx - m.step.x2) > 1 || Math.abs(cy - m.step.y2) > 1)) m.step.path = null;
                m.step.x2 = cx; m.step.y2 = cy;
            } else {
                if (m.step.path != null && (Math.abs(cx - m.step.x) > 1 || Math.abs(cy - m.step.y) > 1)) m.step.path = null;
                m.step.x = cx; m.step.y = cy;
            }
        }
        int[] sz = screenSize();
        sc.screenW = sz[0];
        sc.screenH = sz[1];
    }

    private void toggleMarkers() {
        markersVisible = !markersVisible;
        applyMarkerState();
    }

    private boolean usesImages() {
        if (sc == null) return false;
        if (rules()) return true;
        for (Scenario.Step s : sc.steps) if (s.isImage()) return true;
        return false;
    }

    /** 実行中・記録中・撮影中は、照合や操作の邪魔をしないよう表示を引っ込める */
    private void applyMarkerState() {
        boolean passive = playing || recording;
        boolean hide = !markersVisible || busy || (playing && usesImages());
        for (Marker m : markers) {
            m.view.setVisibility(hide ? View.GONE : View.VISIBLE);
            m.view.setAlpha(passive ? 0.35f : 1f);
            if (passive) m.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else m.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(m.view, m.lp); } catch (Exception ignored) { }
        }
        if (areaView != null) {
            areaView.setVisibility(hide || playing ? View.GONE : View.VISIBLE);
            areaView.invalidate();
        }
        if (btnEye != null) btnEye.setAlpha(markersVisible ? 1f : 0.4f);
    }

    private void addStep(int type) {
        if (sc == null || playing || recording || busy) return;
        commitMarkers();
        int[] sz = screenSize();
        Scenario.Step st = new Scenario.Step();
        st.type = type;
        int k = sc.steps.size() % 5;
        st.x = sz[0] / 2f + (k - 2) * dp(24);
        st.y = sz[1] * 0.45f + (k - 2) * dp(24);
        if (type == Scenario.SWIPE) {
            st.x = sz[0] / 2f;
            st.y = sz[1] * 0.70f;
            st.x2 = sz[0] / 2f;
            st.y2 = sz[1] * 0.30f;
            st.duration = 300;
            st.after = 800;
        }
        sc.steps.add(st);
        sc.save(this);
        markersVisible = true;
        refreshOverlays();
        idleStatus();
    }

    // ================= 画面の撮影（照合用。保存しない） =================

    interface ShotCallback { void done(Bitmap b, String error); }

    private void shoot(final ShotCallback cb, final int retry) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.done(null, "画像を使う機能は Android 11 以上で使えます");
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    Bitmap soft = null;
                    HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                    } catch (Throwable e) {
                        App.log(TapService.this, e);
                    } finally {
                        hb.close();
                    }
                    cb.done(soft, soft == null ? "画面を読み取れませんでした" : null);
                }

                @Override
                public void onFailure(int code) {
                    // 連続で撮りすぎ（1秒に約3回が上限）のときは少し待って撮り直す
                    if (retry < 6) {
                        h.postDelayed(() -> shoot(cb, retry + 1), 220 + rnd.nextInt(120));
                    } else {
                        cb.done(null, "画面を撮れませんでした（コード " + code + "）");
                    }
                }
            });
        } catch (Throwable e) {
            App.log(this, e);
            cb.done(null, "画面を撮れませんでした");
        }
    }

    // ================= 画面を撮って、指で囲む =================

    interface CropDone { void done(Bitmap shot, Rect r); }

    /** いまの画面を撮って、指で囲んでもらう。パネルなどは写らないよう先に隠す */
    private void capture(final String title, final boolean allowFull, final CropDone cb) {
        if (sc == null || playing || recording || busy) return;
        if (Build.VERSION.SDK_INT < 30) { toast("画像を使う機能は Android 11 以上で使えます"); return; }
        commitMarkers();
        busy = true;
        hideOverlays();
        h.postDelayed(() -> shoot((b, err) -> h.post(() -> {
            if (b == null) {
                busy = false;
                restoreOverlays();
                toast(err);
                return;
            }
            cropShot = b;
            showCrop(title, allowFull, cb);
        }), 0), 350);
    }

    private void hideOverlays() {
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        for (Marker m : markers) m.view.setVisibility(View.GONE);
        if (areaView != null) areaView.setVisibility(View.GONE);
    }

    private void restoreOverlays() {
        if (panel != null) panel.setVisibility(View.VISIBLE);
        applyMarkerState();
    }

    private class CropView extends View {
        final Bitmap shot;
        final String title;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float sx, sy, ex, ey;
        boolean has;

        CropView(Context c, Bitmap shot, String title) {
            super(c);
            this.shot = shot;
            this.title = title;
        }

        RectF rect() {
            return new RectF(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (shot.isRecycled()) return;
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            c.drawBitmap(shot, -loc[0], -loc[1], null);
            c.drawColor(0x66000000);
            if (has) {
                RectF r = rect();
                r.offset(-loc[0], -loc[1]);
                c.save();
                c.clipRect(r);
                c.drawBitmap(shot, -loc[0], -loc[1], null);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0xFFFFD166);
                c.drawRect(r, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(15));
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            float tw = p.measureText(title);
            float cx = getWidth() / 2f, ty = dp(70);
            p.setColor(0xDD000000);
            c.drawRoundRect(cx - tw / 2 - dp(12), ty - dp(22), cx + tw / 2 + dp(12), ty + dp(10), dp(10), dp(10), p);
            p.setColor(Color.WHITE);
            c.drawText(title, cx, ty, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ex = e.getRawX(); sy = ey = e.getRawY(); has = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    ex = e.getRawX(); ey = e.getRawY();
                    break;
            }
            invalidate();
            return true;
        }
    }

    /** cropShot の上で囲んでもらう（同じ画面で続けて2回囲むこともできる） */
    private void showCrop(String title, boolean allowFull, final CropDone cb) {
        removeSafe(cropView);
        removeSafe(cropBar);
        final Bitmap shot = cropShot;
        final CropView cv = new CropView(this, shot, title);
        int[] sz = screenSize();
        cropView = cv;
        addSafe(cv, overlayLp(sz[0], sz[1]));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.round(0xF0202124, dp(14)));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(barBtn("やめる", v -> cancelFlow()));
        if (allowFull) bar.addView(barBtn("画面全体", v ->
                finishCrop(cb, shot, new Rect(0, 0, shot.getWidth(), shot.getHeight()))));
        TextView ok = barBtn("これで決定", v -> {
            if (!cv.has) { toast("先に指で囲んでください"); return; }
            RectF r = cv.rect();
            int x = Math.max(0, Math.round(r.left)), y = Math.max(0, Math.round(r.top));
            int x2 = Math.min(shot.getWidth(), Math.round(r.right)), y2 = Math.min(shot.getHeight(), Math.round(r.bottom));
            if (x2 - x < dp(12) || y2 - y < dp(12)) { toast("もう少し大きく囲んでください"); return; }
            finishCrop(cb, shot, new Rect(x, y, x2, y2));
        });
        ok.setTextColor(0xFFFFD166);
        bar.addView(ok);
        WindowManager.LayoutParams blp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        blp.y = dp(70);
        cropBar = bar;
        addSafe(bar, blp);
    }

    private void finishCrop(CropDone cb, Bitmap shot, Rect r) {
        try {
            cb.done(shot, r);
        } catch (Throwable e) {
            App.log(this, e);
            cancelFlow();
            toast("エラーが起きました。「最後のエラー」を確認してください");
        }
    }

    private TextView barBtn(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setOnClickListener(l);
        return t;
    }

    /** 囲む画面を閉じる。撮った画面はここで捨てる（残さない） */
    private void closeCrop() {
        removeSafe(cropView);
        removeSafe(cropBar);
        cropView = null;
        cropBar = null;
        if (cropShot != null) { cropShot.recycle(); cropShot = null; }
    }

    /** 囲む枠だけ消す（同じ画面でもう一度囲んでもらう前など） */
    private void hideCropUi() {
        removeSafe(cropView);
        removeSafe(cropBar);
        cropView = null;
        cropBar = null;
    }

    private void endFlow() {
        closeCrop();
        busy = false;
        if (sc != null) sc.save(this);
        refreshOverlays();
        restoreOverlays();
        idleStatus();
    }

    private void cancelFlow() {
        closeCrop();
        busy = false;
        if (sc != null) sc.save(this);
        Templates.cleanup(this);
        restoreOverlays();
    }

    /** 囲んだ部分をお手本画像として保存。単色すぎるなどで使えないときは null */
    private String saveTemplate(Bitmap shot, Rect r) {
        Bitmap crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height());
        Matcher.Tpl t = Matcher.prepare(crop);
        if (t.flat) {
            crop.recycle();
            toast("ほぼ単色です。模様や文字のある部分を囲んでください");
            return null;
        }
        String id = Templates.save(this, crop);
        crop.recycle();
        return id;
    }

    // ================= 場面モード：追加と編集 =================

    /** 場面を追加する。replaceIndex >= 0 なら、その場面の目印の画像だけ撮り直す */
    private void addSceneFlow(final int replaceIndex) {
        capture("この場面の目印になる部分を囲んでください", true, (shot, r) -> {
            final String id = saveTemplate(shot, r);
            if (id == null) return;
            hideCropUi();
            if (replaceIndex >= 0 && replaceIndex < sc.rules.size()) {
                sc.rules.get(replaceIndex).tpl = id;
                endFlow();
                Templates.cleanup(this);
                toast("目印の画像を取り直しました");
                return;
            }
            final Scenario.Rule rule = new Scenario.Rule();
            rule.tpl = id;
            rule.name = "場面" + (sc.rules.size() + 1);
            showMenu("この画面が見えたら、何をしますか？", Scenario.R_NAMES, which -> {
                rule.act = which;
                if (rule.needsArea()) {
                    askArea(rule, () -> { sc.rules.add(rule); endFlow(); toast("「" + rule.name + "」を追加しました"); });
                } else {
                    sc.rules.add(rule);
                    endFlow();
                    toast("「" + rule.name + "」を追加しました");
                }
            }, this::cancelFlow, "やめる");
        });
    }

    /** 撮ってある画面の上で、操作する範囲を囲んでもらう。スワイプなら向きも聞く */
    private void askArea(final Scenario.Rule rule, final Runnable done) {
        String title = rule.act == Scenario.R_SWIPE_AREA ? "スワイプする範囲を囲んでください" : "タップする範囲を囲んでください";
        showCrop(title, true, (shot, r) -> {
            rule.al = r.left; rule.at = r.top; rule.ar = r.right; rule.ab = r.bottom;
            hideCropUi();
            if (rule.act == Scenario.R_SWIPE_AREA) {
                showMenu("スワイプの向き", Scenario.DIR_NAMES, which -> { rule.dir = which; done.run(); },
                        () -> { rule.dir = 0; done.run(); }, "おまかせ（ランダム）");
            } else {
                done.run();
            }
        });
    }

    /** いまの画面を撮ってから、範囲を決め直す */
    private void retakeArea(final Scenario.Rule rule) {
        String title = rule.act == Scenario.R_SWIPE_AREA ? "スワイプする範囲を囲んでください" : "タップする範囲を囲んでください";
        capture(title, true, (shot, r) -> {
            rule.al = r.left; rule.at = r.top; rule.ar = r.right; rule.ab = r.bottom;
            hideCropUi();
            if (rule.act == Scenario.R_SWIPE_AREA) {
                showMenu("スワイプの向き", Scenario.DIR_NAMES, which -> { rule.dir = which; endFlow(); },
                        this::endFlow, "そのまま");
            } else {
                endFlow();
            }
        });
    }

    /** 場面の一覧（パネルの「一覧」） */
    private void sceneListDialog() {
        if (sc == null || playing || busy) return;
        final int n = sc.rules.size();
        String[] items = new String[n + 2];
        for (int i = 0; i < n; i++) {
            Scenario.Rule r = sc.rules.get(i);
            items[i] = (i + 1) + ". " + r.name + "\n　→ " + r.actLabel();
        }
        items[n] = "どの場面でもないとき\n　→ " + sc.other.actLabel();
        items[n + 1] = "くわしい設定を開く（回数・時間・判定など）";
        showMenu("場面の一覧（上にあるものを優先）", items, which -> {
            if (which < n) sceneMenu(which);
            else if (which == n) otherMenu();
            else openEditor();
        }, null, "閉じる");
    }

    private void sceneMenu(final int i) {
        final Scenario.Rule r = sc.rules.get(i);
        final List<String> items = new ArrayList<>();
        items.add("やることを変える");
        items.add("目印の画像を撮り直す");
        if (r.needsArea()) items.add("操作する範囲を決め直す");
        items.add("優先を上げる（上へ）");
        items.add("優先を下げる（下へ）");
        items.add("この場面を削除");
        showMenu((i + 1) + ". " + r.name, items.toArray(new String[0]), which -> {
                    String s = items.get(which);
                    if (s.startsWith("やること")) changeAction(r, false);
                    else if (s.startsWith("目印")) addSceneFlow(i);
                    else if (s.startsWith("操作する範囲")) retakeArea(r);
                    else if (s.startsWith("優先を上げる")) { if (i > 0) { sc.rules.remove(i); sc.rules.add(i - 1, r); } saveAndRefresh(); sceneListDialog(); }
                    else if (s.startsWith("優先を下げる")) { if (i < sc.rules.size() - 1) { sc.rules.remove(i); sc.rules.add(i + 1, r); } saveAndRefresh(); sceneListDialog(); }
                    else { sc.rules.remove(i); saveAndRefresh(); Templates.cleanup(this); sceneListDialog(); }
        }, this::sceneListDialog, "戻る");
    }

    private void otherMenu() {
        changeAction(sc.other, true);
    }

    /** やることを選び直す。範囲が必要なら、続けて画面を撮って囲んでもらう */
    private void changeAction(final Scenario.Rule r, final boolean isOther) {
        final String[] names;
        final int[] acts;
        if (isOther) {
            names = new String[]{"決めた範囲の中をスワイプし続ける", "決めた範囲の中をタップし続ける", "何もしない（待つ）"};
            acts = new int[]{Scenario.R_SWIPE_AREA, Scenario.R_TAP_AREA, Scenario.R_WAIT};
        } else {
            names = Scenario.R_NAMES;
            acts = new int[]{0, 1, 2, 3, 4};
        }
        showMenu(isOther ? "どの場面でもないとき（クエスト中など）" : "この画面が見えたら、何をしますか？", names, which -> {
            r.act = acts[which];
            if (r.needsArea()) retakeArea(r);
            else saveAndRefresh();
        }, this::sceneListDialog, "戻る");
    }

    private void saveAndRefresh() {
        if (sc == null) return;
        sc.save(this);
        refreshOverlays();
        idleStatus();
    }

    /** 「確認」：いまの画面がどの場面に当てはまるかを、その場で見せる */
    private void checkNow() {
        if (sc == null || playing || busy) return;
        if (sc.rules.isEmpty()) { toast("先に「＋場面」で場面を追加してください"); return; }
        if (Build.VERSION.SDK_INT < 30) { toast("画像を使う機能は Android 11 以上で使えます"); return; }
        busy = true;
        hideOverlays();
        final List<Scenario.Rule> list = new ArrayList<>(sc.rules);
        h.postDelayed(() -> shoot((shot, err) -> {
            final StringBuilder sb = new StringBuilder();
            if (shot == null) {
                sb.append(err);
            } else {
                try {
                    Matcher.Frame f = new Matcher.Frame(shot);
                    boolean first = true;
                    for (int i = 0; i < list.size(); i++) {
                        Scenario.Rule r = list.get(i);
                        Matcher.Tpl t = Templates.tpl(this, r.tpl);
                        Matcher.Hit hit = t == null ? null : Matcher.find(f, t);
                        int pct = hit == null ? 0 : Math.round(hit.score * 100);
                        boolean ok = pct >= r.threshold;
                        sb.append(ok ? (first ? "◎ " : "○ ") : "× ").append(i + 1).append(". ").append(r.name)
                                .append("　").append(pct).append("%（基準 ").append(r.threshold).append("%）");
                        if (ok && first) { sb.append("\n　→ いまはこの場面として動きます"); first = false; }
                        sb.append("\n");
                    }
                    if (first) sb.append("\nどの場面にも当てはまりません。\n→「どの場面でもないとき」の操作をします");
                } catch (Throwable e) {
                    App.log(this, e);
                    sb.append("確認中にエラーが起きました");
                } finally {
                    shot.recycle();
                }
            }
            h.post(() -> {
                busy = false;
                restoreOverlays();
                showMessage("いまの画面の判定", sb.toString());
            });
        }, 0), 350);
    }

    // ================= 順番モード：画像の手順を追加 =================

    private void addImageStepFlow() {
        capture("お手本にする部分を囲んでください", false, (shot, r) -> {
            final String id = saveTemplate(shot, r);
            if (id == null) return;
            final float cx = r.exactCenterX(), cy = r.exactCenterY();
            hideCropUi();
            String[] items = {"見つけたらタップ", "出るまで待つ", "出るまでタップをくり返す", "出るまでスワイプをくり返す"};
            showMenu("この画像で何をしますか？", items, which -> addImageStep(id, which, cx, cy), this::cancelFlow, "やめる");
        });
    }

    private void addImageStep(String id, int which, float cx, float cy) {
        int[] sz = screenSize();
        Scenario.Step st = new Scenario.Step();
        st.tpl = id;
        st.x = cx;
        st.y = cy;
        st.after = 800;
        st.afterMax = 1500;
        if (which == 0) {
            st.type = Scenario.IMAGE;
        } else {
            st.type = Scenario.UNTIL;
            st.timeoutSec = 300;
            if (which == 1) st.act = Scenario.ACT_NONE;
            if (which == 2) {
                st.act = Scenario.ACT_TAP;
                st.x = sz[0] / 2f;
                st.y = sz[1] * 0.5f;
            }
            if (which == 3) {
                st.act = Scenario.ACT_SWIPE;
                st.x = sz[0] / 2f; st.y = sz[1] * 0.70f;
                st.x2 = sz[0] / 2f; st.y2 = sz[1] * 0.30f;
                st.duration = 300;
            }
        }
        sc.steps.add(st);
        markersVisible = true;
        endFlow();
        toast(which >= 2 ? "紫の丸をくり返す操作の位置に動かしてください" : "画像の手順を追加しました");
    }

    // ================= 実行（共通） =================

    public boolean isPlaying() { return playing; }

    public void startPlay() {
        if (sc == null || playing || busy) return;
        if (recording) stopRecord();
        commitMarkers();
        sc.save(this);
        if (usesImages() && Build.VERSION.SDK_INT < 30) {
            toast("画像を使う機能は Android 11 以上で使えます");
            return;
        }
        if (rules()) {
            if (sc.rules.isEmpty()) { toast("先に「＋場面」で場面を追加してください"); return; }
            for (int i = 0; i < sc.rules.size(); i++) {
                Scenario.Rule r = sc.rules.get(i);
                if (!Templates.exists(this, r.tpl)) { toast((i + 1) + "番目の場面の画像がありません。撮り直してください"); return; }
                if (r.needsArea() && !r.hasArea()) { toast((i + 1) + "番目の場面の範囲が決まっていません"); return; }
            }
            if (sc.other.needsArea() && !sc.other.hasArea()) { toast("「どの場面でもないとき」の範囲が決まっていません"); return; }
        } else {
            if (sc.steps.isEmpty()) { toast("先に手順を追加してください"); return; }
            for (Scenario.Step s : sc.steps) {
                if (s.isImage() && !Templates.exists(this, s.tpl)) {
                    toast((sc.steps.indexOf(s) + 1) + "番目の画像がありません。登録し直してください");
                    return;
                }
            }
            if (pointUnderPanel()) toast("パネルの下にタップ位置があります。パネルを動かしてください");
        }
        playing = true;
        token++;
        stepIndex = 0;
        loopCount = 0;
        notFoundSkips = 0;
        lastRule = -1;
        noneSince = 0;
        otherFired = 0;
        fired = new int[sc.rules.size()];
        bestWhileNone = new float[sc.rules.size()];
        playStart = SystemClock.uptimeMillis();
        btnPlay.setText("■");
        btnPlay.setTextColor(0xFFFFD166);
        for (View v : extraButtons) v.setVisibility(View.GONE); // 実行中は小さくして、画面の邪魔をしない
        applyMarkerState();
        setStatus("開始します");
        App.runLog(this, "開始「" + sc.name + "」");
        final int tk = token;
        h.postDelayed(() -> { if (tk == token) { if (rules()) ruleCycle(); else runStep(); } }, 400);
    }

    private boolean pointUnderPanel() {
        if (panel == null) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight());
        for (Scenario.Step s : sc.steps) {
            if (!s.hasPoint()) continue;
            if (r.contains((int) s.x, (int) s.y)) return true;
            if (s.isSwipeLike() && r.contains((int) s.x2, (int) s.y2)) return true;
        }
        return false;
    }

    public void stopPlay(String msg) {
        if (!playing) return;
        playing = false;
        token++;
        h.removeCallbacksAndMessages(null);
        if (btnPlay != null) {
            btnPlay.setText("▶");
            btnPlay.setTextColor(Color.WHITE);
        }
        if (extraButtons != null) for (View v : extraButtons) v.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        applyMarkerState();
        String done = loopCount + "周・" + elapsed();
        setStatus(sc == null ? "" : done + "で停止");
        StringBuilder log = new StringBuilder("停止 ").append(done);
        if (rules() && fired != null) {
            log.append("・回数[");
            for (int i = 0; i < fired.length; i++) log.append(i > 0 ? " " : "").append(i + 1).append(":").append(fired[i]);
            log.append(" 他:").append(otherFired).append("]");
        }
        if (notFoundSkips > 0) log.append("・見失い").append(notFoundSkips).append("回");
        if (msg != null) log.append("・").append(msg);
        App.runLog(this, log.toString());
        if (msg != null) toast(msg + "（" + done + "）");
    }

    private String elapsed() {
        long s = (SystemClock.uptimeMillis() - playStart) / 1000;
        return s >= 3600 ? (s / 3600) + "時間" + (s % 3600 / 60) + "分" : (s / 60) + "分" + (s % 60) + "秒";
    }

    private boolean timeUp() {
        if (sc.maxMinutes > 0 && SystemClock.uptimeMillis() - playStart >= sc.maxMinutes * 60_000L) {
            stopPlay("指定時間が経ったので停止しました");
            return true;
        }
        return false;
    }

    private float clampX(float v) { return Math.max(1, Math.min(screenSize()[0] - 2, v)); }

    private float clampY(float v) { return Math.max(1, Math.min(screenSize()[1] - 2, v)); }

    /** 操作を送る。終わったら then を呼ぶ */
    private void send(GestureDescription g, final Runnable then) {
        final int tk = token;
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) { if (tk == token && playing) then.run(); }

                @Override
                public void onCancelled(GestureDescription d) { if (tk == token && playing) then.run(); }
            }, h);
        } catch (Throwable e) {
            App.log(this, e);
            ok = false;
        }
        if (!ok) stopPlay("操作を送れませんでした。ユーザー補助の設定を確認してください");
    }

    private GestureDescription stroke(Path p, long dur) {
        dur = Math.max(1, Math.min(dur, GestureDescription.getMaxGestureDuration()));
        return new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build();
    }

    private void tapAt(float x, float y, Runnable then) {
        Path p = new Path();
        p.moveTo(clampX(x), clampY(y));
        send(stroke(p, 40 + rnd.nextInt(90)), then);
    }

    /** 見つけた画像の内側のどこかをタップ（端は避ける） */
    private void tapInside(Matcher.Hit hit, Runnable then) {
        float x = hit.cx + (rnd.nextFloat() * 2 - 1) * hit.w * 0.33f;
        float y = hit.cy + (rnd.nextFloat() * 2 - 1) * hit.h * 0.33f;
        tapAt(x, y, then);
    }

    // ================= 実行：場面モード =================

    private void laterRules(int ms) {
        final int tk = token;
        h.postDelayed(() -> { if (tk == token && playing) ruleCycle(); }, Math.max(250, ms));
    }

    private int waitOf(Scenario.Rule r) {
        return r.waitMax > r.waitMin ? r.waitMin + rnd.nextInt(r.waitMax - r.waitMin + 1) : r.waitMin;
    }

    /** 画面を見て、当てはまる場面（上から順に最初のもの）の操作をする */
    private void ruleCycle() {
        if (!playing || sc == null || timeUp()) return;
        final int tk = token;
        final List<Scenario.Rule> list = new ArrayList<>(sc.rules);
        shoot((shot, err) -> {
            if (tk != token) { if (shot != null) shot.recycle(); return; }
            if (shot == null) {
                h.post(() -> { if (tk == token) stopPlay(err); });
                return;
            }
            int found = -1;
            Matcher.Hit hit = null;
            final float[] scores = new float[list.size()];
            try {
                Matcher.Frame f = new Matcher.Frame(shot);
                for (int i = 0; i < list.size(); i++) {
                    Scenario.Rule r = list.get(i);
                    Matcher.Tpl t = Templates.tpl(this, r.tpl);
                    Matcher.Hit hh = t == null ? null : Matcher.find(f, t);
                    scores[i] = hh == null ? 0 : hh.score;
                    if (hh != null && hh.score * 100 >= r.threshold) { found = i; hit = hh; break; }
                }
            } catch (Throwable e) {
                App.log(this, e);
            } finally {
                shot.recycle(); // 照合が終わったら画面はすぐ捨てる
            }
            final int fi = found;
            final Matcher.Hit fh = hit;
            h.post(() -> { if (tk == token && playing) afterRules(fi, fh, scores); });
        }, 0);
    }

    private void afterRules(int found, Matcher.Hit hit, float[] scores) {
        long now = SystemClock.uptimeMillis();
        if (found >= 0 && found < sc.rules.size()) {
            final Scenario.Rule r = sc.rules.get(found);
            noneSince = 0;
            java.util.Arrays.fill(bestWhileNone, 0);
            fired[found]++;
            if (r.countLoop && lastRule != found) {
                loopCount++;
                if (sc.repeat > 0 && loopCount >= sc.repeat) {
                    stopPlay("決めた回数に達したので完了しました");
                    return;
                }
            }
            lastRule = found;
            setStatus(loopCount + "周・" + elapsed() + "｜" + (found + 1) + "." + r.name + " " + Math.round(hit.score * 100) + "%");
            act(r, hit, () -> laterRules(waitOf(r)));
            return;
        }
        lastRule = -1;
        if (noneSince == 0) noneSince = now;
        for (int i = 0; i < scores.length && i < bestWhileNone.length; i++)
            bestWhileNone[i] = Math.max(bestWhileNone[i], scores[i]);
        if (sc.stuckSec > 0 && now - noneSince >= sc.stuckSec * 1000L) {
            StringBuilder sb = new StringBuilder("どの場面も" + sc.stuckSec + "秒見つからないので停止しました（最高一致度");
            for (int i = 0; i < bestWhileNone.length; i++) sb.append(" ").append(i + 1).append(":").append(Math.round(bestWhileNone[i] * 100)).append("%");
            sb.append("）");
            vibrate();
            stopPlay(sb.toString());
            return;
        }
        otherFired++;
        setStatus(loopCount + "周・" + elapsed() + "｜どの場面でもない");
        act(sc.other, null, () -> laterRules(waitOf(sc.other)));
    }

    private void act(Scenario.Rule r, Matcher.Hit hit, Runnable then) {
        switch (r.act) {
            case Scenario.R_TAP_IMAGE:
                if (hit != null) tapInside(hit, then);
                else then.run();
                return;
            case Scenario.R_TAP_AREA:
                if (!r.hasArea()) { then.run(); return; }
                tapAt(r.al + rnd.nextFloat() * (r.ar - r.al), r.at + rnd.nextFloat() * (r.ab - r.at), then);
                return;
            case Scenario.R_SWIPE_AREA:
                if (!r.hasArea()) { then.run(); return; }
                swipeIn(r, then);
                return;
            case Scenario.R_STOP:
                vibrate();
                stopPlay("「" + r.name + "」が出たので停止しました");
                return;
            default:
                then.run();
        }
    }

    private float between(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    /** 範囲の中で、毎回ちがう位置・長さ・速さのスワイプをする */
    private void swipeIn(Scenario.Rule r, Runnable then) {
        float w = r.ar - r.al, hh = r.ab - r.at;
        float x1, y1, x2, y2;
        switch (r.dir) {
            case 1: // 上へ
                x1 = r.al + w * between(0.15f, 0.85f); y1 = r.at + hh * between(0.60f, 0.95f);
                x2 = x1 + w * between(-0.12f, 0.12f);  y2 = r.at + hh * between(0.05f, 0.40f);
                break;
            case 2: // 下へ
                x1 = r.al + w * between(0.15f, 0.85f); y1 = r.at + hh * between(0.05f, 0.40f);
                x2 = x1 + w * between(-0.12f, 0.12f);  y2 = r.at + hh * between(0.60f, 0.95f);
                break;
            case 3: // 左へ
                y1 = r.at + hh * between(0.15f, 0.85f); x1 = r.al + w * between(0.60f, 0.95f);
                y2 = y1 + hh * between(-0.12f, 0.12f);  x2 = r.al + w * between(0.05f, 0.40f);
                break;
            case 4: // 右へ
                y1 = r.at + hh * between(0.15f, 0.85f); x1 = r.al + w * between(0.05f, 0.40f);
                y2 = y1 + hh * between(-0.12f, 0.12f);  x2 = r.al + w * between(0.60f, 0.95f);
                break;
            default: // ランダム：範囲の中の2点。短すぎるときは選び直す
                float min = Math.max(dp(40), Math.min(w, hh) * 0.25f);
                int tries = 0;
                do {
                    x1 = r.al + w * rnd.nextFloat(); y1 = r.at + hh * rnd.nextFloat();
                    x2 = r.al + w * rnd.nextFloat(); y2 = r.at + hh * rnd.nextFloat();
                } while (Math.hypot(x2 - x1, y2 - y1) < min && ++tries < 12);
        }
        x1 = Math.max(r.al, Math.min(r.ar, x1)); x2 = Math.max(r.al, Math.min(r.ar, x2));
        y1 = Math.max(r.at, Math.min(r.ab, y1)); y2 = Math.max(r.at, Math.min(r.ab, y2));
        // 途中を少しだけ曲げる（真っすぐな線ばかりにしない）
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        float mx = (x1 + x2) / 2 + between(-0.08f, 0.08f) * len;
        float my = (y1 + y2) / 2 + between(-0.08f, 0.08f) * len;
        Path p = new Path();
        p.moveTo(clampX(x1), clampY(y1));
        p.quadTo(clampX(mx), clampY(my), clampX(x2), clampY(y2));
        send(stroke(p, 180 + rnd.nextInt(420)), then);
    }

    // ================= 実行：順番モード =================

    private int waitOf(Scenario.Step st) {
        if (st.afterMax > st.after) return st.after + rnd.nextInt(st.afterMax - st.after + 1);
        return jitterTime(st.after);
    }

    private int jitterTime(int ms) {
        if (sc.jitterPct <= 0 || ms <= 0) return ms;
        double f = 1 + (rnd.nextDouble() * 2 - 1) * sc.jitterPct / 100.0;
        return (int) Math.max(0, Math.round(ms * f));
    }

    private void later(int ms) {
        final int tk = token;
        h.postDelayed(() -> { if (tk == token && playing) runStep(); }, Math.max(0, ms));
    }

    private void retryImage(final Scenario.Step st, int ms) {
        final int tk = token;
        h.postDelayed(() -> {
            if (tk != token || !playing || timeUp()) return;
            imageCycle(st);
        }, Math.max(0, ms));
    }

    private void nextStep(Scenario.Step st) {
        stepIndex++;
        later(waitOf(st));
    }

    private void runStep() {
        if (!playing || sc == null || timeUp()) return;
        if (stepIndex >= sc.steps.size()) {
            loopCount++;
            stepIndex = 0;
            if (sc.repeat > 0 && loopCount >= sc.repeat) {
                stopPlay("完了しました");
                return;
            }
            setStatus((loopCount + 1) + "周目" + (sc.repeat > 0 ? " / " + sc.repeat : "") + "・" + elapsed());
            if (sc.loopWait > 0) {
                later(jitterTime(sc.loopWait));
                return;
            }
        }
        final Scenario.Step st = sc.steps.get(stepIndex);
        stepStart = SystemClock.uptimeMillis();
        lastScore = 0;
        switch (st.type) {
            case Scenario.WAIT:
                stepIndex++;
                later(waitOf(st));
                return;
            case Scenario.IMAGE:
            case Scenario.UNTIL:
                imageCycle(st);
                return;
            default:
                send(buildGesture(st, st.type, true), () -> nextStep(st));
        }
    }

    private void imageCycle(final Scenario.Step st) {
        final int tk = token;
        final int idx = stepIndex + 1;
        setStatus((loopCount + 1) + "周目・手順" + idx + " 画像を探しています");
        shoot((shot, err) -> {
            if (tk != token) { if (shot != null) shot.recycle(); return; }
            if (shot == null) {
                h.post(() -> { if (tk == token) stopPlay(err); });
                return;
            }
            Matcher.Hit hit = null;
            try {
                Matcher.Tpl t = Templates.tpl(this, st.tpl);
                if (t != null) hit = Matcher.find(new Matcher.Frame(shot), t);
            } catch (Throwable e) {
                App.log(this, e);
            } finally {
                shot.recycle();
            }
            final Matcher.Hit result = hit;
            h.post(() -> { if (tk == token && playing) afterMatch(st, idx, result); });
        }, 0);
    }

    private void afterMatch(final Scenario.Step st, int idx, Matcher.Hit hit) {
        if (hit != null) lastScore = Math.max(lastScore, hit.score);
        boolean found = hit != null && hit.score * 100 >= st.threshold;
        if (found) {
            setStatus((loopCount + 1) + "周目・手順" + idx + " 見つけました " + Math.round(hit.score * 100) + "%");
            if (st.type == Scenario.IMAGE || st.tapFound) tapInside(hit, () -> nextStep(st));
            else nextStep(st);
            return;
        }
        long waited = SystemClock.uptimeMillis() - stepStart;
        if (waited >= st.timeoutSec * 1000L) {
            String what = "手順" + idx + "の画像が" + st.timeoutSec + "秒見つかりませんでした（最高一致度 "
                    + Math.round(lastScore * 100) + "%）";
            if (st.skipOnTimeout) {
                notFoundSkips++;
                App.runLog(this, (loopCount + 1) + "周目 " + what + "→次へ");
                nextStep(st);
            } else {
                vibrate();
                stopPlay(what);
            }
            return;
        }
        if (st.type == Scenario.UNTIL && st.act != Scenario.ACT_NONE) {
            int t = st.act == Scenario.ACT_SWIPE ? Scenario.SWIPE : Scenario.TAP;
            send(buildGesture(st, t, true), () -> retryImage(st, waitOf(st)));
        } else {
            retryImage(st, 350 + rnd.nextInt(250));
        }
    }

    private GestureDescription buildGesture(Scenario.Step st, int type, boolean jitter) {
        float dx = 0, dy = 0;
        if (jitter && sc != null && sc.jitterPx > 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * sc.jitterPx;
            dx = (float) (Math.cos(a) * r);
            dy = (float) (Math.sin(a) * r);
        }
        Path p = new Path();
        long dur;
        if (type == Scenario.SWIPE) {
            if (st.path != null && st.path.size() > 2) {
                float[] f = st.path.get(0);
                p.moveTo(clampX(f[0] + dx), clampY(f[1] + dy));
                for (int i = 1; i < st.path.size(); i++) {
                    f = st.path.get(i);
                    p.lineTo(clampX(f[0] + dx), clampY(f[1] + dy));
                }
            } else {
                p.moveTo(clampX(st.x + dx), clampY(st.y + dy));
                p.lineTo(clampX(st.x2 + dx), clampY(st.y2 + dy));
            }
            dur = Math.max(20, jitter ? jitterTime(st.duration) : st.duration);
        } else {
            p.moveTo(clampX(st.x + dx), clampY(st.y + dy));
            dur = type == Scenario.LONG ? Math.max(300, st.duration) : Math.max(1, st.duration);
        }
        return stroke(p, dur);
    }

    // ================= 記録（順番モード） =================

    private class RecordView extends View {
        final Paint p = new Paint();
        final List<float[]> pts = new ArrayList<>();
        long downAt;

        RecordView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0x14FF0000);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xAAFF3B30);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (passing) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pts.clear();
                    downAt = SystemClock.uptimeMillis();
                    pts.add(new float[]{e.getRawX(), e.getRawY()});
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pts.isEmpty()) return true;
                    float[] last = pts.get(pts.size() - 1);
                    if (Math.hypot(e.getRawX() - last[0], e.getRawY() - last[1]) > dp(6))
                        pts.add(new float[]{e.getRawX(), e.getRawY()});
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pts.isEmpty()) return true;
                    pts.add(new float[]{e.getRawX(), e.getRawY()});
                    try {
                        onRecorded(new ArrayList<>(pts), downAt, SystemClock.uptimeMillis());
                    } catch (Throwable ex) {
                        App.log(TapService.this, ex);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pts.clear();
                    return true;
            }
            return true;
        }
    }

    public void startRecord() {
        if (sc == null || playing || recording || busy || rules()) return;
        commitMarkers();
        recording = true;
        lastUpTime = 0;
        recView = new RecordView(this);
        int[] sz = screenSize();
        recLp = overlayLp(sz[0], sz[1]);
        addSafe(recView, recLp);
        raisePanel();
        applyMarkerState();
        btnRec.setText("■");
        setStatus("記録中…操作してください");
        toast("記録中：画面を普通に操作してください。終わったら ■");
    }

    private void onRecorded(List<float[]> pts, long down, long up) {
        if (sc == null) return;
        float[] a = pts.get(0), b = pts.get(pts.size() - 1);
        float dist = 0;
        for (int i = 1; i < pts.size(); i++)
            dist = Math.max(dist, (float) Math.hypot(pts.get(i)[0] - a[0], pts.get(i)[1] - a[1]));
        int dur = (int) Math.max(1, up - down);

        if (lastUpTime > 0 && !sc.steps.isEmpty()) {
            sc.steps.get(sc.steps.size() - 1).after = (int) Math.min(600_000, Math.max(50, down - lastUpTime));
        }

        Scenario.Step st = new Scenario.Step();
        if (dist < dp(12)) {
            st.type = dur >= 450 ? Scenario.LONG : Scenario.TAP;
            st.x = a[0];
            st.y = a[1];
            st.duration = st.type == Scenario.LONG ? dur : Math.max(40, Math.min(200, dur));
        } else {
            st.type = Scenario.SWIPE;
            st.x = a[0]; st.y = a[1]; st.x2 = b[0]; st.y2 = b[1];
            st.duration = Math.max(50, dur);
            st.path = simplify(pts, 30);
        }
        st.after = 800;
        sc.steps.add(st);
        lastUpTime = up;
        setStatus("記録中…" + sc.steps.size() + "手順");

        passing = true;
        recLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recView, recLp); } catch (Exception ignored) { }
        final GestureDescription g = buildGesture(st, st.type, false);
        h.postDelayed(() -> {
            boolean ok = false;
            try {
                ok = dispatchGesture(g, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription d) { h.postDelayed(TapService.this::resumeCapture, 80); }

                    @Override
                    public void onCancelled(GestureDescription d) { h.postDelayed(TapService.this::resumeCapture, 80); }
                }, h);
            } catch (Throwable e) {
                App.log(this, e);
            }
            if (!ok) resumeCapture();
        }, 60);
    }

    private void resumeCapture() {
        passing = false;
        if (!recording || recView == null) return;
        recLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recView, recLp); } catch (Exception ignored) { }
    }

    private static List<float[]> simplify(List<float[]> pts, int max) {
        if (pts.size() <= max) return pts;
        List<float[]> out = new ArrayList<>();
        float step = (pts.size() - 1) / (float) (max - 1);
        for (int i = 0; i < max; i++) out.add(pts.get(Math.round(i * step)));
        return out;
    }

    public void stopRecord() {
        if (!recording) return;
        recording = false;
        passing = false;
        removeSafe(recView);
        recView = null;
        if (btnRec != null) btnRec.setText("●");
        if (sc != null) {
            if (!sc.steps.isEmpty()) sc.steps.get(sc.steps.size() - 1).after = Math.max(500, sc.steps.get(sc.steps.size() - 1).after);
            int[] sz = screenSize();
            sc.screenW = sz[0];
            sc.screenH = sz[1];
            sc.save(this);
            refreshOverlays();
            idleStatus();
        }
        applyMarkerState();
    }
}
