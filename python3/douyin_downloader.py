#!/usr/bin/python3
# -*- coding: UTF-8 -*-

##################################################################
##                                                              ##
##    下载抖音/小红书视频（图文帖自动下载原图+BGM）              ##
##    GITHUB：https://github.com/kajweb/douyin_downloader        ##
##                                                              ##
##################################################################

import requests
import configparser
import os
import re
import json
import shutil
import subprocess
import tempfile
from datetime import datetime

from requests.packages import urllib3
urllib3.disable_warnings()

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BROWSER_PROFILE_DIR = os.path.join(SCRIPT_DIR, ".douyin-browser-profile")

TYPE_PREFIX = {"video": "video", "image": "img", "bgm": "bgm", "bgm_direct": "bgm"}


def get_headers(filename, key):
    conf = configparser.ConfigParser()
    conf.read(filename)
    return dict(conf._sections[key])


def _session(headers):
    s = requests.Session()
    s.verify = False
    s.headers.update(headers)
    return s


# ── 抖音 ────────────────────────────────────────────────────────

def parse_douyin(url, headers):
    res = _session(headers).get(url, allow_redirects=True, timeout=15)
    if res.status_code != 200:
        raise Exception(f"请求失败，状态码：{res.status_code}")

    html = res.text
    aweme_match = re.search(r'"aweme_type"\s*:\s*(\d+)', html)
    aweme_type = int(aweme_match.group(1)) if aweme_match else None

    if aweme_type in (2, 68):
        try:
            return _parse_douyin_images(html)
        except Exception:
            pass
    elif '"play_addr"' in html:
        try:
            return _parse_douyin_video(html)
        except Exception:
            pass

    item_id, item_kind = _douyin_item_target(res.url, url)
    if not item_id:
        raise Exception("抖音链接已打开，但无法从跳转地址识别作品 ID")

    print("分享页未包含作品数据，正在启动 Chromium 解析……")
    detail, browser_headers = _parse_douyin_with_browser(item_id, item_kind)
    return _douyin_items_from_detail(detail, browser_headers)


def _douyin_item_target(*urls):
    """从短链跳转地址或直链中识别作品 ID 和页面类型。"""
    for url in urls:
        match = re.search(r'/(?:share/)?(note|video)/(\d+)', url or "")
        if match:
            return match.group(2), match.group(1)

        # 某些分享链接只有末尾的纯数字作品 ID。
        match = re.search(r'/(\d{15,22})(?:[/?#]|$)', url or "")
        if match:
            return match.group(1), "video"

    return None, None


def _chromium_user_agent(chromium_path):
    try:
        version = subprocess.run(
            [chromium_path, "--version"],
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        ).stdout
        major = re.search(r'(\d+)\.', version).group(1)
    except (AttributeError, OSError, subprocess.SubprocessError):
        major = "131"

    return (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        f"(KHTML, like Gecko) Chrome/{major}.0.0.0 Safari/537.36"
    )


def _find_douyin_detail(value, item_id):
    """在 React Server Components 数据中递归寻找当前作品。"""
    if isinstance(value, dict):
        if str(value.get("awemeId")) == item_id:
            aweme = value.get("aweme")
            if isinstance(aweme, dict) and isinstance(aweme.get("detail"), dict):
                return aweme["detail"]
            if "images" in value or "video" in value:
                return value

        for child in value.values():
            detail = _find_douyin_detail(child, item_id)
            if detail:
                return detail
    elif isinstance(value, list):
        for child in value:
            detail = _find_douyin_detail(child, item_id)
            if detail:
                return detail

    return None


def _detail_from_pace_scripts(script_texts, item_id):
    """解析抖音页面 self.__pace_f 中的 React 服务端数据。"""
    prefix = "self.__pace_f.push("

    for text in script_texts:
        start = text.find(prefix)
        if start < 0:
            continue

        payload_end = text.rfind(")")
        if payload_end <= start:
            continue

        try:
            push_args = json.loads(text[start + len(prefix):payload_end])
        except (ValueError, json.JSONDecodeError):
            continue

        if len(push_args) < 2 or not isinstance(push_args[1], str):
            continue

        for line in push_args[1].splitlines():
            _, separator, frame_text = line.partition(":")
            if not separator:
                continue
            try:
                frame = json.loads(frame_text)
            except (ValueError, json.JSONDecodeError):
                continue

            detail = _find_douyin_detail(frame, item_id)
            if detail:
                return detail

    return None


