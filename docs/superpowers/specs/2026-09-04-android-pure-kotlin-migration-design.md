# Android 纯 Kotlin 迁移与 APK 瘦身设计

日期：2026-09-04

## 背景

本项目的实际产品形态是直接安装在 Android 手机上的 APK。早期 Termux/Python 版本已经不再
是需要维护的桌面或命令行产品，但 Android 应用目前仍通过 Chaquopy 启动 Python 3.13，调用
`android_bridge.py`、`douyin_quality.py`、`xiaohongshu_parser.py` 和 `abogus.py` 完成媒体解析。

当前 arm64 Release APK 约 70 MB。包内约 20 MB 来自 Python、OpenSSL、标准库、requests、
gmssl 和 Chaquopy 桥接运行时；约 50 MB 来自没有经过 R8 裁剪的 DEX。Python 环境也使应用
启动和首次解析需要额外准备时间，并形成 Kotlin/Python 两套类型和错误处理边界。

本次工作把 Android 应用迁移成单一 Kotlin 技术栈，并在不改变功能、解析结果、存储语义和
用户操作方式的前提下压缩 APK。迁移完成后，仓库不再保留 Termux、桌面或 Python 运行方式。

## 硬性目标

- 删除 Chaquopy、Python 运行时、Python 第三方依赖、`python3/` 和根目录旧 `run` 脚本。
- 抖音与小红书解析全部由 Kotlin 在设备本地完成，不引入服务器。
- 最高画质、无水印图片候选、清晰度档位、独立音频轨道和备用 CDN 不得退化。
- Kotlin 解析结果与现有 Python 实现保持契约等价，包括候选顺序、档位排序和错误分类。
- 保持现有 Compose 界面、任务数据库、下载、断点状态、音视频合成、分享、预览和文件管理
  行为不变。
- 保持应用 ID、发布签名和已有数据库兼容，支持从当前版本覆盖安装。
- 对 Release 进行安全的代码与资源裁剪，以实测分析为依据继续瘦身。

解析结果等价是发布硬门槛。任何已覆盖样本出现资源候选减少、最高档位降低、图片水印优先级
退化、音频丢失或错误语义退化，都不得交付新 APK。

## 非目标

- 不重新设计界面、任务交互或下载模式。
- 不在本次迁移中新增知乎、X、Instagram 等平台。
- 不为了体积改写 Compose、Room、WorkManager、Media3 或现有存储体系。
- 不依赖远程解析服务、代理服务或云端签名服务。
- 不承诺每次随机生成的 `a_bogus` 字符串逐字节一致；要求相同输入能够通过真实接口并取得
  契约等价的详情和媒体资源。
- 不清理 Git 历史中的旧 Python 文件；文件从当前版本删除后仍可从历史提交恢复和审计。

## 方案选择

### 采用：开发期双实现对照，验证后切换并删除 Python

先在现有应用边界后增加 Kotlin 实现，同时保留 Python 实现作为开发期基准。固定输入和脱敏
响应样本同时送入两套实现，逐字段比较稳定结果。所有平台通过后将 `ParserGateway` 切换到
Kotlin，移除 Python 代码和运行时，再执行完整回归与 APK 分析。

该方案最终产物没有双运行时，但迁移期间能够准确定位差异，最符合“功能完全不变”的硬标准。

### 不采用：一次性逐行翻译后直接删除 Python

实现速度更快，但 URL 去重顺序、JavaScript 风格的签名字节运算、HTML 状态提取和异常映射中
的细小差异很难被发现。真实链接偶尔成功不能证明候选完整，风险不可接受。

### 不采用：重新设计解析算法

可以得到更自由的代码结构，但会同时改变语言和算法，无法区分行为差异的来源，也不符合本次
无功能变化的范围。

## 当前边界与目标架构

现有 Android 侧只有 `ParserGateway` 直接依赖 Chaquopy，解析结果已经通过 `ParseResult`
进入 ViewModel 和下载链路。因此迁移保留 `ParserGateway.parse(shareText, cookieHeader)` 的
调用契约，把其内部改成 Kotlin 平台解析路由。

### PlatformParser

定义单一平台解析接口，输入分享文本和 Cookie 字符串，返回现有 `ParseResult`。平台路由只
负责识别来源并选择解析器，不包含抖音或小红书字段规则。

### DouyinParser

按职责拆分为以下内部组件：

