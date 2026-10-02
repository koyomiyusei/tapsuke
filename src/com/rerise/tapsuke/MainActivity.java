package com.rerise.tapsuke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 12;

    private LinearLayout root, list;
    private TextView state;
    private Button stateBtn, updateBtn;
    private String exportId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p + Ui.dp(this, 8), p, p);
        sv.addView(root);
        setContentView(sv);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
            return ins;
        });

        LinearLayout head = Ui.hbox(this);
        TextView title = Ui.text(this, "タップ助", 24, true);
        head.addView(title, Ui.weight(1));
        TextView ver = Ui.subText(this, "v" + Updater.currentName(this));
        head.addView(ver);
        root.addView(head);

        // 状態
        LinearLayout st = Ui.vbox(this);
        state = Ui.text(this, "", 15, false);
        st.addView(state);
        stateBtn = Ui.primary(this, "設定ガイドを開く", v -> startActivity(new Intent(this, GuideActivity.class)));
        st.addView(stateBtn, Ui.mw(this, 10));
        root.addView(Ui.cardView(this, st), Ui.mw(this, 12));

        // シナリオ操作
        LinearLayout row = Ui.hbox(this);
        row.addView(Ui.primary(this, "＋ 新しいシナリオ", v -> newScenario()), Ui.weight(1));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        row.addView(Ui.button(this, "読み込み", v -> importFile()), Ui.weight(1));
        root.addView(row, Ui.mw(this, 16));

        TextView h = Ui.text(this, "シナリオ", 17, true);
        root.addView(h, Ui.mw(this, 18));
        list = Ui.vbox(this);
        root.addView(list, Ui.mw(this, 6));

        // 下部
        updateBtn = Ui.button(this, "更新確認", v -> Updater.updateNow(this));
        root.addView(updateBtn, Ui.mw(this, 24));
        root.addView(Ui.button(this, "使い方・設定", v -> startActivity(new Intent(this, GuideActivity.class))), Ui.mw(this, 4));
        root.addView(Ui.button(this, "実行ログ（周回・停止の記録）", v -> showRunLog()), Ui.mw(this, 4));
        Button err = Ui.button(this, "最後のエラーを見る", v -> showError());
        root.addView(err, Ui.mw(this, 4));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (App.prefs(this).getBoolean("auto_update", true)) Updater.autoCheck(this, this::markUpdate);
    }

    private void markUpdate() {
        if (Updater.available != null) {
            updateBtn.setText("⬇ v" + Updater.available + " に更新");
            updateBtn.setTextColor(0xFFFFFFFF);
            updateBtn.setBackground(Ui.round(0xFFF2994A, Ui.dp(this, 10)));
        }
    }

    private void refresh() {
        boolean on = TapService.isOn();
        if (on) {
            state.setText("✅ 準備OK　パネルを出して使えます\n音量の下キーで実行を止められます");
            stateBtn.setVisibility(View.GONE);
        } else {
            state.setText("⚠ ユーザー補助がオフです。最初に設定が必要です（1回だけ）");
            stateBtn.setVisibility(View.VISIBLE);
        }

        list.removeAllViews();
        List<Scenario> all = Scenario.all(this);
        if (all.isEmpty()) {
            list.addView(Ui.subText(this, "まだありません。「＋ 新しいシナリオ」から作ります。"));
        }
        String cur = TapService.isOn() && TapService.get().panelShown() ? TapService.get().currentId() : null;
        for (Scenario s : all) list.addView(scenarioCard(s, s.id.equals(cur)), Ui.mw(this, 8));
    }

    private View scenarioCard(final Scenario s, boolean open) {
        LinearLayout box = Ui.vbox(this);
        TextView name = Ui.text(this, (open ? "▶ " : "") + s.name, 16, true);
        box.addView(Ui.subText(this, s.mode == Scenario.MODE_RULES ? "画面を見て動く型" : "決まった順番の型"));
        box.addView(name, 0);
        box.addView(Ui.subText(this, s.summary()));
        LinearLayout row = Ui.hbox(this);
        Button use = Ui.primary(this, open ? "パネル表示中" : "パネルで使う", v -> openPanel(s.id));
        row.addView(use, Ui.weight(1.4f));
        row.addView(Ui.button(this, "設定", v -> startActivity(new Intent(this,
                s.mode == Scenario.MODE_RULES ? RulesActivity.class : EditActivity.class).putExtra("id", s.id))), Ui.weight(1));
        row.addView(Ui.button(this, "…", v -> menu(s)), Ui.weight(0.6f));
        box.addView(row, Ui.mw(this, 8));
        return Ui.cardView(this, box);
    }

    private void newScenario() {
        String[] items = {
                "画面を見て動く（おすすめ）\n　「この画面が見えたら、これをする」を登録する。テスト・周回向き",
                "決まった順番で動く\n　タップやスワイプを上から順に行う。単純なくり返し向き"};
        new AlertDialog.Builder(this).setTitle("どちらの型で作りますか？")
                .setItems(items, (d, w) -> createScenario(w == 0 ? Scenario.MODE_RULES : Scenario.MODE_SEQ))
                .show();
    }

    private void createScenario(int mode) {
        Scenario s = new Scenario();
        s.mode = mode;
        s.name = "シナリオ" + (Scenario.all(this).size() + 1);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        s.screenW = dm.widthPixels;
        s.screenH = dm.heightPixels;
        s.save(this);
        openPanel(s.id);
    }

    private void openPanel(String id) {
        if (!TapService.isOn()) {
            App.prefs(this).edit().putString("current", id).putBoolean("panel_on_connect", true).apply();
            Toast.makeText(this, "先にユーザー補助をオンにしてください", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, GuideActivity.class));
            return;
        }
        TapService.get().showPanel(id);
        Toast.makeText(this, "パネルを出しました。使いたいアプリを開いてください", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void menu(final Scenario s) {
        String[] items = {"名前を変える", "複製", "書き出し（ファイル・画像も含む）", "削除"};
        new AlertDialog.Builder(this).setTitle(s.name).setItems(items, (d, w) -> {
            if (w == 0) rename(s);
            else if (w == 1) { s.copyAsNew().save(this); refresh(); }
            else if (w == 2) exportFile(s);
            else if (w == 3) confirmDelete(s);
        }).show();
    }

    private void rename(final Scenario s) {
        final EditText e = new EditText(this);
        e.setText(s.name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("名前").setView(e)
                .setNegativeButton("やめる", null)
                .setPositiveButton("OK", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) {
                        s.name = n;
                        s.save(this);
                        if (TapService.isOn()) TapService.get().reload(s.id);
                        refresh();
                    }
                }).show();
    }

    private void confirmDelete(final Scenario s) {
        new AlertDialog.Builder(this).setTitle("削除しますか？").setMessage(s.name)
                .setNegativeButton("やめる", null)
                .setPositiveButton("削除", (d, w) -> {
                    if (TapService.isOn()) TapService.get().forget(s.id);
                    Scenario.delete(this, s.id);
                    Templates.cleanup(this);
                    refresh();
                }).show();
    }

    // ---------------- 書き出し・読み込み ----------------

    private void exportFile(Scenario s) {
        exportId = s.id;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "タップ助_" + s.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void importFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT) {
                Scenario s = Scenario.load(this, exportId);
                if (s == null) return;
                OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                JSONObject o = s.toJson();
                o.put("templates", Templates.export(this, s));
                out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
                out.close();
                Toast.makeText(this, "書き出しました", Toast.LENGTH_SHORT).show();
            } else if (req == REQ_IMPORT) {
                String txt = Scenario.readAll(getContentResolver().openInputStream(uri));
                JSONObject o = new JSONObject(txt);
                if (!o.has("steps")) throw new Exception("タップ助のシナリオではありません");
                Templates.importAll(this, o.optJSONObject("templates"));
                Scenario s = Scenario.fromJson(o);
                if (Scenario.exists(this, s.id)) {
                    s.id = UUID.randomUUID().toString().substring(0, 8);
                    s.name = s.name + "（読込）";
                }
                s.save(this);
                refresh();
                Toast.makeText(this, "読み込みました：" + s.name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            App.log(this, e);
            new AlertDialog.Builder(this).setTitle("できませんでした")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    private void showRunLog() {
        String e = App.prefs(this).getString("run_log", "");
        TextView t = Ui.text(this, e.isEmpty() ? "まだ記録はありません" : e, 12, false);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("実行ログ").setView(sv)
                .setNegativeButton("消す", (d, w) -> App.prefs(this).edit().remove("run_log").apply())
                .setPositiveButton("閉じる", null).show();
    }

    private void showError() {
        String e = App.prefs(this).getString("last_error", "エラーの記録はありません");
        TextView t = Ui.text(this, e, 11, false);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("最後のエラー").setView(sv)
                .setNegativeButton("消す", (d, w) -> App.prefs(this).edit().remove("last_error").apply())
                .setPositiveButton("閉じる", null).show();
    }
}
