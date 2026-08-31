# Android 抖音下载器设计

日期：2026-09-01  
状态：已批准，直接实施  
目标设备：一加 13T，ColorOS 15，arm64-v8a

## 目标

在现有命令行下载器基础上新增完全本地运行的 Android 应用。应用接收抖音分享或
用户粘贴的分享文本，取得抖音公开提供的最高质量视频或原图，支持选择清晰度以及
四种音视频保存模式。解析、下载、媒体处理和日志均在手机上完成，不依赖服务器。

首版只支持抖音。小红书、应用商店发布、远程代码更新和云同步不在首版范围内。
抖音接口变化后，通过使用同一签名密钥覆盖安装新版 APK 完成适配。

## 总体架构

Android 客户端使用 Kotlin 和 Jetpack Compose。系统 WebView 负责建立抖音浏览器
环境并保存登录 Cookie；Chaquopy 嵌入共享 Python 解析核心，复用现有 a_bogus
签名、详情接口、质量档位和原图选择逻辑。下载、通知、存储、历史和诊断由原生
Android 代码负责。

共享 Python 核心不得依赖 Selenium、Android UI 或 ffmpeg。桌面版由 Selenium
提供 Cookie，Android 版由 WebView CookieManager 提供 Cookie，两端调用同一解析
接口，避免维护两套抖音字段和签名规则。

Android 端组件：

- ShareReceiver：接收 ACTION_SEND 文本。
- ClipboardInput：在用户操作后读取或粘贴剪贴板。
- WebSessionManager：显示抖音 WebView，管理 Cookie 和刷新解析环境。
- PythonParserGateway：调用共享 Python 解析核心并返回统一模型。
- DownloadWorker：以前台任务执行下载并发布进度通知。
- MediaTrackProcessor：使用 MediaExtractor/MediaMuxer 无损合并和拆分编码轨。
- MediaStoreWriter：把最终文件写入公共下载目录或用户授权目录。
- DownloadHistory：保存任务、选择项和文件状态。
- DiagnosticLogger：写入结构化脱敏日志并导出诊断 ZIP。

## 用户流程

应用支持两个入口：

1. 用户在抖音中分享文本到本应用。
2. 用户打开应用后点击读取剪贴板，或手动粘贴分享文本。

两种入口都进入同一解析流程：识别作品 ID、读取 WebView Cookie、调用共享解析
核心、展示作品与全部质量档位、选择下载模式、创建后台任务、无损处理媒体并写入
公共目录。解析遇到 401、403 或 Cookie 问题时，引导用户打开“刷新解析环境”页；
普通网络错误按策略重试。

## 界面

首版包含五个页面：

- 首页：分享文本输入、用户触发的剪贴板读取、最近任务。
- 解析页：封面、作者、描述、资源类型、全部清晰度、编码和预计大小。
- 任务页：解析、下载、媒体处理和保存进度；支持取消、失败重试和打开文件。
- 历史页：任务选择、生成文件、保存位置、状态和对应日志。
- 设置与诊断页：默认质量、默认模式、编码偏好、目录、WebView 环境、版本和日志。

视频默认选择最高档。同分辨率下按帧率、码率和编码信息排序，并提示 H.265 的旧
播放器兼容性。图文默认选择接口公开的原图地址。

四种视频模式：

1. 原始或合成音视频文件、视频轨、音频轨全部保留。
2. 只保留视频轨和音频轨。
3. 只保留视频轨。
4. 只保留音频轨。

媒体处理只复制编码样本，不重新编码或压缩。媒体处理失败时保留已下载的原始文件。

## 存储

默认根目录为：

```text
内部存储/Download/DouyinDownloader/
```

每个任务使用时间戳目录，文件命名与桌面版一致。应用使用 MediaStore 写入公共下载
目录，不请求“访问所有文件”权限。设置中可以通过系统目录选择器改用用户授权的
目录，并持久保存授权。

下载临时文件位于应用缓存目录，成功或取消后清理。历史数据库和日常日志位于应用
私有目录。只有用户主动导出时，诊断 ZIP 才写入公共目录的 diagnostics 子目录。

## 诊断日志

每个任务生成 taskId，所有事件以结构化 JSONL 记录。阶段固定为：

```text
INPUT -> SHORT_LINK -> WEBVIEW -> COOKIE -> SIGNATURE -> DETAIL_API
-> PARSE -> QUALITY -> DOWNLOAD -> MEDIA_PROCESS -> MEDIASTORE -> COMPLETE
```

日志包含时间、应用版本、解析器版本、设备与 WebView 版本、作品 ID、阶段、耗时、
HTTP 状态码、重试次数、所选媒体元数据、CDN 主机、文件大小、结果和异常类型。

Cookie、密码、a_bogus、msToken 和 URL 查询参数必须脱敏。日志不自动上传。默认
保留最近 30 天或 20 MB，超过限制删除最旧文件。

诊断 ZIP 包含 manifest.json、events.jsonl、response-shape.json、media-probe.json
和脱敏异常信息。默认只保存响应结构；用户可在导出时选择附带脱敏响应样本。

## 错误处理

- 网络超时和 CDN 错误最多重试三次，并切换备用地址。
- 401、403 或 Cookie 失效不盲目重试，提示刷新 WebView 环境。
- 未识别字段结构标记 SCHEMA_CHANGED，并提示导出诊断包。
- 存储失败保留缓存文件，允许重新选择目录后再次保存。
- 进程被系统终止后，任务状态可恢复；已完成的文件不重复下载。
- CDN URL 可能过期，首版不提供长时间暂停，取消后重新解析和下载。

## 兼容性与测试

最低 Android 版本为 Android 10（API 29），目标设备为一加 13T / ColorOS 15，APK
只包含 arm64-v8a。

实施首先验证 WebView Cookie 能传入内嵌 Python，并用已知量子位作品取得六个质量
档位。随后完成：

- Python 核心的签名、字段、质量、原图和音频测试。
- Android 的链接、状态机、命名、脱敏和错误分类单元测试。
- 独立音视频合并、音视频合一拆轨及四种模式的媒体测试。
- 分享入口、WebView、后台任务、MediaStore 和覆盖安装的仪器测试。
- 普通视频、H.264/H.265、图文、BGM、备用 CDN、403、锁屏、进程恢复、空间不足
  和诊断导出的实机验收。

## 交付

构建 release APK，使用本地持久签名密钥签名，密钥和密码不提交 Git。最终 APK
复制到：

```text
/mnt/Android/douyinDownload/
```

交付同时包含安装说明、测试结果、解析器版本、变更说明、已知限制和 Git 提交记录。
