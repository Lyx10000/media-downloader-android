# MultiPlatformDownloader 架构整理与命名迁移设计

## 背景

项目已经从抖音下载器扩展为支持抖音、小红书、知乎、X、Instagram 和哔哩哔哩的聚合下载器。当前 Android 代码仍集中在单一
`com.local.douyindownloader` 包中，Gradle 项目名、主题名和默认下载目录也保留了早期抖音专用命名。随着平台和作者批量下载功能增加，主页、任务页、作者页及主 ViewModel 文件持续膨胀，平台差异也逐渐进入公共流程。

本次工作在不改变用户可见功能、任务数据和覆盖升级能力的前提下，完成通用命名迁移与单模块内的架构整理。

## 目标

- 将源码中的产品概念统一为 `MultiPlatformDownloader`。
- 保持应用显示名“聚合下载器”。
- 按应用入口、核心能力、业务功能和平台适配整理包与目录。
- 缩小主 ViewModel 和大型 Compose 文件的职责范围。
- 收拢平台差异，减少公共流程中的平台枚举判断。
- 保证现有 APK 可以覆盖升级，历史任务、设置、登录状态和下载文件继续可用。
- 保持单个 Gradle 应用模块，不在本次引入多模块构建复杂度。

## 非目标

- 不改变解析接口、媒体清晰度选择、下载策略或并发策略。
- 不增加或删除支持平台。
- 不修改数据库业务结构，除非包名迁移需要稳定类名或 Room 配置调整。
- 不更换签名、GitHub 仓库或用户可见应用名。
- 不在本次重写所有 UI 或改变页面布局。

## 方案选择

采用“单模块内分层整理”的兼容迁移方案。与立即拆分多个 Gradle 模块相比，该方案可以建立清晰边界，同时避免大量公开 API、依赖图和构建配置调整。与仅改名相比，它能够实际降低大型文件和中心类的维护压力。

目标包结构：

```text
com.local.multiplatformdownloader
├── app
│   ├── di
│   ├── navigation
│   └── theme
├── core
│   ├── database
│   ├── download
│   ├── logging
│   ├── model
│   ├── network
│   └── storage
├── feature
│   ├── creator
│   ├── document
│   ├── home
│   ├── preview
│   ├── settings
│   ├── tasks
│   └── zhihuarchive
└── platform
    ├── common
    ├── bilibili
    ├── douyin
    ├── instagram
    ├── x
    ├── xiaohongshu
    └── zhihu
```

物理文件位置与 Kotlin 包声明保持一致。暂时允许少量跨功能共享类型保留在 `core.model` 或 `platform.common`，不通过循环依赖维持旧结构。

## 命名迁移

以下标识改为通用名称：

- Gradle 根项目名：`MultiPlatformDownloader`
- Android namespace：`com.local.multiplatformdownloader`
- Kotlin 主源码和测试包：`com.local.multiplatformdownloader...`
- 应用主题：`Theme.MultiPlatformDownloader`
- 通用类、资源和文档中的旧产品命名
- 新默认下载根目录：`Download/MultiPlatformDownloader`

应用显示名继续使用“聚合下载器”。GitHub 仓库 `media-downloader-android` 已经是通用命名，本次不改远端仓库。

## 覆盖升级兼容

`applicationId` 必须继续使用 `com.local.douyindownloader`。Android 以应用 ID 和签名识别已安装应用；修改应用 ID 会生成另一款应用，导致现有用户无法覆盖升级，应用私有目录中的 Room 数据库、DataStore 设置和 WebView Cookie 也不会自动继承。

Gradle 配置中的旧应用 ID 旁必须保留醒目注释，说明它仅是发布兼容标识，不代表当前源码命名。该值不应在普通重命名中修改。

数据库文件名、DataStore 文件名、WorkManager 唯一任务名等持久化键如果包含旧命名，也必须保留或提供显式迁移。所有此类值集中记录在 `LegacyCompatibility`，并注释删除后会造成的用户影响。

## 下载目录兼容

新任务默认保存到 `Download/MultiPlatformDownloader`。现有任务仍可能位于 `Download/DouyinDownloader`，因此存储层需要同时识别新旧根目录：

