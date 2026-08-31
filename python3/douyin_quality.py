#!/usr/bin/python3
# -*- coding: UTF-8 -*-

"""抖音详情 API 与媒体质量档位处理。"""

import os
import random
import re
import string
import urllib.parse

import requests


DOUYIN_API_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
)


def _cookie_pairs(cookie_text):
    for pair in (cookie_text or "").split(";"):
        key, separator, value = pair.strip().partition("=")
        if separator and key and value:
            yield key, value


def _api_params(item_id):
    token_chars = string.ascii_letters + string.digits + "-_"
    return {
        "device_platform": "webapp",
        "aid": "6383",
        "channel": "channel_pc_web",
        "pc_client_type": 1,
        "publish_video_strategy_type": 2,
        "pc_libra_divert": "Windows",
        "version_code": "290100",
        "version_name": "29.1.0",
        "cookie_enabled": "true",
        "screen_width": 1920,
        "screen_height": 1080,
        "browser_language": "zh-CN",
        "browser_platform": "Win32",
        "browser_name": "Edge",
        "browser_version": "130.0.0.0",
        "browser_online": "true",
        "engine_name": "Blink",
        "engine_version": "130.0.0.0",
        "os_name": "Windows",
        "os_version": "10",
        "cpu_core_num": 12,
        "device_memory": 8,
        "platform": "PC",
        "downlink": 10,
        "effective_type": "4g",
        "round_trip_time": 100,
        "msToken": "".join(random.choice(token_chars) for _ in range(184)),
        "aweme_id": str(item_id),
    }


def fetch_douyin_detail(item_id, browser_cookies=(), timeout=20):
    """使用浏览器安全 Cookie 和 a_bogus 获取完整作品详情。"""
    from abogus import ABogus, BrowserFingerprintGenerator

    session = requests.Session()
    session.verify = False
    session.headers.update({
        "User-Agent": DOUYIN_API_USER_AGENT,
        "Referer": f"https://www.douyin.com/video/{item_id}",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "sec-ch-ua": (
            '"Chromium";v="130", "Microsoft Edge";v="130", '
            '"Not?A_Brand";v="99"'
        ),
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
    })

    for cookie in browser_cookies or ():
        name = cookie.get("name")
        value = cookie.get("value")
        if name and value:
            session.cookies.set(
                name,
                value,
                domain=cookie.get("domain") or ".douyin.com",
                path=cookie.get("path") or "/",
            )

    # 显式 Cookie 可补充浏览器 profile，适合无头环境预先配置登录态。
    for name, value in _cookie_pairs(os.environ.get("DOUYIN_COOKIE")):
        session.cookies.set(name, value, domain=".douyin.com", path="/")

    params_text = urllib.parse.urlencode(_api_params(item_id))
    fingerprint = BrowserFingerprintGenerator.generate_fingerprint("Edge")
    signature = ABogus(
        fp=fingerprint,
        user_agent=DOUYIN_API_USER_AGENT,
    ).generate_abogus(params_text)[1]
    endpoint = (
        "https://www.douyin.com/aweme/v1/web/aweme/detail/?"
        f"{params_text}&a_bogus={urllib.parse.quote(signature, safe='')}"
    )

    response = session.get(endpoint, timeout=timeout)
    response.raise_for_status()
    if not response.content:
        return None
    data = response.json()
    detail = data.get("aweme_detail")
    if data.get("status_code") == 0 and isinstance(detail, dict):
        return detail
    return None


def _address_urls(value):
    urls = []
    if isinstance(value, str):
        if value.startswith("http"):
            urls.append(value)
    elif isinstance(value, list):
        for child in value:
            urls.extend(_address_urls(child))
    elif isinstance(value, dict):
        for key in (
            "url_list", "urlList", "main_url", "backup_url", "fallback_url",
            "src", "url",
        ):
            if key in value:
                urls.extend(_address_urls(value[key]))
    return list(dict.fromkeys(urls))


def _number(value):
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _codec_name(rate):
    codec = str(rate.get("codec_type") or rate.get("codecType") or "").lower()
    if rate.get("is_h265") or rate.get("isH265") or codec in {"h265", "hevc", "bytevc1"}:
        return "H.265"
    return "H.264"


def extract_audio_urls(video):
    """从 DASH 音频档位中选择最高码率，并保留备用 CDN。"""
    audio_rates = video.get("bit_rate_audio") or video.get("bitRateAudio") or []
    choices = []
    for rate in audio_rates:
        meta = rate.get("audio_meta") or rate.get("audioMeta") or rate
        bitrate = _number(meta.get("bitrate") or rate.get("bit_rate"))
        urls = _address_urls(meta.get("url_list") or meta.get("urlList") or meta)
        if urls:
            choices.append((bitrate, urls))
    if not choices:
        return []
    return max(choices, key=lambda item: item[0])[1]


