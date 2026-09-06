# 作者本地下载与 X / Instagram 作者来源

已确认范围：保持现有双任务并发、分段连接、解析调度不变。沿用 Kotlin、现有作者模型和分页 UI，不修改单条作品解析路线。

- 作者详情的“下载记录”改为“本地下载”。合并现有任务及尚未创建任务的批次准备记录；解析失败可选中、删除记录和重新提交。删除记录不删除远端作品索引；有文件任务沿用既有安全删除和重新下载流程。
- 作者入口移除完成/失败/总量统计，只保留有操作意义的暂停/前台准备提示。
- 本地任务卡片读取现有任务进度、状态、速度，不额外轮询；仅真实传输且已知总量显示确定进度条。
- X 与 Instagram 支持主页链接、所选平台的准确用户名。以服务端稳定 ID 归档，拒绝把作品路径或保留路径识别为用户名。
- X 读取作者媒体时间线，仅保留作者自己的原生图片、视频/GIF，不递归收集转发、引用、推荐作品。
- Instagram 读取作者帖子与 Reels，支持轮播；不包含 Stories、直播、Notes 或被标记页。按游标分页、作品 ID 去重；缺失结构报错，不把登录限制误判为空主页。
- 明确登录、限流、账号不可访问、接口变动错误；沿用现有风控暂停机制，不新增自动重试调度。
- 使用自有 Kotlin 实现，仅参考公开接口形状，不集成或复制第三方下载器源码。

验证：定向测试用户名输入、作者归属过滤、游标、准备失败记录和媒体类型；编译 debug、保留版本 1.6.2 与现有签名。未获登录凭证的真实作者翻页需用户端验证，不能把模拟响应测试宣称为端到端成功。不上线发布。

接口资料（2026-09-06，仅核对接口名称、参数和返回字段；不复制实现）：

- https://github.com/mikf/gallery-dl/blob/master/gallery_dl/extractor/twitter.py — UserByScreenName / UserMedia 的网页 GraphQL operation 与游标。
- https://github.com/instaloader/instaloader/blob/master/instaloader/structures.py — web_profile_info、帖子和 Reels connection 名称及文档 ID。
- https://github.com/instaloader/instaloader/blob/master/instaloader/nodeiterator.py — GraphQL after / page_info 协议。

当前限制：X 作者访问要求现有有效网页登录凭证；Instagram 匿名资料访问可能拒绝，Reels 翻页要求登录。接口 ID 可能变化，异常不会覆盖为“空主页”。开发环境匿名 Instagram 实测返回 HTTP 401（要求等待），故本轮不能声称已通过真实登录后的完整作者下载。