def _parse_douyin_with_browser(item_id, item_kind):
    """由 Chromium 执行抖音签名脚本，再读取页面内的作品数据。"""
    try:
        from selenium import webdriver
        from selenium.webdriver.chrome.options import Options
        from selenium.webdriver.chrome.service import Service
        from selenium.webdriver.support.ui import WebDriverWait
    except ImportError as exc:
        raise Exception(
            "缺少 Selenium，请执行：venv/bin/pip install -r requirements.txt"
        ) from exc

    chromium_path = shutil.which("chromium") or shutil.which("chromium-browser")
    driver_path = shutil.which("chromedriver")
    if not chromium_path or not driver_path:
        raise Exception(
            "缺少 Chromium/ChromeDriver，请执行："
            "apt install chromium chromium-driver"
        )

    os.makedirs(BROWSER_PROFILE_DIR, exist_ok=True)
    user_agent = _chromium_user_agent(chromium_path)

    options = Options()
    options.binary_location = chromium_path
    for argument in (
        "--headless=new",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--disable-gpu",
        "--disable-blink-features=AutomationControlled",
        "--window-size=1365,1000",
        "--lang=zh-CN",
        "--no-first-run",
        "--no-default-browser-check",
        f"--user-agent={user_agent}",
        f"--user-data-dir={BROWSER_PROFILE_DIR}",
    ):
        options.add_argument(argument)

    options.add_experimental_option("excludeSwitches", ["enable-automation"])
    options.add_experimental_option("useAutomationExtension", False)

    try:
        driver = webdriver.Chrome(
            service=Service(driver_path, log_output=subprocess.DEVNULL),
            options=options,
        )
    except Exception as exc:
        raise Exception(f"Chromium 启动失败：{exc}") from exc

    page_url = f"https://www.douyin.com/{item_kind}/{item_id}"
    try:
        driver.set_page_load_timeout(45)
        driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
            "source": (
                "Object.defineProperty(navigator, 'webdriver', "
                "{get: () => undefined});"
            )
        })
        driver.get(page_url)

        def find_detail(current_driver):
            page_data = current_driver.execute_script(
                """
                const itemId = arguments[0];
                const scripts = Array.from(document.scripts)
                    .map(script => script.textContent || '')
                    .filter(text => text.includes(itemId) &&
                                    text.includes('self.__pace_f.push'));
                const elementUrls = Array.from(
                    document.querySelectorAll('video source, video')
                ).flatMap(element => [element.currentSrc, element.src])
                    .filter(Boolean);
                const performanceUrls = performance.getEntriesByType('resource')
                    .map(entry => entry.name)
                    .filter(url => url.includes('douyinvod.com'));
                const mediaUrls = Array.from(new Set(
                    [...elementUrls, ...performanceUrls]
                        .filter(url => url.startsWith('http'))
                ));
                return {scripts, mediaUrls};
                """,
                item_id,
            )
            detail = _detail_from_pace_scripts(page_data["scripts"], item_id)
            if detail:
                return detail

            # 普通视频的新页面可能把 SSR 中的 aweme 设为 null，但播放器
            # 已经拿到了带签名的 <source> 地址。优先保留明确带作品 ID 的 URL。
            if item_kind == "video":
                media_urls = page_data["mediaUrls"]
                tagged_urls = [url for url in media_urls if item_id in url]
                if tagged_urls or media_urls:
                    return {
                        "awemeId": item_id,
                        "video": {
                            "playAddr": [
                                {"src": url} for url in (tagged_urls or media_urls)
                            ]
                        },
                    }

            return False

        detail = WebDriverWait(driver, 30, poll_frequency=0.5).until(find_detail)
        # 登录态保留在浏览器 profile 内，不把账号 Cookie 发送给媒体 CDN。
        return detail, {"User-Agent": user_agent}
    except Exception as exc:
        title = ""
        try:
            title = driver.title
        except Exception:
            pass
        suffix = f"（页面标题：{title}）" if title else ""
        raise Exception(f"Chromium 未能取得抖音作品数据{suffix}：{exc}") from exc
    finally:
        driver.quit()