def extract_video_variants(video):
    """提取并按分辨率、帧率、码率排序视频档位。"""
    rates = video.get("bit_rate") or video.get("bitRateList") or []
    grouped = {}

    for rate in rates:
        play = rate.get("play_addr") or rate.get("playAddr") or {}
        urls = [
            url for url in _address_urls(play)
            if "media-audio" not in url and "ies-music" not in url
        ]
        if not urls:
            continue
        width = _number(play.get("width") or rate.get("width") or video.get("width"))
        height = _number(play.get("height") or rate.get("height") or video.get("height"))
        bitrate = _number(rate.get("bit_rate") or rate.get("bitRate"))
        fps = _number(rate.get("FPS") or rate.get("fps"))
        codec = _codec_name(rate)
        variant = {
            "width": width,
            "height": height,
            "bitrate": bitrate,
            "fps": fps,
            "codec": codec,
            "size": _number(play.get("data_size") or play.get("dataSize")),
            "gear": rate.get("gear_name") or rate.get("gearName") or "",
            "addrs": urls,
            "confirmed": bool(width and height),
        }
        key = (width, height, fps, codec)
        previous = grouped.get(key)
        if previous is None or variant["bitrate"] > previous["bitrate"]:
            grouped[key] = variant

    variants = list(grouped.values())
    if not variants:
        fallback = [
            url for url in _address_urls(video.get("play_addr") or video.get("playAddr"))
            if "media-audio" not in url and "ies-music" not in url
        ]
        if fallback:
            variants.append({
                "width": _number(video.get("width")),
                "height": _number(video.get("height")),
                "bitrate": 0,
                "fps": 0,
                "codec": "未知",
                "size": 0,
                "gear": "player",
                "addrs": fallback,
                "confirmed": False,
            })

    return sorted(
        variants,
        key=lambda item: (
            item["width"] * item["height"],
            item["fps"],
            item["bitrate"],
            item["codec"] == "H.265",
        ),
        reverse=True,
    )


def _image_quality(url):
    match = re.search(r"(?:[:_-]|%3A)q(\d+)(?:[._:?&]|%2F|$)", url, re.I)
    return _number(match.group(1)) if match else 0


def extract_image_urls(image):
    """优先原图字段，再按质量参数选择无水印 CDN 地址。"""
    candidates = []
    for source_rank, key in enumerate(("origin_url", "originUrl", "url_list", "urlList")):
        for position, url in enumerate(_address_urls(image.get(key))):
            candidates.append({
                "url": url,
                "source_rank": 2 if key in {"origin_url", "originUrl"} else 1,
                "quality": _image_quality(url),
                "position": position,
            })
    if not candidates:
        return []
    ordered = sorted(
        candidates,
        key=lambda item: (item["source_rank"], item["quality"], item["position"]),
        reverse=True,
    )
    return list(dict.fromkeys(item["url"] for item in ordered))


def format_video_variant(variant):
    resolution = (
        f"{variant['width']}×{variant['height']}"
        if variant.get("width") and variant.get("height") else "分辨率未知"
    )
    parts = [resolution]
    if variant.get("bitrate"):
        parts.append(f"{variant['bitrate'] / 1_000_000:.2f} Mbps")
    if variant.get("fps"):
        parts.append(f"{variant['fps']} fps")
    if variant.get("codec"):
        parts.append(variant["codec"])
    if variant.get("size"):
        parts.append(f"约 {variant['size'] / 1048576:.1f} MB")
    return " / ".join(parts)


def choose_video_variant(item, input_fn=input, output_fn=print):
    """交互选择档位；直接回车默认最高档。"""
    variants = item.get("variants") or []
    if not variants:
        return item
    if len(variants) == 1 and not variants[0].get("confirmed"):
        output_fn("\n当前只取得播放器流，无法确认它是最高画质。")
        return item

    output_fn("\n可用视频清晰度（默认选择最高档）：")
    for index, variant in enumerate(variants, 1):
        suffix = "（最高）" if index == 1 else ""
        output_fn(f"  [{index}] {format_video_variant(variant)}{suffix}")

    while True:
        choice = input_fn("请选择清晰度，直接回车使用最高档，q 取消视频：").strip().lower()
        if not choice:
            choice = "1"
        if choice == "q":
            return None
        if choice.isdigit() and 1 <= int(choice) <= len(variants):
            selected = variants[int(choice) - 1]
            result = dict(item)
            result["addr"] = selected["addrs"][0]
            result["addrs"] = selected["addrs"]
            result["selected_variant"] = selected
            return result
        output_fn("输入无效，请输入列表中的编号。")


def choose_video_download_mode(item, input_fn=input, output_fn=print):
    """选择视频、音频分轨和无损合成的保存方式。"""
    if item.get("audio_addrs"):
        prompt = "\n请选择视频下载方式（默认合成并保留分轨）："
        modes = {
            "1": ("merge_keep", "视频 + 音频 + 合成，并保留三个文件"),
            "2": ("tracks", "视频 + 音频分轨，不合成"),
            "3": ("video_only", "仅视频轨"),
            "4": ("audio_only", "仅音频轨"),
        }
    else:
        output_fn("\n当前档位是音视频合一文件，将用 ffmpeg 无损拆轨。")
        prompt = "\n请选择视频下载方式（默认保留原始文件和拆分轨）："
        modes = {
            "1": ("merge_keep", "原始音视频 + 视频分轨 + 音频分轨，保留三个文件"),
            "2": ("tracks", "仅保存拆分后的视频轨 + 音频轨"),
            "3": ("video_only", "仅保存拆分后的视频轨"),
            "4": ("audio_only", "仅保存拆分后的音频轨"),
        }
    output_fn(prompt)
    for key, (_, label) in modes.items():
        suffix = "（默认）" if key == "1" else ""
        output_fn(f"  [{key}] {label}{suffix}")

    while True:
        choice = input_fn("请选择下载方式，直接回车使用默认方式：").strip()
        if not choice:
            choice = "1"
        if choice in modes:
            result = dict(item)
            result["download_mode"] = modes[choice][0]
            return result
        output_fn("输入无效，请输入 1、2、3 或 4。")
