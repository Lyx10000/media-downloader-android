# B站普通投稿适配：实现范围与核查记录

核查日期：2026-09-06；独立 worktree：`/root/douyin_downloader_bilibili`。
分支：`feature/bilibili-adapter`；基线：`7051abd1f86843f018f39bcbd31a5c2c871075c6`。
本文是原预研的实施版；主目录原文保留。本轮已获得实现、针对性测试、debug 构建和本分支提交授权。
主代理负责最终审查/合并；本轮不 push、不 release、不复制 APK 到共享目录。

## 范围与实现决定

- 普通 UGC 的 BV/av、b23、完整裸 BV/av；保留 BV 大小写，拒绝无歧义规则之外的裸文本。
- 标准页、移动站同类视频路径归一；无 `p` 明确第一 P，指定 `p` 只下载对应 P，不展开全集。
- 元数据包括作者、UID、标题/分 P 标题、封面、平台；任务内容身份使用 `BV:cid`，标准地址保留 `p`。
- 只展示本次返回的兼容 H.264 视频轨；AAC 选一条实际可用音轨，备用 URL 始终属于同一轨。
- DASH 分别映射现有 `MediaVariant.urls` 和 `ParseResult.audioUrls`；不扩展公共媒体模型。
- 沿用用户现有下载模式、默认模式、并发与队列，包含仅保留合成 MP4，不施加原预研的全局默认/串行建议。
- 下载后验证 H.264/AAC 实际轨道，合并后核对音视频均存在再发布；仅 MP4 模式沿用原成品发布分支。
- DASH 缺音频明确失败；durl 多段明确不支持，绝不将顺序片段当作 CDN 备用列表。
- 当前单段 durl 也未接入，明确报格式不支持；不引入新的拼接/转码/下载引擎。
- 首页自动列出 B站状态与可点击登录入口；官方网页登录完成返回后进行登录状态验证，提供重新加载按钮。
- 作者/UP 主批量、合集展开、番剧、影视、课程、付费/专属、直播、动态图文、互动分支、字幕弹幕均不支持。
- 不绕登录/VIP/地域/DRM/验证码限制，不声称登录或会员一定能取得某档画质。
- 版本保持 `1.6.2 / 42`，debug 不使用 R8；不增加依赖，不改项目许可证。

## 首选参考：用户指定 BiliDownload

