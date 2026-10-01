# タップ助（Android 汎用オートクリッカー）

アプリやWebサイトを問わず使える、個人用のオートクリッカー。
ユーザー補助（AccessibilityService）の `dispatchGesture` でタップ・スワイプを行う。

- 浮かぶ操作パネル（TYPE_ACCESSIBILITY_OVERLAY なので「他のアプリの上に表示」の許可は不要）
- タップ／長押し／スワイプ／待機、記録と再生、くり返し・自動停止、ランダム化
- シナリオは端末内に JSON で保存。書き出し・読み込み可
- Gradle・AndroidX・外部ライブラリ不使用。`build.sh` が aapt2 / javac / d8 / apksigner を直接呼ぶ
- push すると GitHub Actions がビルドし、`Tapsuke.apk` と `latest.json` をリポジトリに戻す
- アプリ内「更新確認」で `latest.json` を見て更新

署名鍵は Secret `RELEASE_KEYSTORE_B64` / `KS_PASS` から復元する（リポジトリには入れない）。
使う前に、対象のアプリやサイトの利用規約を確認すること。
