# 小红书平台适配设计

日期：2026-09-04

## 目标

在不破坏现有抖音任务、下载模式和覆盖安装能力的前提下，将 Android 应用扩展为首个双平台版本：

- 自动识别抖音和小红书分享文本、完整链接及短链接。
- 下载当前账号可访问的小红书图文笔记原始图片候选。
- 下载小红书视频笔记可获得的最高档位，并允许用户选择其他档位。
- 保留现有任务、进度、取消、重试、删除、文件夹、分享、预览和诊断功能。
- 在任务卡中始终用文字标出“抖音”或“小红书”。

首期只处理单篇小红书笔记，不处理用户主页、合集、评论媒体、直播、私密内容绕过或 DRM。

## 平台边界

新增 `SourcePlatform`，集中保存平台 ID、显示名称、Cookie 首页、Referer 和域名规则。`SupportedSourceDetector` 只负责从分享文本中找出受支持链接并确定平台。

`ParserGateway` 继续通过 Chaquopy 调用 Python，但 Python 桥改为路由器：

- 抖音交给现有解析器，行为保持不变。
- 小红书交给独立解析器，支持 `xiaohongshu.com/explore/...`、`/discovery/item/...`、`/note/...`、`xhslink.cn` 和 `xhslink.com`。

平台检测、平台会话和媒体归一化相互独立，后续新增平台时不再修改下载任务主流程。

## 通用数据模型

`ParseResult` 新增：

- `platform`：来源平台，旧任务缺失时默认抖音。
- `contentId`：替代抖音专用的 `awemeId`。
- `canonicalUrl`：解析后的稳定作品链接，用于重新下载。
- `referer`：下载媒体时使用的平台 Referer。

JSON 读取兼容旧字段 `aweme_id`，因此 Room 数据库不升级表结构；已有任务仍能打开、删除和重新下载。新 JSON 写入通用字段，并可为抖音保留兼容字段。

首期沿用“单视频或多图片”的媒体模型，因为抖音与小红书笔记都能被该模型准确表达。未来支持图文混排或多视频平台时再升级为 `mediaItems`，本次不提前扩大范围。

`TaskRecord` 从任务规格 JSON 推导平台，不新增数据库列。任务卡使用静态文字徽标展示平台，不能只用颜色表达来源。

## 小红书解析

### URL 与目标绑定

解析器先打开分享 URL 并跟随跳转，保留最终 URL 中的 `noteId`、`xsec_token` 和 `xsec_source`。页面数据必须与目标 note ID 一致；页面推荐区域中的其他笔记和 CDN 地址不得作为目标媒体。

### 页面数据

使用当前 WebView Cookie 发起页面请求，从 HTML 中安全提取 `window.__INITIAL_STATE__`。兼容 `note.noteDetailMap`、`note.note`、camelCase 与 snake_case 字段。解析状态不存在时返回可诊断错误，不使用模糊递归结果冒充目标作品。

小红书允许先匿名解析。遇到登录、风控或空详情时，应用进入现有的隐藏环境准备流程，使用小红书首页刷新 Cookie 后仅重试一次。设置页同时提供可见的小红书登录/刷新环境。

### 图片

图文笔记读取 `imageList`/`image_list`。每张图片保留多个候选并按以下顺序下载：

1. 明确的原始 URL 字段。
2. 从 CDN 对象路径恢复的无尺寸变换 URL，保留 `spectrum/` 和 `notes_pre_post/` 前缀。
3. `urlDefault`/`url_default`。
4. 其他预览 URL 作为最后回退。

候选去重；下载器按顺序尝试，因此原始对象不可用时仍能退回可读版本。不会把封面重复当作正文图片。

### 视频

视频笔记从 `video.media.stream` 收集 H.264、H.265、AV1 等直连 MP4，并兼容 `originVideoKey`、`masterUrl`、`backupUrls` 和直接下载字段。档位按像素数、帧率、码率、文件大小排序；同质量才优先 H.264。默认最高档，用户仍可选择其他档位。

只接收绑定目标笔记的数据和 `xhscdn.com` 直连媒体。HLS 清单与分片不在首期范围；如果只有 HLS，则明确提示当前版本无可下载直连档位。

小红书常见视频为音视频合一 MP4，下载后继续使用现有轨道探测。若文件含内置音频，四种保存模式和预览能力保持不变。

## 下载与重新下载

下载器不再写死抖音 Referer，而是读取 `ParseResult.referer`。User-Agent 保持统一，Cookie 不写入任务规格或诊断日志，避免持久化敏感信息。

重新下载优先使用 `canonicalUrl`；旧抖音任务继续由旧 ID 规则生成稳定链接。重新解析时根据任务平台读取对应 Cookie，并沿用原清晰度匹配逻辑。

日志中的 `content_id` 和 `platform` 取代新增流程里的抖音专用字段。现有日志仍可读取。

## 界面

- 首页文案改为“抖音/小红书分享文本或链接”。
- 解析环境进度文案带平台名称，但仍不显示后台网页。
- 结果页使用平台名称作为标题兜底。
- 任务卡日期附近显示带文字的平台徽标，使用 Material 3 主题色和形状，满足深色模式与字体缩放。
- 设置页分别提供抖音和小红书登录/刷新入口。

## 错误处理

- 不支持的链接：提示当前支持抖音和小红书。
- 短链失效或无法确定 note ID：提示重新复制最新分享链接。
- 登录/风控：刷新一次平台环境，仍失败则提示从设置页登录。
- 删除、私密或无权访问：不重试下载，明确标注内容不可用。
- 无图片或视频地址：保留解析形状日志，不创建空下载任务。
- 所有错误继续脱敏，不记录 Cookie、`xsec_token` 完整值或带查询参数的完整 CDN URL。

## 测试与交付

- Python：URL 路由、初始状态提取、目标 ID 绑定、原图候选、视频档位排序和错误分类。
- Kotlin：平台检测、旧 JSON 兼容、稳定链接、任务平台推导、平台 Referer 和 UI 辅助函数。
- 回归：现有全部单元测试、debug 编译、lint、release 构建。
- 版本升级为 `1.2.0`，使用现有签名证书生成 arm64 APK，复制到 `/mnt/Android/douyinDownload`。
- 推送 GitHub `main`，不创建 GitHub Release。

## 研究依据

- 小红书页面状态与目标 note ID 绑定：<https://github.com/ljb1020/video-batch-download/blob/main/scripts/platforms/xiaohongshu.js>
- 小红书图片对象路径处理：<https://github.com/NanmiCoder/MediaCrawler/blob/main/media_platform/xhs/help.py>
- 小红书开放平台当前主要面向店铺工具型应用：<https://open.xiaohongshu.com/>
