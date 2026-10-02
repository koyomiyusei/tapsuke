package com.rerise.tapsuke;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.Map;

/**
 * お手本画像が画面のどこにあるかを探す（正規化相互相関によるテンプレートマッチング）。
 * 画素の明るさの並びを数値で比べるだけで、文字を読んだり内容を判断したりはしない。
 * 速度のため、まず縮小した画面でおおまかに探し、候補の周りだけ細かく確かめる。
 */
public class Matcher {

    /** 処理の基準は実画面の 1/2 */
    static final int BASE = 2;
    /** 粗い探索でお手本がこの画素数以下になるまで縮める */
    static final int COARSE_AREA = 700;

    public static class Gray {
        final int w, h;
        final float[] p;

        Gray(int w, int h) { this.w = w; this.h = h; this.p = new float[w * h]; }
    }

    /** お手本の、ある縮小率での姿 */
    static class Level {
        Gray g;
        float[] z;   // 平均を引いた値
        double n;    // そのノルム
    }

    public static class Tpl {
        int fullW, fullH;
        Gray half;
        int c;               // 粗い探索での縮小率（half に対して）
        boolean flat;        // ほぼ単色（照合に向かない）
        private final Map<Integer, Level> levels = new HashMap<>();

        synchronized Level level(int k) {
            Level l = levels.get(k);
            if (l == null) {
                l = new Level();
                l.g = down(half, k);
                l.z = new float[l.g.p.length];
                l.n = zeroMean(l.g, l.z);
                levels.put(k, l);
            }
            return l;
        }
    }

    public static class Hit {
        public float cx, cy;   // 実画面での中心
        public int w, h;       // 実画面での大きさ
        public float score;    // 0〜1
    }

    // ---------------- 前処理 ----------------

    static Gray toGray(Bitmap b, int factor) {
        int w = Math.max(1, b.getWidth() / factor), h = Math.max(1, b.getHeight() / factor);
        Bitmap s = (factor == 1) ? b : Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        if (s != b) s.recycle();
        Gray g = new Gray(w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g.p[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        return g;
    }

    static Gray down(Gray g, int k) {
        if (k == 1) return g;
        int w = Math.max(1, g.w / k), h = Math.max(1, g.h / k);
        Gray o = new Gray(w, h);
        float inv = 1f / (k * k);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int dy = 0; dy < k; dy++) {
                    int row = (y * k + dy) * g.w + x * k;
                    for (int dx = 0; dx < k; dx++) s += g.p[row + dx];
                }
                o.p[y * w + x] = s * inv;
            }
        return o;
    }

    private static double zeroMean(Gray g, float[] out) {
        double m = 0;
        for (float v : g.p) m += v;
        m /= g.p.length;
        double n = 0;
        for (int i = 0; i < g.p.length; i++) {
            out[i] = (float) (g.p[i] - m);
            n += out[i] * out[i];
        }
        return Math.sqrt(n);
    }

    public static Tpl prepare(Bitmap full) {
        Tpl t = new Tpl();
        t.fullW = full.getWidth();
        t.fullH = full.getHeight();
        t.half = toGray(full, BASE);
        int c = 1;
        while (c < 16 && (t.half.w / c) * (t.half.h / c) > COARSE_AREA
                && t.half.w / (c * 2) >= 6 && t.half.h / (c * 2) >= 6) c *= 2;
        if (c == 1 && Math.min(t.half.w, t.half.h) >= 12) c = 2;
        t.c = c;
        Level l1 = t.level(1);
        t.level(c);
        t.flat = l1.n / Math.sqrt(l1.z.length) < 3.0;
        return t;
    }

    // ---------------- 照合 ----------------

    /** 1枚の画面に対する前処理の使い回し */
    public static class Frame {
        final Gray half;
        final Map<Integer, Gray> coarse = new HashMap<>();
        final Map<Integer, double[][]> integ = new HashMap<>();
        final int fullW, fullH;

        public Frame(Bitmap screen) {
            fullW = screen.getWidth();
            fullH = screen.getHeight();
            half = toGray(screen, BASE);
        }

        Gray at(int c) {
            if (c == 1) return half;
            Gray g = coarse.get(c);
            if (g == null) { g = down(half, c); coarse.put(c, g); }
            return g;
        }

        double[][] integral(int c) {
            double[][] r = integ.get(c);
            if (r != null) return r;
            Gray g = at(c);
            int W = g.w + 1;
            double[] s = new double[W * (g.h + 1)], ss = new double[W * (g.h + 1)];
            for (int y = 0; y < g.h; y++) {
                double rs = 0, rss = 0;
                for (int x = 0; x < g.w; x++) {
                    double v = g.p[y * g.w + x];
                    rs += v; rss += v * v;
                    s[(y + 1) * W + x + 1] = s[y * W + x + 1] + rs;
                    ss[(y + 1) * W + x + 1] = ss[y * W + x + 1] + rss;
                }
            }
            r = new double[][]{s, ss};
            integ.put(c, r);
            return r;
        }
    }

