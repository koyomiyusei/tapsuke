package com.rerise.tapsuke;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 初回設定のガイドと使い方 */
public class GuideActivity extends Activity {

    private TextView s1, s2, s3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        root.addView(Ui.text(this, "設定ガイド", 22, true));
        root.addView(Ui.subText(this, "最初の1回だけ。上から順に進めてください。"), Ui.mw(this, 4));

        // 1. 制限付き設定
        LinearLayout c1 = Ui.vbox(this);
        s1 = Ui.text(this, "", 16, true);
        c1.addView(s1);
        c1.addView(Ui.subText(this,
                "ストア以外から入れたアプリは、ユーザー補助をオンにする前に許可が要ります（Android 13以降）。\n"
                        + "① 下のボタンでアプリ情報を開く\n"
                        + "② 右上の「︙」→「制限付き設定を許可」\n"
                        + "③ 指紋やPINで確認\n"
                        + "※メニューに出ない場合は、先に②の手順2でタップ助をオンにしようとしてから戻ると出てきます。"),
                Ui.mw(this, 6));
        c1.addView(Ui.button(this, "アプリ情報を開く", v -> openAppInfo()), Ui.mw(this, 8));
        root.addView(Ui.cardView(this, c1), Ui.mw(this, 14));

        // 2. ユーザー補助
        LinearLayout c2 = Ui.vbox(this);
        s2 = Ui.text(this, "", 16, true);
        c2.addView(s2);
        c2.addView(Ui.subText(this,
                "タップ・スワイプを代わりに行うために使います。画面の内容を読んだり送信したりはしません。\n"
                        + "Galaxyの場合：「インストール済みアプリ」→「タップ助」→ オン"),
                Ui.mw(this, 6));
        c2.addView(Ui.primary(this, "ユーザー補助の設定を開く", v -> openA11y()), Ui.mw(this, 8));
        root.addView(Ui.cardView(this, c2), Ui.mw(this, 10));

        // 3. 電池
        LinearLayout c3 = Ui.vbox(this);
        s3 = Ui.text(this, "", 16, true);
        c3.addView(s3);
        c3.addView(Ui.subText(this,
                "長時間の実行中に止められないようにします。Galaxyは特に止められやすいので推奨です。"),
                Ui.mw(this, 6));
        c3.addView(Ui.button(this, "電池の最適化を外す", v -> openBattery()), Ui.mw(this, 8));
        root.addView(Ui.cardView(this, c3), Ui.mw(this, 10));

        // 設定
        LinearLayout c4 = Ui.vbox(this);
        c4.addView(Ui.text(this, "安全装置", 16, true));
        Switch vol = new Switch(this);
        vol.setText("実行中・記録中は音量の下キーで停止");
        vol.setTextColor(Ui.fg(this));
        vol.setChecked(App.prefs(this).getBoolean("vol_stop", true));
        vol.setOnCheckedChangeListener((btn, on) -> App.prefs(this).edit().putBoolean("vol_stop", on).apply());
        c4.addView(vol, Ui.mw(this, 6));
        Switch upd = new Switch(this);
        upd.setText("起動時に更新を自動確認する（オフにすると通信は「更新確認」を押したときだけ）");
        upd.setTextColor(Ui.fg(this));
        upd.setChecked(App.prefs(this).getBoolean("auto_update", true));
        upd.setOnCheckedChangeListener((btn, on) -> App.prefs(this).edit().putBoolean("auto_update", on).apply());
        c4.addView(upd, Ui.mw(this, 6));
        root.addView(Ui.cardView(this, c4), Ui.mw(this, 10));

        LinearLayout cp = Ui.vbox(this);
        cp.addView(Ui.text(this, "画面の扱い", 16, true));
        cp.addView(Ui.subText(this,
                "・画像の手順では画面を撮って、お手本と同じ部分があるかを数値で比べるだけです。文字を読んだり内容を判断したりはしません\n"
                        + "・撮った画面は比べたらすぐ捨てます。保存も送信もしません\n"
                        + "・お手本画像はこのアプリ専用の領域にだけ保存します（書き出しを選んだときだけファイルに含まれます）\n"
                        + "・画面の文字や部品の情報は読み取らない設定です\n"
                        + "・通信は更新確認（GitHubのバージョン情報を読む）だけです"), Ui.mw(this, 6));
        root.addView(Ui.cardView(this, cp), Ui.mw(this, 10));

        // 使い方
        LinearLayout c5 = Ui.vbox(this);
        c5.addView(Ui.text(this, "使い方", 16, true));
        c5.addView(Ui.subText(this,
                "パネルのボタン\n"
                        + "≡　ドラッグで移動／タップで折りたたみ\n"
                        + "▶　実行（■で停止）\n"
                        + "＋　タップ位置を追加（マーカーをドラッグして合わせる）\n"
                        + "⇅　スワイプを追加（緑＝始点、赤＝終点）\n"
                        + "◫　画面からお手本画像を登録（見つけたらタップ／出るまで待つ・タップ・スワイプをくり返す）\n"
                        + "●　記録。普通に操作すると、その操作が手順として残る（■で終了）\n"
                        + "◉　マーカーの表示・非表示\n"
                        + "✎　細かい編集（回数・時間・ランダム化・順番）\n"
                        + "✕　パネルを閉じる（自動で保存）\n\n"
                        + "コツ\n"
                        + "・Webページを読み進めるなら「⇅」を1つ置いて、そのあと待つ時間を1〜2秒に\n"
                        + "・タップ位置がパネルの下にあると、パネルを押してしまいます。パネルは端に寄せてください\n"
                        + "・ランダム化（位置±10px・間隔±20%くらい）を入れると機械的な動きが減ります\n"
                        + "・画面の向きを変えると位置がずれます。作ったときと同じ向きで使ってください\n"
                        + "・画像が見つからないときは、一致の判定（%）を少し下げるか、お手本を撮り直してください\n"
                        + "・画像の手順があるシナリオでは、実行中はマーカーを隠します（照合の邪魔をしないため）\n\n"
                        + "使えない場面\n"
                        + "・一部のアプリやゲームはユーザー補助によるタップを受け付けません\n"
                        + "・使う前に、そのアプリやサイトの利用規約を確認してください"),
                Ui.mw(this, 6));
        root.addView(Ui.cardView(this, c5), Ui.mw(this, 10));
    }

    @Override
    protected void onResume() {
        super.onResume();
        s1.setText(Build.VERSION.SDK_INT >= 33 ? "1. 制限付き設定を許可" : "1. 制限付き設定（この機種では不要）");
        boolean on = TapService.isOn() || a11yEnabled();
        s2.setText((on ? "✅ " : "2. ") + "ユーザー補助で「タップ助」をオン");
        if (on) s1.setText("✅ " + s1.getText().toString().replaceFirst("^1\\. ", ""));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        s3.setText((bat ? "✅ " : "3. ") + "電池の最適化を外す（推奨）");
    }

    private boolean a11yEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(this, TapService.class).flattenToString();
        String shortName = new ComponentName(this, TapService.class).flattenToShortString();
        return s.contains(me) || s.contains(shortName);
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void openA11y() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "開けませんでした", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