def _url_candidates(value):
    """兼容抖音 camelCase/snake_case 地址对象，按原顺序收集 URL。"""
    urls = []
    if isinstance(value, str):
        if value.startswith("http"):
            urls.append(value)
    elif isinstance(value, list):
        for child in value:
            urls.extend(_url_candidates(child))
    elif isinstance(value, dict):
        for key in ("urlList", "url_list", "src", "url", "uri"):
            if key in value:
                urls.extend(_url_candidates(value[key]))

    return list(dict.fromkeys(urls))


def _pick_url(value, preferred_extensions=()):
    urls = _url_candidates(value)
    for extension in preferred_extensions:
        for url in urls:
            if re.search(rf'\.{re.escape(extension)}(?:\?|$)', url, re.IGNORECASE):
                return url
    return urls[0] if urls else None


def _douyin_video_urls(video):
    bitrate_urls = []
    for bitrate in video.get("bitRateList") or video.get("bit_rate") or []:
        urls = _url_candidates(bitrate.get("playAddr") or bitrate.get("play_addr"))
        if not urls:
            continue
        rate = bitrate.get("bitRate") or bitrate.get("bit_rate") or 0
        bitrate_urls.append((rate, urls))

    urls = []
    if bitrate_urls:
        for _, candidates in sorted(bitrate_urls, key=lambda item: item[0], reverse=True):
            urls.extend(candidates)

    urls.extend(_url_candidates(video.get("playAddr") or video.get("play_addr")))
    urls = [url.replace("/playwm/", "/play/") for url in urls]
    return list(dict.fromkeys(urls))


def _guess_audio_ext(url):
    match = re.search(r'\.(mp3|m4a|aac|wav)(?:\?|$)', url, re.IGNORECASE)
    return match.group(1).lower() if match else "mp3"


def _douyin_items_from_detail(detail, browser_headers=None):
    """把浏览器得到的作品详情转换成现有下载资源格式。"""
    results = []
    images = detail.get("images") or []
    item_headers = browser_headers or {}

    if images:
        for image in images:
            image_urls = image.get("urlList") or image.get("url_list") or image
            url = _pick_url(image_urls, ("jpeg", "jpg", "png", "webp"))
            if not url:
                continue
            results.append({
                "type": "image",
                "addr": url,
                "ext": _guess_ext(url),
                "referer": "https://www.douyin.com/",
                "headers": item_headers,
            })

        music = detail.get("music") or {}
        music_url = _pick_url(music.get("playUrl") or music.get("play_url"))
        if music_url:
            results.append({
                "type": "bgm_direct",
                "addr": music_url,
                "ext": _guess_audio_ext(music_url),
                "referer": "https://www.douyin.com/",
                "headers": item_headers,
            })
    else:
        video_urls = _douyin_video_urls(detail.get("video") or {})
        if video_urls:
            results.append({
                "type": "video",
                "addr": video_urls[0],
                "addrs": video_urls,
                "ext": "mp4",
                "referer": "https://www.douyin.com/",
                "headers": item_headers,
            })

    if not results:
        raise Exception("Chromium 已取得作品信息，但没有找到可下载的图片或视频")
    return results


def _parse_douyin_video(html):
    match = re.search(
        r'"play_addr"\s*:\s*\{[^}]*"uri"\s*:\s*"([^"]+)"[^}]*"url_list"\s*:\s*\["([^"]+)"',
        html, re.DOTALL
    )
    if not match:
        raise Exception(
            "抖音视频信息获取失败：抖音已升级反爬（页面不再内嵌视频数据，接口需要环境指纹签名），"
            "纯 HTTP 脚本当前无法解析抖音，请改用其他工具或直接使用抖音 App 保存")

    watermarked_url = match.group(2).encode().decode('unicode_escape')
    clean_url = watermarked_url.replace('/playwm/', '/play/')

    return [{
        "type": "video",
        "addr": clean_url,
        "ext": "mp4",
        "referer": "https://www.douyin.com/",
    }]