- `DouyinShareResolver`：提取分享链接、跟随短链跳转、识别作品 ID 与视频/图文类型。
- `DouyinRequestParameters`：按现有实现生成稳定请求参数与随机 `msToken`。
- `DouyinABogusSigner`：把现有纯 Python 签名算法等价迁移为 Kotlin 字节运算和摘要实现。
- `DouyinDetailClient`：使用浏览器 Cookie、请求头、Referer 和签名访问详情接口。
- `DouyinMediaNormalizer`：提取视频档位、最高码率独立音频、图片候选、封面和音乐候选，并
  保持现有去重与排序规则。

### XiaohongshuParser

按职责拆分为以下内部组件：

- `XiaohongshuShareResolver`：提取分享链接、处理短链和 `redirectPath`、识别笔记 ID。
- `InitialStateExtractor`：从公开页面提取平衡的状态对象，处理字符串内部括号和未定义值。
- `XiaohongshuNoteFinder`：只在目标笔记结构中定位指定 ID，避免误取页面推荐内容。
- `XiaohongshuMediaNormalizer`：生成无水印原图候选、HTTPS CDN 候选和视频档位，保持原有
  URL 优先级、对象路径恢复与排序语义。

### 网络与 JSON

解析网络请求统一由 Kotlin/OkHttp 执行，复用应用已有网络栈并显式声明直接依赖。请求超时、
跳转、Range 大小探测、Cookie、User-Agent、Referer 和 `Accept-Encoding: identity` 与现有
实现对齐。

优先使用 Android 平台 JSON 能力和项目内轻量解析辅助函数，不为了数据映射引入新的大型
反射或序列化运行时。所有响应流必须关闭；档位大小探测保持最多四路受控并发和最多两个 CDN
候选，失败时保留接口值或估算值。

## 稳定解析契约

`ParseResult` 继续作为 Android 其他模块唯一可见的解析输出。迁移不得要求 ViewModel、下载
Worker 或任务数据库理解平台私有响应。

对照测试逐项检查：

- `ok`、`platform`、`contentId`、`awemeId`、`canonicalUrl`、`referer` 和作品类型；
- 作者、描述和封面；
- 每个视频档位的宽、高、码率、帧率、编码、大小、大小来源和全部 URL；
- 档位列表顺序以及同档位去重结果；
- 独立音频、音乐和备用 CDN URL 的内容与顺序；
- 每张图片的全部候选、图片顺序和首选下载 URL；
- 网络、鉴权/风控、空详情、页面结构异常和不支持链接的错误码。

响应结构摘要只用于诊断，不参与下载决策；其键结构保持可比较，不能记录完整媒体 URL、
Cookie 或用户隐私数据。

## 迁移阶段

### 阶段一：建立行为基线

把现有 Python 单元测试涵盖的输入、边界条件和脱敏响应转为语言无关 fixtures。补充用户此前
验证过的抖音视频、抖音图文、小红书视频和小红书图文样本，并保存解析后的稳定期望值。动态
签名、时间和一次性查询参数在比较前规范化。

### 阶段二：迁移无网络纯逻辑

先迁移 URL 提取、短链结果识别、JSON/HTML 状态提取、候选 URL 递归读取、图片质量规则、
视频档位排序、大小估算和错误映射。每个组件使用 fixtures 与 Python 基准逐字段对照。

### 阶段三：迁移签名与网络

迁移 `a_bogus` 及所需摘要/编码逻辑，然后接入 Kotlin 网络客户端。签名单元测试覆盖固定随机
源、边界字符和 Unicode 输入；集成测试用真实接口证明签名有效。网络测试使用假响应覆盖
跳转链、HTTP 错误、空响应、超时和 Range 探测，避免只依赖不稳定在线接口。

### 阶段四：切换 Android 入口

在两套实现对照全部通过后，让 `ParserGateway` 只调用 Kotlin 平台路由。保持公开方法、线程
调度和 `ParseResult` 不变，运行完整 Android 回归。

### 阶段五：删除 Python 并瘦身

删除 Chaquopy Gradle 插件和配置、Python 源码、requirements、Python 测试及旧 `run` 脚本。
删除不再使用的许可证声明和构建任务；语言无关 fixtures 与 Kotlin 回归测试继续保留。

每个阶段独立提交。若后续阶段失败，可回退到最近一个通过等价验证的提交；不得通过降低断言
或删除失败样本来获得通过结果。

## APK 瘦身

移除 Python 后，Release 启用 R8 优化与混淆，并启用资源裁剪。ProGuard 规则只保留 Room、
WorkManager、Hilt、Compose、Media3、Coil 或系统反射确实需要的入口，不使用全包 `keep`。

继续执行以下约束：

