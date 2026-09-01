# Android 全面现代化重构设计

日期：2026-09-01
状态：已实施（2026-09-01）

## 背景

当前 Android 应用功能完整，并已覆盖原图、视频档位、音频、无损合成、多文件分享、
任务恢复与安全删除。但工程结构仍以单个大型 Activity、单个大型 ViewModel、原生
SQLiteOpenHelper、SharedPreferences 和定时数据库轮询为中心。继续增加抖音兼容逻辑或
任务功能会提高回归风险。

本次重构只改变 Android/Kotlin 内部实现，不改变 Python 解析算法及其 JSON 契约。
覆盖安装后，旧任务、旧设置、已下载文件和原有操作流程必须继续可用。

## 目标

- 使用 Room 管理任务数据库，并无损接管现有 `downloads.db`。
- 使用 Kotlin Flow 和 StateFlow 建立响应式数据流，取消每秒数据库轮询。
- 使用 Hilt 统一构造数据库、仓库、协调器、解析器、日志和 Worker 依赖。
- 使用 Preferences DataStore 替代 SharedPreferences，并自动迁移旧设置。
- 将持久化字符串状态映射为类型安全的 Kotlin 模型。
- 拆分 UI、状态管理、任务编排和下载执行职责。
- 保持现有产品行为、存储安全边界和 Python 解析契约不变。
- 增加数据库迁移、数据映射、响应式状态和关键任务流程测试。

## 非目标

- 不改变抖音接口调用、签名、Cookie 或质量档位算法。
- 不重新设计视觉风格、导航结构、文案或用户操作流程。
- 不改变下载目录、任务目录、输出文件名或 MIME 类型规则。
- 不改变四种视频模式、无损封装方式或“合成后保留原轨道”的约定。
- 不删除旧任务，不清理现有公共文件，不改用远程服务器。
- 不在本次重构中支持新的内容平台。

## 分层架构

应用按表现层、领域协调层、数据层和基础设施层划分。避免为每个简单函数创建独立
用例类；只有跨越多个依赖、包含状态转换的流程使用协调器。

### 表现层

`MainActivity` 只负责 Activity 生命周期、分享 Intent、系统权限及目录选择结果。
Compose 内容拆分为：

- `DouyinDownloaderApp`：应用级导航和 Snackbar。
- `HomeScreen`：输入、解析环境和解析结果。
- `TasksScreen`：任务列表、文件状态、分享、恢复和删除入口。
- `SettingsScreen`：默认模式、编码偏好、目录和解析环境。
- `DiagnosticsScreen`：日志查看、导出与清理。
- 独立组件：质量选择、模式选择、任务卡、分享选择、恢复和删除对话框。

ViewModel 暴露不可变 `StateFlow` UI 状态和明确事件方法。Composable 不直接读取数据库、
DocumentFile、SharedPreferences 或 WorkManager，也不在组合期间执行磁盘检查。

### 领域协调层

- `ParseCoordinator`：启动解析、处理 Cookie、选择默认档位并记录诊断事件。
- `TaskRedownloadCoordinator`：重新解析、匹配档位、清理旧输出并重新入队。
- `TaskDeletionCoordinator`：停止工作、删除登记输出、删除空目录并更新任务记录。
- `TaskFileStateRefresher`：受控扫描任务输出并持久化文件状态变化。
- `DownloadExecutor`：执行图片、视频、音频、拆轨、合成和公共存储发布。
- `DownloadScheduler`：封装 WorkManager 唯一任务名、标签、入队、替换和取消规则。

协调器通过接口依赖仓库和基础设施。现有状态转换及错误文案保持不变。

### 数据层

`DownloadTaskRepository` 是 ViewModel、Worker 和协调器访问任务数据的统一接口，提供：

- `observeTasks(): Flow<List<TaskRecord>>`
- 按 ID 读取任务及 TaskSpec
- 创建、更新、完成和删除任务
- 替换解析结果与输出文件
- 更新执行状态和文件状态

Room DAO 使用挂起函数执行单条读写，并用 Flow 观察任务列表。数据库操作不再由界面
线程直接执行。JSON 格式的 `spec` 和 `outputs` 暂时保留，以保证迁移可靠且不改变
Python 结果持久化格式；Room Entity 与领域模型之间由 Mapper 隔离。

### 基础设施层

ParserGateway、DiagnosticLogger、StorageInspector、PublicStorage、MediaTrackProcessor 和
WorkManager 继续承担现有平台职责，由 Hilt 提供单例或应用作用域实例。

`DownloadWorker` 使用 Hilt Worker 注入，只负责后台生命周期、前台通知、取消传播和
结果映射；媒体下载及处理委托给 `DownloadExecutor`。WorkManager 的唯一工作名称
`douyin-<taskId>`、任务标签和输入键 `task_id` 保持不变，因此覆盖安装不会破坏已排队工作。

## 类型安全与兼容转换

以下模型改为枚举或密封类型：

- `TaskStatus`
- `FileState`
- `StorageMode`
- `DownloadMode`
- `MediaKind`

每个类型具有稳定的 `wireValue`，继续写入当前字符串，例如 `QUEUED`、`AVAILABLE`、
`SAF`、`merge_keep` 和 `image`。读取旧记录时使用宽容转换：已知值转换为对应类型；未知
执行状态保留原始值并按失败/不可操作状态展示，未知存储和文件状态采用安全回退，绝不
因无法识别字符串而崩溃或删除文件。

Python `ParseResult` JSON 字段名称和含义保持不变。TaskSpec 序列化需要在 `rawJson`
异常时返回可诊断错误，不能使整个任务列表无法加载。

## Room 数据库迁移

Room 数据库继续使用文件名 `downloads.db`，新版本为 3。