def _parse_douyin_images(html):
    """解析图文帖，提取原图和 BGM"""
    results = []
    img_urls = []

    # 格式1 (share/video/): origin_url 包裹的原图
    origin_urls = re.findall(
        r'"origin_url"\s*:\s*\{[^}]*"url_list"\s*:\s*\["([^"]+)"',
        html, re.DOTALL
    )
    if origin_urls:
        img_urls = [u.encode().decode('unicode_escape') for u in origin_urls]
    else:
        # 格式2 (share/note/): 解析 "images":[{...}] JSON 数组
        try:
            idx = html.index('"images":[')
            depth = 0
            end_idx = idx
            for i in range(idx, len(html)):
                if html[i] == '[':
                    depth += 1
                elif html[i] == ']':
                    depth -= 1
                    if depth == 0:
                        end_idx = i + 1
                        break
            json_str = html[idx + len('"images":'):end_idx]
            images = json.loads(json_str)
            for img in images:
                if 'url_list' in img and img['url_list']:
                    img_urls.append(img['url_list'][-1])
        except (ValueError, json.JSONDecodeError, KeyError):
            pass

    if not img_urls:
        raise Exception("未找到图片地址，页面结构可能已变更")

    for url in img_urls:
        results.append({
            "type": "image",
            "addr": url,
            "ext": _guess_ext(url),
            "referer": "https://www.douyin.com/",
        })

    # BGM
    match = re.search(
        r'"play_addr"\s*:\s*\{[^}]*"uri"\s*:\s*"([^"]+)"[^}]*"url_list"\s*:\s*\["([^"]+)"',
        html, re.DOTALL
    )
    if match:
        video_id = match.group(1).encode().decode('unicode_escape')
        watermarked_url = match.group(2).encode().decode('unicode_escape')
        if video_id.startswith('http'):
            results.append({
                "type": "bgm_direct",
                "addr": video_id,
                "ext": "mp3",
                "referer": "https://www.douyin.com/",
            })
        else:
            results.append({
                "type": "bgm",
                "addr": watermarked_url,
                "ext": "m4a",
                "referer": "https://www.douyin.com/",
            })

    return results


def _guess_ext(url):
    m = re.search(r'\.(jpe?g|png|webp|gif)(?:\?|$)', url, re.IGNORECASE)
    if not m:
        return 'jpg'
    ext = m.group(1).lower()
    return 'jpg' if ext == 'jpeg' else ext


# ── 小红书 ──────────────────────────────────────────────────────

def _xhs_note_data(html):
    """从 __SETUP_SERVER_STATE__ 提取笔记数据（新版页面结构）"""
    idx = html.find('__SETUP_SERVER_STATE__')
    if idx < 0:
        return None

    start = html.find('{', idx)
    depth = 0
    end = None
    for i in range(start, len(html)):
        if html[i] == '{':
            depth += 1
        elif html[i] == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        return None

    try:
        state = json.loads(html[start:end])
        return state['LAUNCHER_SSR_STORE_PAGE_DATA'].get('noteData')
    except (ValueError, KeyError, json.JSONDecodeError):
        return None


