package com.rerise.tapsuke;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
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

/**
 * タップ助の本体。ユーザー補助サービスとして動き、
 *  - 画面に浮かぶ操作パネルと、位置を示すマーカーを出す
 *  - シナリオどおりにタップ・スワイプを実行する
 *  - 実際の操作を記録する
 * 重ね表示は TYPE_ACCESSIBILITY_OVERLAY を使うので「他のアプリの上に表示」の許可は要らない。
 */
public class TapService extends AccessibilityService {

    private static TapService instance;

    public static TapService get() { return instance; }

    public static boolean isOn() { return instance != null; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private WindowManager wm;

    // パネルとマーカー
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView btnPlay, btnRec, btnEye, status;
    private View[] extraButtons;
    private boolean collapsed;
    private final List<Marker> markers = new ArrayList<>();
    private boolean markersVisible = true;
    private int offX = Integer.MIN_VALUE, offY = Integer.MIN_VALUE; // 重ね表示の座標ずれ補正

    private Scenario sc;

    // 実行
    private boolean playing;
    private int stepIndex, loopCount, cancelled;
    private long playStart;

    // 記録
    private RecordView recView;
    private WindowManager.LayoutParams recLp;
    private boolean recording, passing;
    private long lastUpTime;

    // ================= ライフサイクル =================

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (App.prefs(this).getBoolean("panel_on_connect", false)) {
            App.prefs(this).edit().putBoolean("panel_on_connect", false).apply();
            showPanel(App.prefs(this).getString("current", null));
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        stopPlay(null);
        closePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** 音量下キー：実行中なら停止（安全装置） */
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

    // ================= 画面サイズ =================

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

    // ================= パネル =================

    public boolean panelShown() { return panel != null; }

    public String currentId() { return sc == null ? null : sc.id; }

    /** シナリオを読み込んでパネルとマーカーを出す。id が null なら新規 */
    public void showPanel(String id) {
        if (panel != null) {
            if (sc != null && id != null && id.equals(sc.id)) return;
            closePanel();
        }
        Scenario s = Scenario.load(this, id);
        if (s == null) {
            s = new Scenario();
            int n = Scenario.all(this).size() + 1;
            s.name = "シナリオ" + n;
            int[] sz = screenSize();
            s.screenW = sz[0];
            s.screenH = sz[1];
            s.save(this);
        }
        sc = s;
        App.prefs(this).edit().putString("current", sc.id).apply();
        scaleIfNeeded(sc);
        buildPanel();
        rebuildMarkers();
    }

    /** 画面サイズが作成時と違えば比率で座標を直す（回転や別端末） */
    private void scaleIfNeeded(Scenario s) {
        int[] sz = screenSize();
        if (s.screenW <= 0 || s.screenH <= 0) { s.screenW = sz[0]; s.screenH = sz[1]; return; }
        if (s.screenW == sz[0] && s.screenH == sz[1]) return;
        float fx = sz[0] / (float) s.screenW, fy = sz[1] / (float) s.screenH;
        for (Scenario.Step st : s.steps) {
            st.x *= fx; st.y *= fy; st.x2 *= fx; st.y2 *= fy;
            if (st.path != null) for (float[] p : st.path) { p[0] *= fx; p[1] *= fy; }
        }
        s.screenW = sz[0];
        s.screenH = sz[1];
    }

    private TextView pbtn(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setMinHeight(dp(40));
        t.setOnClickListener(l);
        return t;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(Ui.round(0xE0202124, dp(22)));
        panel.setPadding(dp(4), dp(2), dp(8), dp(2));

        TextView handle = pbtn("≡", v -> toggleCollapse());
        attachDrag(handle, true);
        btnPlay = pbtn("▶", v -> { if (playing) stopPlay("停止しました"); else startPlay(); });
        TextView add = pbtn("＋", v -> addStep(Scenario.TAP));
        TextView swipe = pbtn("⇅", v -> addStep(Scenario.SWIPE));
        btnRec = pbtn("●", v -> { if (recording) stopRecord(); else startRecord(); });
        btnRec.setTextColor(0xFFFF6B6B);
        btnEye = pbtn("◉", v -> toggleMarkers());
        TextView edit = pbtn("✎", v -> openEditor());
        TextView close = pbtn("✕", v -> closePanel());
        status = new TextView(this);
        status.setTextColor(0xFFCFCFCF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(dp(4), 0, 0, 0);

        panel.addView(handle);
        panel.addView(btnPlay);
        panel.addView(add);
        panel.addView(swipe);
        panel.addView(btnRec);
        panel.addView(btnEye);
        panel.addView(edit);
        panel.addView(close);
        panel.addView(status);
        extraButtons = new View[]{add, swipe, btnRec, btnEye, edit, close};

        panelLp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        panelLp.x = App.prefs(this).getInt("panel_x", dp(8));
        panelLp.y = App.prefs(this).getInt("panel_y", dp(120));
        wm.addView(panel, panelLp);
        setStatus(sc.name);
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        for (View v : extraButtons) v.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        status.setVisibility(collapsed && !playing ? View.GONE : View.VISIBLE);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    public void closePanel() {
        if (playing) stopPlay(null);
        if (recording) stopRecord();
        commitMarkers();
        if (sc != null) sc.save(this);
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        if (panel != null) removeSafe(panel);
        panel = null;
        sc = null;
    }

    private void removeSafe(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private void openEditor() {
        if (sc == null) return;
        commitMarkers();
        sc.save(this);
        Intent i = new Intent(this, EditActivity.class)
                .putExtra("id", sc.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** 編集画面で保存されたら読み直してマーカーを置き直す */
    public void reload(String id) {
        if (sc == null || !sc.id.equals(id) || playing || recording) return;
        Scenario s = Scenario.load(this, id);
        if (s == null) { closePanel(); return; }
        sc = s;
        scaleIfNeeded(sc);
        rebuildMarkers();
        setStatus(sc.name);
    }

    public void forget(String id) {
        if (sc != null && sc.id.equals(id)) {
            for (Marker m : markers) removeSafe(m.view);
            markers.clear();
            if (panel != null) removeSafe(panel);
            panel = null;
            sc = null;
        }
    }

    /** パネルやマーカーをドラッグで動かせるようにする */
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

    // ================= マーカー =================

    private class Marker {
        View view;
        WindowManager.LayoutParams lp;
        Scenario.Step step;
        boolean end; // スワイプの終点
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

    private int markerSize() { return dp(46); }

    private void rebuildMarkers() {
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        if (sc == null) return;
        int n = 0;
        for (Scenario.Step st : sc.steps) {
            n++;
            if (st.type == Scenario.WAIT) continue;
            int color = st.type == Scenario.TAP ? 0xFF2F80ED : st.type == Scenario.LONG ? 0xFFF2994A : 0xFF27AE60;
            addMarker(st, false, String.valueOf(n), color);
            if (st.type == Scenario.SWIPE) addMarker(st, true, n + "終", 0xFFEB5757);
        }
        // パネルを最前面に戻す
        if (panel != null) {
            removeSafe(panel);
            wm.addView(panel, panelLp);
        }
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
        wm.addView(m.view, m.lp);
        markers.add(m);
    }

    /** 重ね表示の座標と実際の画面座標のずれを一度だけ測って補正する */
    private void calibrate() {
        if (offX != Integer.MIN_VALUE || markers.isEmpty()) return;
        final Marker m = markers.get(0);
        m.view.post(() -> {
            int[] loc = new int[2];
            m.view.getLocationOnScreen(loc);
            offX = loc[0] - m.lp.x;
            offY = loc[1] - m.lp.y;
            if (offX != 0 || offY != 0) rebuildMarkers();
        });
    }

    /** マーカーの今の位置をシナリオに書き戻す */
    private void commitMarkers() {
        if (sc == null) return;
        int size = markerSize();
        for (Marker m : markers) {
            if (!m.view.isAttachedToWindow()) continue;
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

    /** 実行中・記録中はマーカーを触れない半透明にして、タップを邪魔しない */
    private void applyMarkerState() {
        boolean passive = playing || recording;
        for (Marker m : markers) {
            m.view.setVisibility(markersVisible ? View.VISIBLE : View.GONE);
            m.view.setAlpha(passive ? 0.35f : 1f);
            if (passive) m.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else m.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(m.view, m.lp); } catch (Exception ignored) { }
        }
        if (btnEye != null) btnEye.setAlpha(markersVisible ? 1f : 0.4f);
    }

    private void addStep(int type) {
        if (sc == null || playing || recording) return;
        commitMarkers();
        int[] sz = screenSize();
        Scenario.Step st = new Scenario.Step();
        st.type = type;
        // 置く場所を少しずつずらして重ならないように
        int k = sc.steps.size() % 5;
        st.x = sz[0] / 2f + (k - 2) * dp(24);
        st.y = sz[1] * 0.45f + (k - 2) * dp(24);
        if (type == Scenario.SWIPE) {
            // 既定は「下から上へ」＝ページを下にスクロール
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
        rebuildMarkers();
        setStatus(sc.name + "・" + sc.steps.size() + "手順");
    }

    // ================= 実行 =================

    public boolean isPlaying() { return playing; }

    public void startPlay() {
        if (sc == null || playing) return;
        if (recording) stopRecord();
        commitMarkers();
        sc.save(this);
        boolean any = false;
        for (Scenario.Step s : sc.steps) if (s.type != Scenario.WAIT) any = true;
        if (!any) {
            toast("先に「＋」でタップ位置を追加してください");
            return;
        }
        if (pointUnderPanel()) toast("パネルの下にタップ位置があります。パネルを動かしてください");
        playing = true;
        stepIndex = 0;
        loopCount = 0;
        cancelled = 0;
        playStart = SystemClock.uptimeMillis();
        btnPlay.setText("■");
        btnPlay.setTextColor(0xFFFFD166);
        applyMarkerState();
        if (collapsed) status.setVisibility(View.VISIBLE);
        setStatus("1周目");
        h.postDelayed(this::runStep, 300);
    }

    private boolean pointUnderPanel() {
        if (panel == null) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight());
        for (Scenario.Step s : sc.steps) {
            if (s.type == Scenario.WAIT) continue;
            if (r.contains((int) s.x, (int) s.y)) return true;
            if (s.type == Scenario.SWIPE && r.contains((int) s.x2, (int) s.y2)) return true;
        }
        return false;
    }

    public void stopPlay(String msg) {
        if (!playing) return;
        playing = false;
        h.removeCallbacksAndMessages(null);
        if (btnPlay != null) {
            btnPlay.setText("▶");
            btnPlay.setTextColor(Color.WHITE);
        }
        applyMarkerState();
        if (collapsed && status != null) status.setVisibility(View.GONE);
        String done = loopCount + "周";
        setStatus(sc == null ? "" : sc.name + "・" + done + "で停止");
        if (msg != null) toast(msg + "（" + done + "）");
    }

    private int jitterTime(int ms) {
        if (sc.jitterPct <= 0 || ms <= 0) return ms;
        double f = 1 + (rnd.nextDouble() * 2 - 1) * sc.jitterPct / 100.0;
        return (int) Math.max(0, Math.round(ms * f));
    }

    private void runStep() {
        if (!playing || sc == null) return;
        // 停止条件
        if (sc.maxMinutes > 0 && SystemClock.uptimeMillis() - playStart >= sc.maxMinutes * 60_000L) {
            stopPlay("指定時間が経ったので停止しました");
            return;
        }
        if (stepIndex >= sc.steps.size()) {
            loopCount++;
            stepIndex = 0;
            if (sc.repeat > 0 && loopCount >= sc.repeat) {
                stopPlay("完了しました");
                return;
            }
            setStatus((loopCount + 1) + "周目" + (sc.repeat > 0 ? " / " + sc.repeat : ""));
            if (sc.loopWait > 0) {
                h.postDelayed(this::runStep, jitterTime(sc.loopWait));
                return;
            }
        }
        final Scenario.Step st = sc.steps.get(stepIndex++);
        if (st.type == Scenario.WAIT) {
            h.postDelayed(this::runStep, jitterTime(st.after));
            return;
        }
        GestureDescription g = buildGesture(st, true);
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                if (playing) h.postDelayed(TapService.this::runStep, jitterTime(st.after));
            }

            @Override
            public void onCancelled(GestureDescription d) {
                cancelled++;
                if (playing) h.postDelayed(TapService.this::runStep, jitterTime(st.after));
            }
        }, h);
        if (!ok) stopPlay("操作を送れませんでした。ユーザー補助の設定を確認してください");
    }

    private GestureDescription buildGesture(Scenario.Step st, boolean jitter) {
        float dx = 0, dy = 0;
        if (jitter && sc != null && sc.jitterPx > 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * sc.jitterPx;
            dx = (float) (Math.cos(a) * r);
            dy = (float) (Math.sin(a) * r);
        }
        int[] sz = screenSize();
        Path p = new Path();
        long dur;
        if (st.type == Scenario.SWIPE) {
            if (st.path != null && st.path.size() > 2) {
                float[] f = st.path.get(0);
                p.moveTo(clamp(f[0] + dx, sz[0]), clamp(f[1] + dy, sz[1]));
                for (int i = 1; i < st.path.size(); i++) {
                    f = st.path.get(i);
                    p.lineTo(clamp(f[0] + dx, sz[0]), clamp(f[1] + dy, sz[1]));
                }
            } else {
                p.moveTo(clamp(st.x + dx, sz[0]), clamp(st.y + dy, sz[1]));
                p.lineTo(clamp(st.x2 + dx, sz[0]), clamp(st.y2 + dy, sz[1]));
            }
            dur = Math.max(20, st.duration);
        } else {
            p.moveTo(clamp(st.x + dx, sz[0]), clamp(st.y + dy, sz[1]));
            dur = st.type == Scenario.LONG ? Math.max(300, st.duration) : Math.max(1, st.duration);
        }
        dur = Math.min(dur, GestureDescription.getMaxGestureDuration());
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
    }

    private static float clamp(float v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    // ================= 記録 =================

    /** 記録中の全画面の受け皿。触った操作を記録し、そのまま下のアプリにも流す */
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
                    float[] last = pts.get(pts.size() - 1);
                    if (Math.hypot(e.getRawX() - last[0], e.getRawY() - last[1]) > dp(6))
                        pts.add(new float[]{e.getRawX(), e.getRawY()});
                    return true;
                case MotionEvent.ACTION_UP:
                    pts.add(new float[]{e.getRawX(), e.getRawY()});
                    onRecorded(new ArrayList<>(pts), downAt, SystemClock.uptimeMillis());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pts.clear();
                    return true;
            }
            return true;
        }
    }

    public void startRecord() {
        if (sc == null || playing || recording) return;
        commitMarkers();
        recording = true;
        lastUpTime = 0;
        recView = new RecordView(this);
        int[] sz = screenSize();
        recLp = overlayLp(sz[0], sz[1]);
        recLp.x = 0;
        recLp.y = 0;
        wm.addView(recView, recLp);
        // パネルを最前面に戻す
        removeSafe(panel);
        wm.addView(panel, panelLp);
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

        // 前の操作との間隔を、前のステップの「あと待つ時間」にする
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

        // 同じ操作を下のアプリにも届ける
        passing = true;
        recLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recView, recLp); } catch (Exception ignored) { }
        final GestureDescription g = buildGesture(st, false);
        h.postDelayed(() -> {
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) { h.postDelayed(TapService.this::resumeCapture, 80); }

                @Override
                public void onCancelled(GestureDescription d) { h.postDelayed(TapService.this::resumeCapture, 80); }
            }, h);
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
            rebuildMarkers();
            setStatus(sc.name + "・" + sc.steps.size() + "手順");
        }
        applyMarkerState();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
