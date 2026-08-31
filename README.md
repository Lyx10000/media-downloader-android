# 抖音/小红书资源下载器

Python 脚本，解析抖音和小红书分享链接。抖音支持视频、图文和图文
BGM；小红书支持视频和图片。

项目同时提供完全本地运行的 Android 自用版，支持从抖音分享、手动粘贴、全部
清晰度、原图、四种音视频模式、后台下载和脱敏诊断日志。安装和构建方式见
[`android/README.md`](android/README.md)。

## 使用方式

```bash
apt update
apt install chromium chromium-driver ffmpeg
cd python3
python3 -m venv venv
venv/bin/pip install -r requirements.txt
venv/bin/python douyin_downloader.py
```

也可以在项目根目录直接运行 `./run`。启动后粘贴完整分享文本或链接，选择
需要的资源；文件会保存到 `python3/download/`，并同步到
`/mnt/Android/douyin_download/`。

下载目录默认按中国时区（`Asia/Shanghai`）命名。如需改用其他时区，可在运行
前设置环境变量，例如 `DOWNLOAD_TIMEZONE=UTC ./run`。

## 原理

抖音分享页不再向普通 HTTP 客户端提供作品详情。本脚本在 HTTP 解析失败后，
会自动启动无头 Chromium，由浏览器生成安全 Cookie，再调用带 `a_bogus` 签名的
作品详情接口获取完整媒体档位。视频默认按分辨率、帧率和码率选择最高档，下载前
也可以手动选择其他清晰度。DASH 分离式视频会由 ffmpeg 以 stream copy 方式无损
合并音视频；音视频已经封装在同一个 MP4 中时，也会用 stream copy 无损拆轨，整个
过程都不会重新编码。选择清晰度后始终可以选择以下保存方式：

1. 视频轨 + 音频轨 + 合成视频，并保留全部三个文件（默认）
2. 只保存视频轨和音频轨，不合成
3. 只保存视频轨
4. 只保存音频轨

默认方式生成 `video_1.mp4`（合成文件或抖音原始音视频文件）、
`video_1_video.mp4`（视频轨）和 `video_1_audio.m4a`（音频轨）。合成成功或失败
都不会删除两个分轨文件。音视频合一的源文件如果没有安装 ffmpeg，默认模式会保留
原始 MP4，但无法生成分轨；其他三种模式会提示先安装 ffmpeg。

详情接口不可用时，脚本会继续从页面数据和播放器网络资源中提取地址；这种兜底
路径会明确提示无法确认是否为最高档。浏览器 Cookie 保存在
`python3/.douyin-browser-profile/`，供后续解析复用。也可以通过环境变量
`DOUYIN_COOKIE` 补充登录 Cookie。

图文优先使用 `origin_url`，其次选择 `url_list` 中质量最高的无水印 CDN 地址，
文件按服务器返回内容原样保存，不转格式、不二次压缩。“最高档”指抖音当前接口
公开提供的最高质量版本，不保证等于作者上传前的母文件。

在 Termux + PRoot Debian 中 Chromium 必须使用 `--no-sandbox`；脚本已经自动
添加该参数，不需要图形桌面。

## 声明

仅供学习交流使用，请勿商用。

MIT License