- 只打包 `arm64-v8a`，与现有目标设备范围一致；
- 清理未使用依赖、重复网络实现、无引用资源和无效许可证条目；
- 使用 APK 文件级分析对 DEX、native library、resources 和 assets 分类比较；
- 保留必要的 Media3 解码/预览能力、数据库迁移、后台 Worker 和 Compose UI；
- 不通过降低图片质量、删除视频编码支持、去掉日志或减少功能换取体积。

当前约 70 MB 的签名 APK 预计降至约 15–25 MB。该范围是依据当前 Python 运行时与未裁剪 DEX
构成给出的工程估计，不是牺牲功能也必须达到的发布门槛。若大于预期，必须给出 APK Analyzer
的具体组成和保留理由；若仍有可安全删除内容，则继续优化。

## 错误处理与日志

Kotlin 解析器使用内部类型化错误，在 `ParserGateway` 边界映射到现有稳定错误码。不得把解析
异常统一折叠成无信息的 `Throwable` 文本。

日志继续覆盖输入识别、Cookie 准备、短链解析、详情请求、候选提取、大小探测和最终结果。
新增迁移相关版本标识，使日志能够确认当前运行的是纯 Kotlin 解析器。日志禁止记录 Cookie、
签名密钥材料、完整响应正文和完整远程媒体 URL；网络异常保留域名、状态码、阶段和异常类型。

并发大小探测中的单个 CDN 失败不导致整个作品解析失败。所有候选均失败时保留大小为零和明确
来源，让下载阶段仍可尝试原候选。鉴权或风控失败继续提示刷新应用内解析环境。

## 测试策略

### 纯逻辑单元测试

- 抖音和小红书分享文本、短链、作品 ID 与平台识别。
- `a_bogus` 固定随机源向量、编码边界和摘要中间结果。
- JSON 地址递归提取、稳定去重、图片无水印优先级和原图对象路径恢复。
- 视频档位去重、排序、编码识别、独立音频选择和大小估算。
- 小红书平衡对象提取、`undefined` 处理、目标笔记定位和误匹配防护。
- 所有稳定错误码及脱敏日志。

### 契约与集成测试

- 同一 fixtures 同时运行 Python 和 Kotlin，实现存在期间逐字段比较。
- 假 HTTP 客户端覆盖跳转历史、Cookie、请求头、HTTP 状态、超时、空正文和 Range 返回。
- 当前有效的真实抖音/小红书链接验证接口可达、签名有效和候选资源可读取。
- Python 删除后，把已经确认的 Kotlin 输出作为长期 golden tests，防止后续适配误删候选。

### Android 回归

- 运行全部 JVM 单元测试、Android 测试编译、Lint 和 Release 构建。
- 验证数据库升级后旧任务仍可查看、分享、预览、管理文件和重新下载。
- 验证四种视频下载模式、图片批量下载、进度/速度/真实大小、取消与删除。
- 验证音视频合成后保留原轨道，音频和视频预览仍正常且同一时刻只播放一个。
- 在可用真机上安装并执行解析、下载和进程重启测试；没有连接真机时明确列出需要用户完成的
  最小设备验收，不以模拟测试冒充设备验证。

Release 开启 R8 后必须单独做启动、数据库、后台任务、预览和文件操作验证，防止反射入口被
错误裁剪。

## 版本与交付

- `versionName` 使用 `1.3.1`，`versionCode` 使用 `22`。
- 保持 `com.local.douyindownloader` 应用 ID 和当前 v2 发布证书，支持覆盖安装。
- 签名 APK 命名为 `DouyinDownloader-1.3.1-arm64.apk`，复制到
  `/mnt/Android/douyinDownload`。
- 提供最终 APK 大小和 SHA-256，并核对签名证书与 1.3.0 一致。
- 所有验收通过后推送 GitHub 当前分支和 `main`，本次不创建 GitHub Release。
- 未通过硬性解析等价或关键 Android 回归时，不复制、不推送、不对外发布 APK。

## 完成标准

- APK 中不存在 Chaquopy、Python 解释器、Python 标准库、requests、gmssl 或 Python 应用代码。
- 仓库当前版本不存在 `python3/`、旧 `run` 和 Python 构建配置。
- 已覆盖作品的媒体候选集合、优先级、档位和音频轨道与迁移前一致。
- 用户可见功能、任务数据、下载目录和操作流程保持不变。
- 应用可用原签名覆盖安装，旧任务和设置保持可用。
- Release 裁剪后完整回归通过，并记录可解释的最终体积组成。
- APK、校验值和 GitHub 提交均对应同一份通过验证的源码。