- 新安装直接创建版本 3 的 `tasks` 表。
- `1 -> 2` 增加 `file_status TEXT NOT NULL DEFAULT 'UNKNOWN'`，与当前迁移一致。
- `2 -> 3` 重建结构等价的任务表，为旧主键补上 Room 要求的显式 `NOT NULL`，原样复制
  全部业务列，然后由 Room 校验 schema 并建立自身 identity。
- 不允许 destructive migration。
- 表名、主键、所有现有列、默认值和排序语义保持不变。

迁移测试分别创建版本 1 和版本 2 数据库，插入包含旧 JSON 输出格式的任务，升级后验证
任务数量、TaskSpec、输出 URI、状态、进度和文件状态。正式构建导出 Room schema，后续
数据库变更必须提交 schema 与显式 Migration。

## 设置迁移

Preferences DataStore 接管以下键：

- `default_mode`
- `prefer_h264`
- `custom_tree_uri`

首次打开 DataStore 时通过 SharedPreferencesMigration 从现有 `settings` 文件迁移。
设置仓库暴露 Flow；ViewModel 不直接持有 SharedPreferences。目录 URI 的持久权限仍由
Activity/ContentResolver 管理，迁移只复制字符串值，不重新申请或扩大权限。

## 响应式任务与文件状态

Room Flow 在 Worker 或协调器写入状态后立即更新任务页，因此删除每秒一次的全表查询。
外部文件删除无法由数据库感知，保留受控扫描：

- 应用回到前台时扫描。
- 进入任务页时扫描。
- 下载、分享、删除和重新下载完成后扫描相关任务。
- 任务页持续可见且存在已完成输出时，最多每 15 秒扫描一次。

任务列表为空时不启动文件扫描，也不显示加载进度圈或“去下载”按钮，只显示现有的
“还没有下载任务”。离开任务页后停止周期扫描。

## 错误与并发处理

- 所有数据库、SAF、MediaStore 和文件系统操作在 IO 调度器执行。
- 删除状态继续阻止 Worker、重试和普通状态更新覆盖删除流程。
- Worker 取消使用协程取消传播；删除协调器等待对应 WorkManager 进入终止状态后再删文件。
- Room 写操作需要原子性的步骤使用事务。
- 文件删除只针对数据库登记输出，目录仅在确认空时删除，绝不递归删除未知公共文件。
- JSON 或旧状态转换失败写入脱敏诊断日志，并将单个任务标记为可诊断错误，不拖垮列表。

## Hilt 依赖图

`DownloaderApplication` 使用 Hilt，并配置 Hilt WorkManager Factory。主要作用域：

- Singleton：Room Database、DAO、Repository、DataStore、设置仓库、ParserGateway、
  DiagnosticLogger、StorageInspector、DownloadScheduler。
- 按调用创建：协调器中的轻量流程对象，或以 Singleton 注入无状态协调器。
- Worker：通过 Assisted Injection 获取 Context/WorkerParameters，并注入 Repository、
  DownloadExecutor 和 DiagnosticLogger。

测试通过接口替换仓库、调度器和存储检查器，不依赖真实抖音网络或公共存储。

## 测试策略

### JVM 单元测试

- 所有持久化字符串与类型模型的双向转换和未知值回退。
- TaskSpec、旧输出 JSON 和 ParseResult 的兼容读取。
- ViewModel StateFlow 的解析、空任务、消息消费和设置状态。
- 重新下载的精确档位、接近档位、最高档位和解析失败流程。
- 删除与文件状态协调器的状态转换。

### Android 仪器测试

- Room `1 -> 3` 和 `2 -> 3` 迁移且旧任务不丢失。
- DAO Flow 在插入、进度更新、完成和删除后发出正确列表。
- SharedPreferences 到 DataStore 的三个设置迁移。
- Hilt Worker 创建及 WorkManager 入队兼容。
- 关键 Compose 状态：空任务无进度圈、缺失文件恢复、删除对话框和多选分享。

### 回归验证

- 运行全部 Python 测试，确认解析契约未变。
- 运行 Android JVM 测试、Lint、Debug 和 Release 构建。
- 有可用设备时覆盖安装旧版本数据库，再检查任务、设置、分享、重新下载和删除。
- Release APK 继续使用现有签名方式并复制到 `/mnt/Android/douyinDownload`。

## 分阶段实施与回退点

1. 增加类型模型和兼容 Mapper，测试通过后再替换调用点。
2. 引入 Room 和迁移测试，确认旧数据库可打开后移除 SQLiteOpenHelper。
3. 引入 DataStore 和设置迁移。
4. 引入 Hilt，统一应用、ViewModel、Worker 和协调器依赖。
5. 抽取 Repository、Scheduler、DownloadExecutor 和领域协调器。
6. 将 ViewModel 改为 StateFlow，并切换任务列表到 Room Flow。
7. 拆分 Compose 页面和组件，保持界面行为及文案。
8. 完成全量自动测试、静态检查、构建和设备兼容验证。

每阶段保持可编译并单独提交。若迁移或实机覆盖安装失败，在该阶段修正，不带着已知数据
兼容问题进入下一阶段。

## 完成标准

- 覆盖安装后，旧任务、旧输出、旧设置和已下载文件不丢失。
- Python 解析返回契约、下载质量和四种保存模式没有变化。
- `MainActivity`、ViewModel、Worker、数据访问和任务协调职责分离。
- UI 不直接执行磁盘或数据库访问。
- 任务状态由 Flow 驱动，不再每秒轮询数据库。
- 空任务页只显示静态空状态。
- Room 与 DataStore 迁移测试通过，且没有 destructive migration。
- 原有 Android/Python 测试、Lint、Debug/Release 构建全部通过。
- 新 APK 可覆盖安装并输出到指定目录。
