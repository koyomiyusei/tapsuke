package com.rerise.tapsuke;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
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
import android.view.ContextThemeWrapper;
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
 * タップ助の本体。ユーザー補助サービスとして動き、
 *  - 画面に浮かぶ操作パネルと、位置を示すマーカーを出す
 *  - シナリオどおりにタップ・スワイプを実行する
 *  - お手本画像を画面から探して、見つけた場所をタップする（v1.1）
 *  - 実際の操作を記録する
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

    // パネルとマーカー
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView btnPlay, btnRec, btnEye, status;
    private View[] extraButtons;
    private boolean collapsed;
    private final List<Marker> markers = new ArrayList<>();
    private boolean markersVisible = true;
    private int offX = Integer.MIN_VALUE, offY = Integer.MIN_VALUE;

    private Scenario sc;

    // 実行
    private boolean playing;
    private int token;              // 停止したら古い処理を無視するための番号
    private int stepIndex, loopCount, notFoundSkips;
    private long playStart, stepStart;
    private float lastScore;

    // 記録
    private RecordView recView;
    private WindowManager.LayoutParams recLp;
    private boolean recording, passing;
    private long lastUpTime;

    // お手本の切り出し
    private View cropView, cropBar;
    private Bitmap cropShot;

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
        closeCrop();
        closePanel();
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

    // ================= 画面サイズ・重ね表示 =================

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

    private void removeSafe(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    /** サービスの上に出すダイアログ（どのアプリの上でも出せる） */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert));
    }

    private void showOverlay(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        d.show();
    }

    // ================= パネル =================

    public boolean panelShown() { return panel != null; }

    public String currentId() { return sc == null ? null : sc.id; }

    public void showPanel(String id) {
        if (panel != null) {
            if (sc != null && id != null && id.equals(sc.id)) return;
            closePanel();
        }
        Scenario s = Scenario.load(this, id);
        if (s == null) {
            s = new Scenario();
            s.name = "シナリオ" + (Scenario.all(this).size() + 1);
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(37));
        t.setMinHeight(dp(38));
        t.setOnClickListener(l);
        return t;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(0xE0202124, dp(18)));
        panel.setPadding(dp(4), dp(2), dp(6), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView handle = pbtn("≡", v -> toggleCollapse());
        attachDrag(handle, true);
        btnPlay = pbtn("▶", v -> { if (playing) stopPlay("停止しました"); else startPlay(); });
        TextView add = pbtn("＋", v -> addStep(Scenario.TAP));
        TextView swipe = pbtn("⇅", v -> addStep(Scenario.SWIPE));
        TextView cam = pbtn("◫", v -> startCapture());
        btnRec = pbtn("●", v -> { if (recording) stopRecord(); else startRecord(); });
        btnRec.setTextColor(0xFFFF6B6B);
        btnEye = pbtn("◉", v -> toggleMarkers());
        TextView edit = pbtn("✎", v -> openEditor());
        TextView close = pbtn("✕", v -> closePanel());

        row.addView(handle);
        row.addView(btnPlay);
        row.addView(add);
        row.addView(swipe);
        row.addView(cam);
        row.addView(btnRec);
        row.addView(btnEye);
        row.addView(edit);
        row.addView(close);
        panel.addView(row);

        status = new TextView(this);
        status.setTextColor(0xFFCFCFCF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(8), 0, dp(4), 0);
        status.setSingleLine(true);
        panel.addView(status);
        extraButtons = new View[]{add, swipe, cam, btnRec, btnEye, edit, close};

        panelLp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        panelLp.x = App.prefs(this).getInt("panel_x", dp(8));
        panelLp.y = App.prefs(this).getInt("panel_y", dp(120));
        wm.addView(panel, panelLp);
        setStatus(sc.name);
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
        commitMarkers();
        if (sc != null) sc.save(this);
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        if (panel != null) removeSafe(panel);
        panel = null;
        sc = null;
    }

    private void openEditor() {
        if (sc == null || playing) return;
        commitMarkers();
        sc.save(this);
        startActivity(new Intent(this, EditActivity.class)
                .putExtra("id", sc.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

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
            if (playing) stopPlay(null);
            for (Marker m : markers) removeSafe(m.view);
            markers.clear();
            if (panel != null) removeSafe(panel);
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

    // ================= マーカー =================

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

    private int markerSize() { return dp(46); }

    private void rebuildMarkers() {
        for (Marker m : markers) removeSafe(m.view);
        markers.clear();
        if (sc == null) return;
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

    private void calibrate() {
        if (offX != Integer.MIN_VALUE || markers.isEmpty()) return;
        final Marker m = markers.get(0);
        m.view.post(() -> {
            if (!m.view.isAttachedToWindow()) return;
            int[] loc = new int[2];
            m.view.getLocationOnScreen(loc);
            offX = loc[0] - m.lp.x;
            offY = loc[1] - m.lp.y;
            if (offX != 0 || offY != 0) rebuildMarkers();
        });
    }

    private void commitMarkers() {
        if (sc == null) return;
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

    private boolean hasImageSteps() {
        if (sc == null) return false;
        for (Scenario.Step s : sc.steps) if (s.isImage()) return true;
        return false;
    }

    /** 実行中・記録中はマーカーを触れないようにする。画像の手順があるときは照合の邪魔をしないよう隠す */
    private void applyMarkerState() {
        boolean passive = playing || recording;
        boolean hide = !markersVisible || (playing && hasImageSteps());
        for (Marker m : markers) {
            m.view.setVisibility(hide ? View.GONE : View.VISIBLE);
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
        rebuildMarkers();
        setStatus(sc.name + "・" + sc.steps.size() + "手順");
    }

    // ================= 画面の撮影（照合用。保存しない） =================

    interface ShotCallback { void done(Bitmap b, String error); }

    /** 画面を1枚撮る。呼び出し側は使い終わったら recycle する */
    private void shoot(final ShotCallback cb, final int retry) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.done(null, "画像の手順は Android 11 以上で使えます");
            return;
        }
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
                } catch (Exception e) {
                    App.log(TapService.this, e);
                } finally {
                    hb.close();
                }
                final Bitmap out = soft;
                cb.done(out, out == null ? "画面を読み取れませんでした" : null);
            }

            @Override
            public void onFailure(int code) {
                // 連続で撮りすぎ（1秒に約3回が上限）のときは少し待って撮り直す
                if (retry < 5) {
                    h.postDelayed(() -> shoot(cb, retry + 1), 200 + rnd.nextInt(120));
                } else {
                    cb.done(null, "画面を撮れませんでした（コード " + code + "）");
                }
            }
        });
    }

    // ================= お手本の登録 =================

    private void startCapture() {
        if (sc == null || playing || recording || cropView != null) return;
        if (Build.VERSION.SDK_INT < 30) { toast("画像の手順は Android 11 以上で使えます"); return; }
        commitMarkers();
        panel.setVisibility(View.INVISIBLE);
        for (Marker m : markers) m.view.setVisibility(View.GONE);
        h.postDelayed(() -> shoot((b, err) -> h.post(() -> {
            if (b == null) {
                restoreAfterCrop();
                toast(err);
                return;
            }
            showCrop(b);
        }), 0), 350);
    }

    /** 撮った画面の上で、お手本にしたい部分を指で囲む */
    private class CropView extends View {
        final Bitmap shot;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float sx, sy, ex, ey;
        boolean has;

        CropView(Context c, Bitmap shot) {
            super(c);
            this.shot = shot;
        }

        RectF rect() {
            return new RectF(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey));
        }

        @Override
        protected void onDraw(Canvas c) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            c.drawBitmap(shot, -loc[0], -loc[1], null);
            c.drawColor(0x55000000);
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
            p.setColor(Color.WHITE);
            p.setTextSize(dp(15));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("お手本にする部分を指で囲んでください", getWidth() / 2f, dp(90), p);
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

    private void showCrop(Bitmap shot) {
        cropShot = shot;
        final CropView cv = new CropView(this, shot);
        int[] sz = screenSize();
        WindowManager.LayoutParams lp = overlayLp(sz[0], sz[1]);
        cropView = cv;
        wm.addView(cv, lp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.round(0xF0202124, dp(14)));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(barBtn("やめる", v -> { closeCrop(); restoreAfterCrop(); }));
        bar.addView(barBtn("やり直す", v -> { cv.has = false; cv.invalidate(); }));
        TextView ok = barBtn("登録", v -> registerCrop(cv));
        ok.setTextColor(0xFFFFD166);
        bar.addView(ok);
        WindowManager.LayoutParams blp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        blp.y = dp(70);
        cropBar = bar;
        wm.addView(bar, blp);
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

    private void registerCrop(CropView cv) {
        if (!cv.has) { toast("先に指で囲んでください"); return; }
        RectF r = cv.rect();
        int x = Math.max(0, Math.round(r.left)), y = Math.max(0, Math.round(r.top));
        int w = Math.min(cropShot.getWidth() - x, Math.round(r.width()));
        int hh = Math.min(cropShot.getHeight() - y, Math.round(r.height()));
        if (w < dp(12) || hh < dp(12)) { toast("もう少し大きく囲んでください"); return; }
        Bitmap crop = Bitmap.createBitmap(cropShot, x, y, w, hh);
        Matcher.Tpl t = Matcher.prepare(crop);
        if (t.flat) { toast("ほぼ単色です。模様や文字のある部分を囲んでください"); crop.recycle(); return; }
        final String id = Templates.save(this, crop);
        crop.recycle();
        final float cx = x + w / 2f, cy = y + hh / 2f;
        closeCrop();

        String[] items = {"見つけたらタップ", "出るまで待つ", "出るまでタップをくり返す", "出るまでスワイプをくり返す"};
        AlertDialog d = dialog().setTitle("この画像で何をしますか？")
                .setItems(items, (di, which) -> addImageStep(id, which, cx, cy))
                .setOnCancelListener(di -> { Templates.cleanup(this); restoreAfterCrop(); })
                .create();
        showOverlay(d);
    }

    private void addImageStep(String id, int which, float cx, float cy) {
        int[] sz = screenSize();
        Scenario.Step st = new Scenario.Step();
        st.tpl = id;
        st.x = cx;
        st.y = cy;
        if (which == 0) {
            st.type = Scenario.IMAGE;
            st.after = 800;
            st.afterMax = 1500;
        } else {
            st.type = Scenario.UNTIL;
            st.timeoutSec = 300;
            st.after = 800;
            st.afterMax = 1500;
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
        sc.save(this);
        restoreAfterCrop();
        markersVisible = true;
        rebuildMarkers();
        setStatus(sc.name + "・" + sc.steps.size() + "手順");
        toast(which >= 2 ? "紫の丸をくり返す操作の位置に動かしてください" : "画像の手順を追加しました");
    }

    private void closeCrop() {
        removeSafe(cropView);
        removeSafe(cropBar);
        cropView = null;
        cropBar = null;
        if (cropShot != null) { cropShot.recycle(); cropShot = null; } // 撮った画面は残さない
    }

    private void restoreAfterCrop() {
        if (panel != null) panel.setVisibility(View.VISIBLE);
        applyMarkerState();
    }

    // ================= 実行 =================

    public boolean isPlaying() { return playing; }

    public void startPlay() {
        if (sc == null || playing) return;
        if (recording) stopRecord();
        commitMarkers();
        sc.save(this);
        if (sc.steps.isEmpty()) {
            toast("先に手順を追加してください");
            return;
        }
        for (Scenario.Step s : sc.steps) {
            if (s.isImage() && !Templates.exists(this, s.tpl)) {
                toast((sc.steps.indexOf(s) + 1) + "番目の画像がありません。登録し直してください");
                return;
            }
        }
        if (hasImageSteps() && Build.VERSION.SDK_INT < 30) {
            toast("画像の手順は Android 11 以上で使えます");
            return;
        }
        if (pointUnderPanel()) toast("パネルの下にタップ位置があります。パネルを動かしてください");
        playing = true;
        token++;
        stepIndex = 0;
        loopCount = 0;
        notFoundSkips = 0;
        playStart = SystemClock.uptimeMillis();
        btnPlay.setText("■");
        btnPlay.setTextColor(0xFFFFD166);
        applyMarkerState();
        setStatus("1周目");
        App.runLog(this, "開始「" + sc.name + "」");
        final int tk = token;
        h.postDelayed(() -> { if (tk == token) runStep(); }, 300);
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
        applyMarkerState();
        String done = loopCount + "周・" + elapsed();
        setStatus(sc == null ? "" : sc.name + "・" + done + "で停止");
        App.runLog(this, "停止 " + done + (notFoundSkips > 0 ? "・見失い" + notFoundSkips + "回" : "")
                + (msg != null ? "・" + msg : ""));
        if (msg != null) toast(msg + "（" + done + "）");
    }

    private String elapsed() {
        long s = (SystemClock.uptimeMillis() - playStart) / 1000;
        return s >= 3600 ? (s / 3600) + "時間" + (s % 3600 / 60) + "分" : (s / 60) + "分" + (s % 60) + "秒";
    }

    /** 待ち時間：範囲指定があれば その間でランダム、なければ ±% のズレ */
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

    /** 同じ画像の手順をもう一度（待ち時間の計測はそのまま続ける） */
    private void retryImage(final Scenario.Step st, int ms) {
        final int tk = token;
        h.postDelayed(() -> {
            if (tk != token || !playing) return;
            if (sc.maxMinutes > 0 && SystemClock.uptimeMillis() - playStart >= sc.maxMinutes * 60_000L) {
                stopPlay("指定時間が経ったので停止しました");
                return;
            }
            imageCycle(st);
        }, Math.max(0, ms));
    }

    private void nextStep(Scenario.Step st) {
        stepIndex++;
        later(waitOf(st));
    }

    private void runStep() {
        if (!playing || sc == null) return;
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
                gesture(st, st.type, () -> nextStep(st));
        }
    }

    /** 画像の手順の1サイクル：撮る → 探す → 見つかれば先へ / なければ（くり返し操作をして）また撮る */
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
                shot.recycle(); // 照合が終わったら画面はすぐ捨てる
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
            if (st.type == Scenario.IMAGE || st.tapFound) {
                tapInside(hit, () -> nextStep(st));
            } else {
                nextStep(st);
            }
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
            gesture(st, t, () -> retryImage(st, waitOf(st)));
        } else {
            retryImage(st, 350 + rnd.nextInt(250));
        }
    }

    /** 見つけた画像の内側（中央寄りの範囲）のどこかをタップ */
    private void tapInside(Matcher.Hit hit, Runnable then) {
        float rx = hit.w * 0.25f, ry = hit.h * 0.25f;
        float x = hit.cx + (rnd.nextFloat() * 2 - 1) * rx;
        float y = hit.cy + (rnd.nextFloat() * 2 - 1) * ry;
        Scenario.Step t = new Scenario.Step();
        t.type = Scenario.TAP;
        t.x = x;
        t.y = y;
        t.duration = 40 + rnd.nextInt(80);
        gesture(t, Scenario.TAP, then);
    }

    private void gesture(Scenario.Step st, int type, final Runnable then) {
        final int tk = token;
        GestureDescription g = buildGesture(st, type, true);
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) { if (tk == token && playing) then.run(); }

            @Override
            public void onCancelled(GestureDescription d) { if (tk == token && playing) then.run(); }
        }, h);
        if (!ok) stopPlay("操作を送れませんでした。ユーザー補助の設定を確認してください");
    }

    private GestureDescription buildGesture(Scenario.Step st, int type, boolean jitter) {
        float dx = 0, dy = 0;
        if (jitter && sc != null && sc.jitterPx > 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * sc.jitterPx;
            dx = (float) (Math.cos(a) * r);
            dy = (float) (Math.sin(a) * r);
        }
        int[] sz = screenSize();
        Path p = new Path();
        long dur;
        if (type == Scenario.SWIPE) {
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
            dur = Math.max(20, jitter ? jitterTime(st.duration) : st.duration);
        } else {
            p.moveTo(clamp(st.x + dx, sz[0]), clamp(st.y + dy, sz[1]));
            dur = type == Scenario.LONG ? Math.max(300, st.duration) : Math.max(1, st.duration);
        }
        dur = Math.min(dur, GestureDescription.getMaxGestureDuration());
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
    }

    private static float clamp(float v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
        } catch (Exception ignored) {
        }
    }

    // ================= 記録 =================

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
        wm.addView(recView, recLp);
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
