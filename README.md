# 肥鱼伴侣 Android 版（FeiYuAndroid）

独立仓库，不依赖苹果版。Live2D 舞台和大肥鱼模型已放在 `app/src/main/assets/`（`AvatarStage/`、`DeepSeekChan/`）。

## 构建（GitHub Actions，仓库需设为私有）
```
gh workflow run build-apk.yml
gh run watch
gh run download --name FeiYuCompanion-APK
```
产物 `FeiYuCompanion-Android.apk`，传到手机安装（需允许“安装未知来源应用”）。

第一次构建会生成签名证书 `keystore/feiyu-release.jks` 并自动提交回仓库。以后都用同一个证书，新版本可以直接覆盖安装，聊天记录不丢。
如果自动提交失败，从 `FeiYu-Android-Keystore` 产物下载后手动提交到 `keystore/feiyu-release.jks`。

## 本地构建
用 Android Studio 打开本目录即可。

## 技术
Kotlin + Jetpack Compose，minSdk 26（Android 8.0），targetSdk 34。Live2D 用 WebView + WebViewAssetLoader；
`assets/AvatarStage/bridge.js` 把页面里的 `window.webkit.messageHandlers.stage` 映射到 Android 的 `AndroidStage` 接口。
苹果版更新 Live2D 舞台（stage.js / director.js）后，需要把 `AvatarStage/` 同步复制过来。