【源码观察】已只读 clone [KafuuNeko/BiliDownload](https://github.com/KafuuNeko/BiliDownload)，未执行其脚本或构建。
固定参考提交：`95da6cc524ae713bd1194111d79f6b8a29d0c93a`。
[该提交 LICENSE](https://github.com/KafuuNeko/BiliDownload/blob/95da6cc524ae713bd1194111d79f6b8a29d0c93a/LICENSE) 为 GPL-3.0。
遵循用户边界：只研究接口流程/字段语义，独立组织 Kotlin 实现；未复制源码、资源、整段翻译或 FFmpeg AAR。

| 直接来源（均固定上述提交） | 采纳的思路 | 未采纳内容 |
| --- | --- | --- |
| [BiliApiService.kt][ref-api] | 稿件详情 → cid → 普通播放资源；用返回数据判断可用性 | 番剧、收藏、历史等服务；不照搬 Retrofit 层 |
| [BiliVideoRepository.kt][ref-repository] | 元数据和音视频资源分步读取 | 全格式需求、附加内容和原 repository 实现 |
| [BiliVideoData.kt][ref-video] | pages 中的 page/cid、owner、标题/封面及权限标记 | 原数据类及不在授权范围的字段业务 |
| [BiliPlayStreamData.kt][ref-stream] | DASH 音视频分轨、主 URL 与 backupUrl 的同轨关系 | 杜比、FLAC、原轨道类与选择代码 |

成熟度判断：参考项目是可审阅的 Android 实现，存在 UGC 完整流程；其 README 功能描述不能视作本应用的实测结果或权限保证。
本次仅使用常规稿件详情和普通播放资源流程，无签名密钥读取、WBI 实现或多级兜底。

## 其他预研来源

- [yt-dlp B站提取器](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py)：辅助认识分 P、格式和失败边界；源码 Unlicense，不集成 Python。
- [lux B站提取器](https://github.com/iawia002/lux/blob/master/extractors/bilibili/bilibili.go)：MIT，仅辅助对照短链/音视频流程，不嵌入 Go。
- [BBDown](https://github.com/nilaoda/BBDown)：此前核查已于 2026-05-14 归档，当前默认分支主体代码已移除；不作为实时维护保证。
- [API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) 与 [Nemo bilibili-api](https://github.com/Nemo2011/bilibili-api)：此前核查为关停状态，历史许可证未从原文件验证，不恢复删除内容。
- [B站开放平台入口](https://open.bilibili.com/doc)：动态正文未完整提取，未据此认定存在面向任意媒体的官方下载 API。
- [Android MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)：原生封装能力依据，不能代替真机兼容性测试。

## 现有结构接入与边界

| 接入点 | 本轮处理 |
| --- | --- |
| SourcePlatform / KotlinParserRouter / ParserGateway | 新平台、域名/裸编号入口、原路由注册；Gateway 复用 |
| BilibiliMediaParser | 链接与分 P 验证、元数据/权限、DASH 归一化；每步失败明确分类 |
| ParserHttpClient | 增加单跳请求，原平台默认请求行为不变；短链先验证下一跳再发请求 |
| DownloadExecutor / MediaTrackProcessor | 复用下载/合并/产物管理；仅 B站增加实际轨道核对 |
| MainViewModel / HomeScreen / PlatformLoginCredential | 登录状态及官方页面入口；不自动启动隐藏挑战流程 |
| CreatorLibraryViewModel / CreatorRepository | B站不进入作者批量或自动注册作者目录；单条任务仍保留作者元数据 |
| TaskRedownloadCoordinator | 保留分 P 身份；原档位不可用时拒绝静默替换；沿用用户重新解析入口 |
| DiagnosticLogger | 补充 B站敏感字段脱敏；解析摘要白名单，不保存原始服务端响应 |

短链最多五跳且不带 Cookie；业务请求仅发往固定 API 主机，不自动跟随携带凭据的跨域跳转。
媒体地址限定已知 B站 CDN 域名、拒绝 userinfo/仿冒域名，主备保持原查询参数；媒体下载不传登录 Cookie。
临时直链失效需要用户重新下载触发原重解析流程；不引入后台自动刷新链路。
风控响应立即返回，无账号池、设备指纹伪造、验证码代解、代理或权限突破。
MediaVariant 展示来自真实尺寸/码率/编码；不把 accept_quality/support_formats 当成可下载资源。
估算大小保持 estimated；AAC 多档选一条，未提供多语言或音轨菜单。
仅音频模式沿用当前行为，仍可能先下载视频做探轨，不宣传额外省流优化。

## 验证矩阵与记录

【离线测试目标】链接/分 P 默认与越界、同稿不同 cid、av 大整数、短链凭据隔离、受限稿件、缺音频、多段 durl、真实档位、CDN 候选、登录失效与脱敏。
【回归目标】原五平台解析/域名与登录策略、媒体音源策略和重新选档；不做重型全套。
【构建目标】SDK `/opt/android-sdk`，`:app:assembleDebug`，不改版本、不运行 release/R8。
【线上验证】采用用户提供 `BV1vobn6dEAR` 与 `BV1Z6t96QEG4`，匿名低频，遇风控即停止。
测试只输出状态/轨道结构，不输出 Cookie、响应体、签名或完整 CDN 地址。

最终验证时间：2026-09-06 19:42（Asia/Shanghai）。以下区分实测与尚未通过的闭环。

| 用户样本 | 匿名实际资源结构 | 本适配器提供档位 | 媒体探测 |
| --- | --- | --- | --- |
| `BV1vobn6dEAR` | HTTP 200；DASH video=2、audio=3；durl=0；视频均 AVC | 852×480、640×360；选定 AAC 的 3 个 CDN 候选 | 低档视频与选定音轨各一次 64 KiB Range 请求，均 HTTP 403、0 字节 |
| `BV1Z6t96QEG4` | HTTP 200；DASH video=4（AVC/HEVC）、audio=3；durl=0 | 852×480、640×360，仅保留 AVC；选定 AAC 的 3 个 CDN 候选 | 同上，视频/音频均 HTTP 403、0 字节 |

【实测】两条样本均由本项目 Kotlin 适配器成功完成元数据→P1→资源归一化。
【未通过】CDN 探测均被拒绝，尚不能证明本环境可完成真实下载、MP4 合并或音画同步。
403 原因可能涉及地址鉴权、请求环境或平台限制，当前未确认，不推断“登录即可解决”。
已停止进一步网络探测，未试探所有 CDN/档位、未更换账号或引入新接口/绕过策略。
测试中的在线成功断言仅针对解析；CDN 探测结果独立记录，不能把测试绿色状态当成下载成功。

针对性测试：**82 项，80 通过、2 跳过、0 失败/错误**；跳过项来自原 Instagram 测试，未修改其代码。
覆盖 B站解析/HTTP 边界/匿名样本，以及 SourcePlatform、PlatformParsers、Instagram/X 解析、媒体策略、选档、凭据、登录视口与重下载策略。
`assembleDebug` 成功；APK 位于副本 `android/app/build/outputs/apk/debug/app-debug.apk`，未复制或发布。
`aapt2 dump badging` 核对 `versionName=1.6.2`、`versionCode=42`、`application-debuggable`。
本环境沿用原 Gradle 的 Termux aapt2 配置；`android/local.properties` 仅本地设置 SDK 路径，未提交。
未运行 release、R8、完整测试套件或第三方项目脚本。

实际验证命令（在副本 `android/`；在线环境开关只影响本轮 B站 smoke test）：

```sh
BILIBILI_LIVE_SMOKE=1 ./gradlew :app:testDebugUnitTest \
  --tests 'com.local.douyindownloader.Bilibili*Test' \
  --tests 'com.local.douyindownloader.SourcePlatformTest' \
  --tests 'com.local.douyindownloader.PlatformParsersTest' \
  --tests 'com.local.douyindownloader.InstagramMediaParserTest' \
  --tests 'com.local.douyindownloader.XMediaParserTest' \
  --tests 'com.local.douyindownloader.MediaDownloadPolicyTest' \
  --tests 'com.local.douyindownloader.VariantMatcherTest' \
  --tests 'com.local.douyindownloader.PlatformLoginCredentialDetectorTest' \
  --tests 'com.local.douyindownloader.TaskRedownloadStrategyTest' \
  --tests 'com.local.douyindownloader.DownloadUrlPolicyTest' \
  --tests 'com.local.douyindownloader.LoginViewportTest' \
  :app:assembleDebug --max-workers=2 --console=plain
```

离线复核应去掉 `BILIBILI_LIVE_SMOKE=1`，避免不必要地重复在线请求。
最初编译补齐了任务列表的 B站枚举分支；随后一轮旧编译快照与新登录地址测试不同步，最终同一源码重跑已全部收敛。
登录入口由返回 404 的 `/login` 修正为 [官方移动登录页](https://passport.bilibili.com/h5-app/passport/login)，匿名 HEAD 返回 200；未实际登录。

## 未验证与交付门槛

- 网页接口并非稳定公开下载契约；返回字段、CDN 有效期与风控策略可能变化。
- 登录账号/VIP 高清、官方 WebView 真机交互、地域差异未覆盖；凭据存在与服务器确认登录分开。
- Android 原生合并在目标设备的长视频、时长和音画同步仍需主代理/设备验收；JVM 测试不代替真机。
- 不保证所有稿件支持 H.264/AAC 或完整 SegmentBase；不兼容时明确失败，不自行追加大量接口。
- 已下载轨道的同 cid 配对有结构校验；真实音画同步和文件完整性须通过产物播放验收。
- 作者公开列表仅作为以后候选，不在本次提交开放。
- 本轮提交供主代理审查，不表示已合并、已发布或已完成上述未验证项。

## 主代理审查建议

1. 审阅分 P 的 `BV:cid` 身份与重下载拒绝静默换 P/换档的分支。
2. 核对 MP4_ONLY 仅发布合成文件、DASH 只有一组 AAC 候选、缺轨和多段 durl 不伪成功。
3. 在允许访问的目标设备/网络确认 CDN 403 原因及实际保存，再验收原生合并、取消、文件清理与音画同步。
4. 验证官方 WebView 登录交互、退出后状态刷新；不以匿名 480p/360p 结果外推会员或高清支持。
5. 主线整合后运行相关平台回归；本副本没有合并主线后续 Instagram 提交，也未修改主目录文档。

## 主线后续：CDN 请求配置修复（2026-09-06）

- 已合并至主线 `dfc4023`；上面的 CDN 403 是第一版验证记录，不是后续修复的最终结果。
- 对同一个刚获取的视频 URL 做匿名 A/B：旧手机 UA＋主站 Referer 返回 403；参考项目的桌面 UA＋移动站 Referer/Origin 返回 206，读取 1024 字节。两次均未携带 Cookie，尚未逐项拆分判断哪个头是必要条件。
- 增加运行时 `MediaRequestProfile`：B站解析请求使用一致的浏览器配置，预检查、测速、分段下载、视频和音轨下载共用媒体请求配置。其他平台保留原默认头与重定向行为。
- B站媒体连接逐跳校验 HTTPS、域名、端口及 userinfo；不向媒体 CDN 传 Cookie/Authorization，不把凭据写入任务存储。未照搬参考实现中把压缩算法填入 Accept-Language 的配置。
- 两个原始 BV 样本的 Kotlin 视频/音轨 Range 探测均返回 206，各读取 65536 字节，检测到 MP4 的 ftyp/moov；在线测试现在会对媒体 403 明确失败，不再把仅解析成功计作下载成功。
- 首轮本地请求头测试发现桌面 OpenJDK 默认丢弃 Origin（并非应用主动漏传）；仅在 JVM 单测进程开启相应 header 选项，以验证真实出站请求头。Android 运行配置未改变。
- 仍未验证：用户账号高清档、手机完整文件下载、原生合并和最终音画同步。局部 Range 探测通过不等于这些项目已经验收。

[ref-api]: https://github.com/KafuuNeko/BiliDownload/blob/95da6cc524ae713bd1194111d79f6b8a29d0c93a/app/src/main/java/cc/kafuu/bilidownload/common/network/service/BiliApiService.kt
[ref-repository]: https://github.com/KafuuNeko/BiliDownload/blob/95da6cc524ae713bd1194111d79f6b8a29d0c93a/app/src/main/java/cc/kafuu/bilidownload/common/network/repository/BiliVideoRepository.kt
[ref-video]: https://github.com/KafuuNeko/BiliDownload/blob/95da6cc524ae713bd1194111d79f6b8a29d0c93a/app/src/main/java/cc/kafuu/bilidownload/common/network/model/BiliVideoData.kt
[ref-stream]: https://github.com/KafuuNeko/BiliDownload/blob/95da6cc524ae713bd1194111d79f6b8a29d0c93a/app/src/main/java/cc/kafuu/bilidownload/common/network/model/BiliPlayStreamData.kt
