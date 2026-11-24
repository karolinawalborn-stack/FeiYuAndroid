# 肥鱼伴侣 Android 版（FeiYuAndroid）

独立仓库，不依赖苹果版。Live2D 舞台和大肥鱼模型在 `app/src/main/assets/`（`AvatarStage/`、`DeepSeekChan/`）。

## 构建（GitHub Actions，仓库需设为私有）
```
gh workflow run build-apk.yml
gh run watch
gh run download --name FeiYuCompanion-APK
```
产物 `FeiYuCompanion-Android.apk`。第一次构建会生成签名证书 `keystore/feiyu-release.jks` 并自动提交，之后都能覆盖安装。

## DSH 电脑桥接
电脑端用 iPhone 版附带的 `dsh-bridge`（同一个桥接服务，两边通用）。手机和电脑连同一 Wi-Fi，设置 → 连接 → 电脑桥接 · DSH。
App 只对局域网桥接用 http，其余服务都是 https（见 `res/xml/network_security_config.xml`）。

## 跨设备同步
和 iPhone 版同一种加密格式（PBKDF2-SHA256 21 万次 + AES-256-GCM，`companion-<设备>.pcsync`），可以互相同步。
同步位置：手机上的文件夹（由网盘 App / Syncthing 同步到云端）或 WebDAV（坚果云、Nextcloud、群晖）。

## 技术
Kotlin + Jetpack Compose，minSdk 26（Android 8.0），targetSdk 34。Live2D 用 WebView + WebViewAssetLoader；
导入的模型放在 `files/models/custom/`，通过 `/assets/Models/custom/` 提供给舞台。
苹果版更新 Live2D 舞台（stage.js / director.js）后，需要把 `AvatarStage/` 同步复制过来。
