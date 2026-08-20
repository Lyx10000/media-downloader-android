# 抖音/小红书资源下载器

Python 脚本，解析抖音和小红书分享链接。抖音支持视频、图文和图文
BGM；小红书支持视频和图片。

## 使用方式

```bash
apt update
apt install chromium chromium-driver
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
会自动启动无头 Chromium，由浏览器执行抖音前端代码，再从页面数据中提取资源
地址。浏览器 Cookie 保存在 `python3/.douyin-browser-profile/`，供后续解析复用。

在 Termux + PRoot Debian 中 Chromium 必须使用 `--no-sandbox`；脚本已经自动
添加该参数，不需要图形桌面。

## 声明

仅供学习交流使用，请勿商用。

MIT License
