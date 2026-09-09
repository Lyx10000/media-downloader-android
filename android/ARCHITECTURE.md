# Android 架构

本项目保持单一 `app` Gradle 模块，在源码内部按职责分层。这样既保留较快的本地构建速度，也让平台适配、下载执行和界面开发拥有清晰边界。

## 依赖方向

```text
Compose 页面
    ↓
ViewModel / 页面状态
    ↓
协调器与执行器
    ↓
Repository / 平台解析契约
    ↓
Room、DataStore、WorkManager、网络与 Android 存储
```

- `app`：应用入口、导航、依赖装配。
- `core`：数据库、设置、网络、存储、下载基础设施和共享模型。
- `feature`：首页、任务、作者库、下载、预览、文档阅读等用户功能。
- `platform`：各平台链接识别、请求策略、响应解析和标准化。

`core` 不依赖具体页面或平台实现。平台实现输出统一的 `ParseResult`，下载层不读取平台原始 JSON，Compose 页面不直接操作 DAO、HTTP 或 WorkManager。

## 关键流程

### 单作品解析

`HomeScreen` 将用户事件交给 `MainViewModel`。解析会话状态由 `ParseSessionController` 管理，平台选择由 `KotlinParserRouter` 完成，具体解析器位于 `platform/<name>`。解析成功后，界面只消费标准化媒体、质量档位和作者信息。

### 下载执行

`DownloadExecutor` 只负责取得任务许可、处理 B站延迟解析并按媒体类型路由：

- `AttachmentDownloadExecutor`：混合附件；
- `ImageDownloadExecutor`：图片、实况图和 BGM；
- `VideoDownloadExecutor`：视频轨、音频轨、合成和 MP4-only；
- `DocumentDownloadExecutor`：Markdown、文档媒体和评论；
- `MediaTransferClient`：CDN 选择、重试、分段下载、速度与进度；
- `OutputPublisher`：发布文件并登记任务输出。

下载任务的持久化状态以 Room 为准，后台执行以 WorkManager 为准，瞬时速度和并发状态由运行时控制器维护。

### 任务与本地内容

`TaskCommandCoordinator` 处理暂停、继续、取消和重试；`TaskInteractionCoordinator` 管理预览、全屏、分享、文件夹和文件操作状态；`TaskContentCoordinator` 负责 Markdown 与文档资源读取。`MainViewModel` 保留兼容门面，避免页面和导航一次性迁移造成回归。

### 平台登录

`PlatformCredentialCoordinator` 统一管理 Cookie 检测、B站在线校验、小红书页面快照校验和登录环境诊断事件。登录 WebView 的页面与脚本分别位于 `HomeWebEnvironment.kt` 和 `LoginWebScripts.kt`。

## 状态所有权

- Room：任务、输出、作者、作品、批次和知乎归档。
- DataStore：下载模式、编码偏好、保存位置、更新与风控设置。
- WorkManager：可恢复的后台工作生命周期。
- 运行时控制器：下载速度、并发、温度、播放器和当前解析会话。
- Compose：筛选、展开、对话框等短生命周期界面状态。

不要在 Compose 中复制一套永久任务状态，也不要把实时速度写入任务业务字段。

## 新增平台

1. 在 `core/model` 增加平台枚举及稳定展示信息。
2. 在 `platform/<name>` 实现 `PlatformParser`，输出统一 `ParseResult`。
3. 将实现注册到 `KotlinParserRouter`。
4. 如支持作者页，在作者数据源中实现资料和作品分页。
5. 增加解析器表征测试、失败映射测试和至少一个脱敏样本测试。

平台解析器不应直接写文件、创建任务或操作 Compose 状态。

## 兼容性约束

为确保正式版本可以覆盖升级，重构时不得随意修改：

- `applicationId` 与签名证书；
- Room 表结构和数据库版本；
- 任务、作者、作品及批次键；
- WorkManager 唯一任务名称与标签；
- 下载目录、文件名和已保存 URI。

需要改变这些内容时，应单独设计迁移方案并覆盖升级测试。