    /** (x,y) 位置での一致度（積分画像を使う。粗い探索用） */
    private static float ncc(Gray img, double[][] in, float[] tz, double tn, int tw, int th, int x, int y) {
        int W = img.w + 1, n = tw * th;
        double[] s = in[0], ss = in[1];
        int a = y * W + x, b = y * W + x + tw, c = (y + th) * W + x, d = (y + th) * W + x + tw;
        double sum = s[d] - s[b] - s[c] + s[a];
        double sq = ss[d] - ss[b] - ss[c] + ss[a];
        double var = sq - sum * sum / n;
        if (var <= 1e-6 || tn <= 1e-6) return 0;
        double dot = 0;
        for (int j = 0; j < th; j++) {
            int ir = (y + j) * img.w + x, tr = j * tw;
            for (int i = 0; i < tw; i++) dot += img.p[ir + i] * tz[tr + i];
        }
        return (float) (dot / (Math.sqrt(var) * tn));
    }

    /** (x,y) 位置での一致度（その場で計算。候補の周りを少しだけ確かめる用） */
    private static float nccDirect(Gray img, Level l, int x, int y) {
        int tw = l.g.w, th = l.g.h, n = tw * th;
        double sum = 0, sq = 0, dot = 0;
        for (int j = 0; j < th; j++) {
            int ir = (y + j) * img.w + x, tr = j * tw;
            for (int i = 0; i < tw; i++) {
                float v = img.p[ir + i];
                sum += v;
                sq += v * v;
                dot += v * l.z[tr + i];
            }
        }
        double var = sq - sum * sum / n;
        if (var <= 1e-6 || l.n <= 1e-6) return 0;
        return (float) (dot / (Math.sqrt(var) * l.n));
    }

    /** 画面からお手本を探す。見つからなくても一番近い場所と一致度を返す（判定は呼び出し側） */
    public static Hit find(Frame f, Tpl t) {
        if (t.half.w > f.half.w || t.half.h > f.half.h) return null;
        Level cl = t.level(t.c);
        Gray cg = f.at(t.c);
        int tw = cl.g.w, th = cl.g.h;
        if (tw > cg.w || th > cg.h) return null;
        double[][] cin = f.integral(t.c);

        // 粗い探索：上位3か所を拾う
        int nx = cg.w - tw + 1, ny = cg.h - th + 1;
        float[] sc = new float[nx * ny];
        for (int y = 0; y < ny; y++)
            for (int x = 0; x < nx; x++) sc[y * nx + x] = ncc(cg, cin, cl.z, cl.n, tw, th, x, y);

        int[][] cand = new int[3][];
        for (int k = 0; k < 3; k++) {
            int bi = -1;
            float bv = -2;
            for (int i = 0; i < sc.length; i++) if (sc[i] > bv) { bv = sc[i]; bi = i; }
            if (bi < 0 || bv <= -1) break;
            int bx = bi % nx, by = bi / nx;
            cand[k] = new int[]{bx, by};
            int rx = Math.max(1, tw / 2), ry = Math.max(1, th / 2);
            for (int y = Math.max(0, by - ry); y <= Math.min(ny - 1, by + ry); y++)
                for (int x = Math.max(0, bx - rx); x <= Math.min(nx - 1, bx + rx); x++) sc[y * nx + x] = -2;
        }

        // 段階的に細かくして確かめる（各段で候補の周り ±2 だけ）
        float best = -2;
        int bx = 0, by = 0;
        for (int[] cd : cand) {
            if (cd == null) continue;
            int px = cd[0], py = cd[1];
            float v = -2;
            if (t.c == 1) v = nccDirect(f.half, t.level(1), px, py);
            for (int k = t.c / 2; k >= 1; k /= 2) {
                Level l = t.level(k);
                Gray g = f.at(k);
                int mx = g.w - l.g.w, my = g.h - l.g.h;
                if (mx < 0 || my < 0) { v = -2; break; }
                int cx = px * 2, cy = py * 2;
                v = -2;
                for (int y = Math.max(0, cy - 2); y <= Math.min(my, cy + 3); y++)
                    for (int x = Math.max(0, cx - 2); x <= Math.min(mx, cx + 3); x++) {
                        float s2 = nccDirect(g, l, x, y);
                        if (s2 > v) { v = s2; px = x; py = y; }
                    }
            }
            if (v > best) { best = v; bx = px; by = py; }
        }
        if (best <= -2) return null;
        Hit h = new Hit();
        h.w = t.fullW;
        h.h = t.fullH;
        h.cx = bx * BASE + t.fullW / 2f;
        h.cy = by * BASE + t.fullH / 2f;
        h.score = Math.max(0, best);
        return h;
    }
}
