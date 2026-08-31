# Android 抖音下载器

首版面向一加 13T / ColorOS 15，自用侧载，完全在手机本地运行。最低 Android 10，
APK 只包含 `arm64-v8a`。

## 安装

将 release APK 复制到手机后点击安装。如果 ColorOS 阻止安装，请在系统提示中仅对
当前文件管理器允许“安装未知应用”。覆盖安装后，历史、设置和 WebView 登录态会
保留。

首次使用：

1. 打开应用，允许通知，以便后台下载显示进度。
2. 粘贴抖音分享文本并点击“解析作品”。
3. 应用会短暂打开内置 WebView 建立解析环境；遇到验证或登录提示时在页面完成。
4. 选择清晰度和保存模式后开始下载。

也可以在抖音的分享面板中选择“抖音下载器”。分享进入后应用会自动开始解析。

默认输出目录：

```text
内部存储/Download/DouyinDownloader/
```

设置中可以通过系统目录选择器改用其他目录。诊断日志默认位于应用私有目录，只有
点击“导出 ZIP”后才会写入公共下载目录。

## 本地构建

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

本项目所在的 ARM64 Termux 环境需要 ARM64 原生 AAPT2。当前构建配置指向：

```text
/data/data/com.termux/files/usr/bin/aapt2
```

在普通 x86-64 Linux/Android Studio 中构建时，应删除 `gradle.properties` 中的
`android.aapt2FromMavenOverride`，使用 Android Gradle Plugin 自动下载的 AAPT2。

## 已知边界

- 抖音没有公开稳定的下载 API，Cookie、签名或字段变化后可能需要更新 APK。
- 首次解析或出现 401/403 时，需要在设置中打开“登录或刷新抖音环境”。
- H.265 是最高档时，旧播放器可能不兼容，可手动选择相同分辨率的 H.264。
- 首版只支持抖音，不包含小红书和服务器兜底。
- APK 中的媒体处理使用 Android MediaExtractor/MediaMuxer，只做编码轨复制，不转码。