def parse_xhs(url, headers):
    res = _session(headers).get(url, allow_redirects=True, timeout=15)
    html = res.text

    # ── 新版页面：从 __SETUP_SERVER_STATE__ 提取笔记数据 ──
    note = _xhs_note_data(html)

    if note:
        results = []

        # 视频笔记：consumer.originVideoKey → 标准播放地址
        video = note.get('video')
        if video:
            origin_key = None
            try:
                origin_key = video['consumer']['originVideoKey']
            except (KeyError, TypeError):
                pass
            if origin_key:
                results.append({
                    "type": "video",
                    "addr": f"https://sns-video-al.xhscdn.com/{origin_key}",
                    "ext": "mp4",
                    "referer": "https://www.xiaohongshu.com/",
                })

        # 图文笔记：imageList
        for img in note.get('imageList') or []:
            if img.get('url'):
                results.append({
                    "type": "image",
                    "addr": img['url'],
                    "ext": _guess_ext(img['url']),
                    "referer": "https://www.xiaohongshu.com/",
                })

        if results:
            return results

    # ── 旧版页面兜底：masterUrl ──
    match = re.search(r'"masterUrl"\s*:\s*"([^"]+)"', html)
    if match:
        video_url = match.group(1).encode().decode('unicode_escape')
        return [{
            "type": "video",
            "addr": video_url,
            "ext": "mp4",
            "referer": "https://www.xiaohongshu.com/",
        }]

    # 风控验证页：无笔记数据、含 JS 校验脚本
    if 'formula-runtime' in html or len(html) < 30000:
        raise Exception("触发小红书风控验证（请求频繁），请等几分钟再试")

    raise Exception("未找到视频/图片地址，页面结构可能已变更")


# ── 下载 ────────────────────────────────────────────────────────

def _make_filename(item_type, ext, counter):
    prefix = TYPE_PREFIX.get(item_type, "file")
    return f"{prefix}_{counter}.{ext}"


def _download_stream(resp, file_obj, desc):
    """流式写入文件，实时刷新下载进度（0%-100%）"""
    try:
        total = int(resp.headers.get("Content-Length", 0) or 0)
    except (ValueError, TypeError):
        total = 0

    downloaded = 0
    last_pct = -1
    printed = False

    for chunk in resp.iter_content(8192):
        file_obj.write(chunk)
        downloaded += len(chunk)
        if total:
            pct = downloaded * 100 // total
            if pct != last_pct:
                last_pct = pct
                printed = True
                print(f"\r{desc} {pct}% ({downloaded/1048576:.1f}MB/{total/1048576:.1f}MB)", end="", flush=True)
        else:
            # 无 Content-Length，每 256KB 刷新一次字节数
            step = downloaded // 262144
            if step != last_pct:
                last_pct = step
                printed = True
                print(f"\r{desc} {downloaded/1048576:.1f}MB", end="", flush=True)

    if printed:
        print()


def _open_download_response(item, headers):
    """打开资源流；抖音视频的首选 CDN 不通时依次尝试备用地址。"""
    addresses = item.get("addrs") or [item["addr"]]
    last_error = None

    for index, address in enumerate(addresses):
        try:
            resp = requests.get(
                address,
                headers={
                    **headers,
                    **item.get("headers", {}),
                    "Referer": item["referer"],
                },
                verify=False,
                timeout=(20, 120),
                stream=True,
            )
            resp.raise_for_status()
            return resp
        except requests.RequestException as exc:
            last_error = exc
            try:
                resp.close()
            except (NameError, AttributeError):
                pass
            if index + 1 < len(addresses):
                print(f"  下载地址 {index + 1} 不可用，正在尝试备用地址……")

    raise last_error or Exception("没有可用的下载地址")


def download_file(item, filepath, headers):
    """普通下载（video / image / bgm_direct）"""
    os.makedirs(os.path.dirname(filepath), exist_ok=True)

    resp = _open_download_response(item, headers)

    try:
        with open(filepath, "wb") as f:
            _download_stream(resp, f, f"下载中：{os.path.basename(filepath)}")
    finally:
        resp.close()

    return filepath


def download_bgm(item, filepath, headers):
    """下载幻灯片视频，用 ffmpeg 提取音频轨"""
    if not _has_ffmpeg():
        print("  (未找到 ffmpeg，跳过 BGM 提取)")
        return None

    os.makedirs(os.path.dirname(filepath), exist_ok=True)

    resp = _open_download_response(item, headers)

    try:
        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
            _download_stream(resp, tmp, f"提取 BGM：{os.path.basename(filepath)}（下载源视频）")
            tmp_path = tmp.name
    finally:
        resp.close()

    subprocess.run(
        ["ffmpeg", "-y", "-i", tmp_path, "-vn", "-c:a", "copy", filepath],
        capture_output=True,
        check=True,
    )
    os.unlink(tmp_path)
    return filepath


