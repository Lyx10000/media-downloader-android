#!/usr/bin/python3
# -*- coding: UTF-8 -*-

"""Android/Chaquopy 调用的纯 Python 抖音解析桥。"""

import json
import re
from urllib.parse import urlparse

import requests

from douyin_quality import (
    DOUYIN_API_USER_AGENT,
    _address_urls,
    extract_audio_urls,
    extract_image_urls,
    extract_video_variants,
    fetch_douyin_detail,
)


URL_PATTERN = re.compile(
    r"https?://[^\s]+?(?:douyin\.com|iesdouyin\.com)[^\s]*",
    re.I,
)
ID_PATTERN = re.compile(r"/(?:share/)?(?:video|note)/(\d{15,22})")


def _cookie_list(cookie_header):
    result = []
    for pair in (cookie_header or "").split(";"):
        name, separator, value = pair.strip().partition("=")
        if separator and name and value:
            result.append({
                "name": name,
                "value": value,
                "domain": ".douyin.com",
                "path": "/",
            })
    return result


def _first_url(value):
    urls = _address_urls(value)
    return urls[0] if urls else ""


def _shape(value, depth=0):
    if depth >= 6:
        return type(value).__name__
    if isinstance(value, dict):
        return {key: _shape(child, depth + 1) for key, child in value.items()}
    if isinstance(value, list):
        return [_shape(value[0], depth + 1)] if value else []
    return type(value).__name__


def _resolve_item(share_text):
    match = URL_PATTERN.search(share_text or "")
    url = match.group(0) if match else (share_text or "").strip()
    if not url.startswith(("http://", "https://")):
        raise ValueError("没有找到抖音链接")

    response = requests.get(
        url,
        headers={"User-Agent": DOUYIN_API_USER_AGENT},
        allow_redirects=True,
        timeout=20,
    )
    candidates = [url, response.url]
    for hop in response.history:
        candidates.append(hop.url)
        location = hop.headers.get("Location")
        if location:
            candidates.append(location)
    for candidate in candidates:
        item_match = ID_PATTERN.search(candidate or "")
        if item_match:
            kind = "note" if "/note/" in candidate else "video"
            return item_match.group(1), kind, url
    raise ValueError("短链已打开，但没有识别到作品 ID")


def _normalise(detail, item_id, item_kind):
    author = detail.get("author") or {}
    result = {
        "ok": True,
        "aweme_id": str(detail.get("aweme_id") or detail.get("awemeId") or item_id),
        "kind": "image" if detail.get("images") else item_kind,
        "author": author.get("nickname") or author.get("name") or "",
        "description": detail.get("desc") or detail.get("description") or "",
        "cover_url": "",
        "variants": [],
        "audio_urls": [],
        "image_urls": [],
        "music_urls": [],
        "response_shape": _shape(detail),
    }

    images = detail.get("images") or []
    if images:
        for image in images:
            urls = extract_image_urls(image)
            if urls:
                result["image_urls"].append(urls[0])
        if result["image_urls"]:
            result["cover_url"] = result["image_urls"][0]
    else:
        video = detail.get("video") or {}
        result["variants"] = [
            {
                "width": variant.get("width", 0),
                "height": variant.get("height", 0),
                "bitrate": variant.get("bitrate", 0),
                "fps": variant.get("fps", 0),
                "codec": variant.get("codec", ""),
                "size": variant.get("size", 0),
                "urls": variant.get("addrs", []),
            }
            for variant in extract_video_variants(video)
        ]
        result["audio_urls"] = extract_audio_urls(video)
        result["cover_url"] = _first_url(
            video.get("cover") or video.get("origin_cover") or video.get("originCover")
        )

    music = detail.get("music") or {}
    music_urls = _address_urls(
        music.get("play_url") or music.get("playUrl") or music.get("play_addr") or {}
    )
    result["music_urls"] = music_urls
    return result


def parse_share(share_text, cookie_header=""):
    """返回稳定 JSON；错误也编码为 JSON，避免跨语言丢失阶段信息。"""
    try:
        item_id, item_kind, _ = _resolve_item(share_text)
        detail = fetch_douyin_detail(item_id, _cookie_list(cookie_header), timeout=25)
        if not detail:
            return json.dumps({
                "ok": False,
                "error_code": "DETAIL_EMPTY",
                "message": "抖音详情接口没有返回作品信息，请刷新解析环境",
            }, ensure_ascii=False)
        return json.dumps(_normalise(detail, item_id, item_kind), ensure_ascii=False)
    except requests.HTTPError as error:
        status = error.response.status_code if error.response is not None else 0
        code = "AUTH_OR_RISK" if status in (401, 403) else "HTTP_ERROR"
        return json.dumps({
            "ok": False,
            "error_code": code,
            "message": f"详情请求失败（HTTP {status}）",
        }, ensure_ascii=False)
    except requests.RequestException as error:
        return json.dumps({
            "ok": False,
            "error_code": "NETWORK",
            "message": f"网络请求失败：{type(error).__name__}",
        }, ensure_ascii=False)
    except Exception as error:
        return json.dumps({
            "ok": False,
            "error_code": "PARSE_FAILED",
            "message": str(error),
        }, ensure_ascii=False)