1. 新任务只写入新目录。
2. 读取、预览、分享、删除、重新下载和打开文件夹时，优先使用任务已记录的位置。
3. 对旧版未记录完整根路径的任务，先检查新目录，再检查旧目录。
4. 不自动批量移动或删除旧文件，避免长时间 I/O、空间不足及用户自行管理文件导致的数据风险。
5. 设置页和提示文字展示新目录；只有兼容错误信息需要提及旧目录。

旧路径常量放入 `LegacyCompatibility` 并说明其用途，不散落在业务代码中。

## 职责整理

### 应用层

`app` 负责 Application、Activity、导航、主题和 Hilt 模块。它可以组装功能，但不实现平台解析和文件下载规则。

### 核心层

`core` 提供平台无关能力：任务模型、Room、HTTP 边界、后台下载、存储、日志和更新。核心层不得依赖具体 Compose 页面。

### 功能层

`feature` 按用户流程组织 UI、ViewModel 和协调器。主页解析、任务管理、作者库、预览、文档阅读、设置和知乎问题归档分别维护自己的状态。

主 ViewModel 逐步拆分：

- 首页输入、解析和登录环境状态归 `HomeViewModel`。
- 普通任务列表、选择、删除和重新下载归 `TasksViewModel`。
- 预览播放状态由现有预览协调器和独立 ViewModel 管理。
- 更新状态归设置功能。
- 已有作者库和知乎归档 ViewModel 保持独立。

在拆分期间允许应用级导航持有少量共享事件，但不再增加新的业务职责到旧 `MainViewModel`。

大型 Compose 文件按页面容器、卡片组件、对话框和纯展示组件拆分。拆分不改变视觉表现，组件参数优先传不可变 UI 状态和事件回调。

### 平台层

每个平台目录包含媒体解析、作者来源、请求参数和平台特有规范化逻辑。`platform.common` 定义统一契约，例如：

- 作品链接识别与解析
- 作者标识解析和公开作品分页
- 登录需求与凭据策略
- 请求头、Referer 和媒体 URL 规则
- 平台能力声明

公共 ViewModel 和下载器通过能力与接口工作。确实无法统一的平台行为保留在平台实现中，不继续向中心类增加平台判断。

## 数据流

```text
Compose 页面
  → Feature ViewModel
  → 用例/协调器
  → 平台契约或核心 Repository
  → HTTP / Room / WorkManager / 存储
  → Flow 状态回传 UI
```

平台解析结果继续归一化为现有通用模型，下载执行器不直接理解网页结构。作者批量流程继续持久化准备状态，应用重启后能够恢复。

## 错误和状态

本次不批量修改数据库状态值，以避免迁移风险，但新增代码使用类型安全的状态映射，不在 UI 和协调器中散写字符串。现有错误码保持兼容，平台特有错误由平台层映射为公共错误类别和用户提示。

取消异常继续向上抛出，不转换为普通失败。文件删除、重新下载事务和 WorkManager 调度语义保持不变。

## 实施顺序

1. 建立兼容常量和命名边界，修改项目名、namespace、主题与源码根包。
2. 整理 `app`、`core`、`feature`、`platform` 包结构并修复依赖方向。
3. 拆分平台聚合文件和大型 UI 文件。
4. 拆分主 ViewModel 的职责，保持现有对外页面行为。
5. 切换新下载目录并加入旧目录读取兼容。
6. 更新测试、README、开发文档和 R8 配置。

每一步独立提交并保持可编译，方便定位回归。现有工作区中与本次无关的删除和未跟踪文件不得纳入提交。

## 验证

- 执行全部 JVM 单元测试。
- 执行 Room 数据库迁移仪器测试可编译检查；有设备时再运行仪器测试。
- 构建不启用 R8 的 debug APK，并使用正式证书签名用于覆盖测试。
- 核对 APK 的 application ID、版本号和签名证书与 1.7.0 一致。
- 对新旧默认目录分别验证任务读取、预览、分享、删除、重新下载和打开文件夹路径选择。
- 搜索残余 `DouyinDownloader`，只允许出现在 `applicationId`、旧目录兼容常量、历史设计文档和必要迁移测试中。

## 成功标准

- 用户可从已发布 1.7.0 覆盖安装测试 APK，现有任务和登录状态保留。
- 新代码的根包、项目名和架构命名全部使用 `MultiPlatformDownloader`。
- 新下载写入新目录，旧任务文件仍可完整操作。
- 六个平台现有解析、单条下载、作者任务、预览和重新下载行为不变。
- 中心类职责减少，新增平台不再要求在多个公共类中散布条件分支。
