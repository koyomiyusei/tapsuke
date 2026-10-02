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
        c5.addView(Ui.text(this, "使い方（画面を見て動く型）", 16, true));
        c5.addView(Ui.subText(this,
                "考え方：「この画面が見えたら、これをする」を場面として登録します。順番は決めなくて大丈夫です。\n\n"
                        + "1. 対象のアプリを開き、パネルの「＋場面」を押す\n"
                        + "2. 目印になる部分を指で囲んで「これで決定」（画面全体でもOK）\n"
                        + "3. その画面が見えたら何をするか選ぶ\n"
                        + "　・その画像の中をタップ（押す位置は毎回少し変わります）\n"
                        + "　・決めた範囲の中をタップ／スワイプ（続けて範囲を囲みます）\n"
                        + "　・何もしない／実行を止める（エラー画面など）\n"
                        + "4. どの場面でもないとき（クエスト中など）の操作は「一覧」の一番下から決める\n"
                        + "5. ▶ で開始。■ か音量の下キーで停止\n\n"
                        + "パネルのボタン\n"
                        + "≡　ドラッグで移動／タップで折りたたみ\n"
                        + "＋場面　場面を追加\n"
                        + "確認　いまの画面がどの場面に何%で当てはまるかを表示\n"
                        + "一覧　場面の並び替え・撮り直し・削除、くわしい設定\n"
                        + "範囲　操作する範囲の枠を表示／非表示\n\n"
                        + "コツ\n"
                        + "・目印は、その画面にしか出ない部分（ボタンや見出し）を囲むと確実です\n"
                        + "・毎回少し変わる画面は、変わらない部分だけを囲むか、判定を「ゆるめ」にします\n"
                        + "・2つの場面に当てはまるときは、一覧で上にある方が優先されます\n"
                        + "・パネルは目印や操作の範囲に重ならない場所へ動かしてください\n"
                        + "・画面の確認は1秒に約3回です。一瞬だけ出る表示は拾えないことがあります\n\n"
                        + "決まった順番の型\n"
                        + "＋ タップ／⇅ スワイプ／◫ 画像の手順／● 記録／◉ マーカー／✎ 編集\n\n"
                        + "使えない場面\n"
                        + "・一部のアプリはユーザー補助によるタップを受け付けません\n"
                        + "・画面の撮影を禁止している画面は、画像を見つけられません"),
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