def _has_ffmpeg():
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
        return True
    except (FileNotFoundError, subprocess.CalledProcessError):
        return False


# ── 主入口 ──────────────────────────────────────────────────────

def extract_url(text):
    """从混合文本中提取第一个目标平台的 URL"""
    # 匹配 https:// 或 http:// 开头、包含目标域名的完整 URL
    m = re.search(
        r'https?://[^\s]*?(?:douyin\.com|iesdouyin\.com|xiaohongshu\.com|xhslink\.com|xhslink\.cn)[^\s]*',
        text
    )
    return m.group(0) if m else text


def detect_platform(url):
    if re.search(r'douyin\.com|iesdouyin\.com', url):
        return "douyin"
    if re.search(r'xiaohongshu\.com|xhslink\.com|xhslink\.cn', url):
        return "xhs"
    return None


if __name__ == '__main__':
    headers = get_headers(os.path.join(SCRIPT_DIR, "config.ini"), "android-headers")

    while True:
        raw = input("请输入视频链接（抖音/小红书），输入 end 退出：").strip()
        if not raw:
            continue
        if raw.lower() == "end":
            print("已退出")
            break

        url = extract_url(raw)
        if url != raw:
            print(f"提取到链接：{url}")

        platform = detect_platform(url)
        if not platform:
            print("未识别的链接，目前支持抖音和小红书\n")
            continue

        try:
            if platform == "douyin":
                items = parse_douyin(url, headers)
            else:
                items = parse_xhs(url, headers)

            # ── 按类型分组展示，让用户选择 ──
            TYPE_LABELS = {"video": "视频", "image": "图片", "bgm": "BGM(音频)", "bgm_direct": "BGM(音频)"}
            DISPLAY_ORDER = ["video", "image", "bgm_direct", "bgm"]

            # 统计各类型数量
            type_count = {}
            for item in items:
                tp = item["type"]
                type_count[tp] = type_count.get(tp, 0) + 1

            # 展示
            print("\n可下载的资源：")
            available_types = []
            idx_map = {}
            idx = 1
            for tp in DISPLAY_ORDER:
                if tp in type_count:
                    label = TYPE_LABELS.get(tp, tp)
                    print(f"  [{idx}] {label} ×{type_count[tp]}")
                    idx_map[str(idx)] = tp
                    available_types.append(tp)
                    idx += 1

            print(f"  [a] 全部下载")
            print(f"  [q] 取消")
            choice = input("请选择要下载的资源（多个用空格分隔，如 1 2）：").strip().lower()

            if choice == "q":
                print("已取消\n")
                continue

            if choice == "a":
                selected_types = set(available_types)
            else:
                selected_types = set()
                for c in choice.split():
                    if c in idx_map:
                        selected_types.add(idx_map[c])

            if not selected_types:
                print("未选择任何资源\n")
                continue

            print()

            # ── 下载 ──
            folder = os.path.join(SCRIPT_DIR, "download", datetime.now().strftime("%Y-%m-%d_%H-%M-%S"))
            counters = {}

            for item in items:
                tp = item["type"]
                if tp not in selected_types:
                    continue

                counters[tp] = counters.get(tp, 0) + 1
                filename = _make_filename(tp, item["ext"], counters[tp])
                filepath = os.path.join(folder, filename)

                if tp == "bgm":
                    path = download_bgm(item, filepath, headers)
                else:
                    path = download_file(item, filepath, headers)

                if path:
                    print(f"  -> {path}")

            # 同步到 Android 目录
            target = os.path.join("/mnt/Android/douyin_download", os.path.basename(folder))
            shutil.copytree(folder, target, dirs_exist_ok=True)
            print(f"已同步到 {target}\n")
        except Exception as e:
            print(f"下载失败：{e}\n")
