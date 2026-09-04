#!/usr/bin/python3
# -*- coding: UTF-8 -*-

"""小红书公开笔记页面解析与原始媒体候选归一化。"""

import html
import json
import re
from urllib.parse import parse_qs, unquote, urljoin, urlparse

import requests


XHS_HOME_URL = "https://www.xiaohongshu.com/"
XHS_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
)
XHS_URL_PATTERN = re.compile(
    r"https?://(?:[a-z0-9-]+\.)*(?:xiaohongshu\.com|xhslink\.(?:cn|com))"
    r"[^\s，。；：！？）】》]*",
    re.I,
)
NOTE_ID_PATTERN = re.compile(r"/(?:explore|discovery/item|note)/([0-9a-zA-Z_-]+)", re.I)
IMAGE_CDNS = (
    "https://sns-img-bd.xhscdn.com",
    "https://sns-img-hw.xhscdn.com",
    "https://sns-img-qc.xhscdn.com",
    "https://sns-img-qn.xhscdn.com",
)


class XiaohongshuParseError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def is_xiaohongshu_share(value):
    return XHS_URL_PATTERN.search(value or "") is not None


def _cookie_dict(cookie_header):
    cookies = {}
    for pair in (cookie_header or "").split(";"):
        name, separator, value = pair.strip().partition("=")
        if separator and name and value:
            cookies[name] = value
    return cookies


def _request_headers():
    return {
        "User-Agent": XHS_USER_AGENT,
        "Referer": XHS_HOME_URL,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }


def _extract_share_url(share_text):
    match = XHS_URL_PATTERN.search(share_text or "")
    if not match:
        raise XiaohongshuParseError("UNSUPPORTED_URL", "没有找到小红书链接")
    return match.group(0).rstrip(".,;:!?)]}。，；：！？）》】")


def _redirect_target(url):
    parsed = urlparse(url)
    redirect_path = parse_qs(parsed.query).get("redirectPath", [""])[0]
    target = urljoin(url, unquote(redirect_path)) if redirect_path else ""
    return target if _is_xiaohongshu_page(target) else ""


def _is_xiaohongshu_page(url):
    host = (urlparse(url or "").hostname or "").lower()
    return host == "xiaohongshu.com" or host.endswith(".xiaohongshu.com")


def _note_id_from_url(url):
    match = NOTE_ID_PATTERN.search(url or "")
    return match.group(1) if match else ""


def _extract_balanced_object(text, start):
    depth = 0
    quote = ""
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    return ""


def _replace_undefined(text):
    result = []
    index = 0
    quote = ""
    escaped = False
    while index < len(text):
        char = text[index]
        if quote:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            index += 1
            continue
        if char in ('"', "'"):
            quote = char
            result.append(char)
            index += 1
            continue
        if text.startswith("undefined", index):
            before = text[index - 1] if index else ""
            after_index = index + len("undefined")
            after = text[after_index] if after_index < len(text) else ""
            if not (before.isalnum() or before in "_$" or after.isalnum() or after in "_$"):
                result.append("null")
                index = after_index
                continue
        result.append(char)
        index += 1
    return "".join(result)


def extract_initial_state(page_html):
    text = html.unescape(page_html or "")
    for marker in ("window.__INITIAL_STATE__", "__INITIAL_STATE__"):
        search_from = 0
        while True:
            marker_index = text.find(marker, search_from)
            if marker_index < 0:
                break
            object_start = text.find("{", marker_index + len(marker))
            if object_start < 0:
                break
            payload = _extract_balanced_object(text, object_start)
            if payload:
                try:
                    return json.loads(_replace_undefined(payload))
                except (json.JSONDecodeError, ValueError):
                    pass
            search_from = marker_index + len(marker)
    return None


def _unwrap(value, unwrap_note=True):
    current = value
    for _ in range(4):
        if not isinstance(current, dict):
            break
        if unwrap_note and isinstance(current.get("note"), dict):
            current = current["note"]
        elif isinstance(current.get("_value"), dict):
            current = current["_value"]
        elif isinstance(current.get("value"), dict) and len(current) <= 3:
            current = current["value"]
        else:
            break
    return current


def _value(root, *names):
    if not isinstance(root, dict):
        return None
    for name in names:
        if name in root and root[name] is not None:
            return root[name]
    return None


def _note_id(note):
    return str(_value(note, "noteId", "note_id", "id", "note_id_str") or "")


def find_target_note(state, target_note_id):
    if not isinstance(state, dict) or not target_note_id:
        return None
    state_root = _unwrap(state, unwrap_note=False)
    note_root = _unwrap(state_root.get("note") or {}, unwrap_note=False)
    if not isinstance(note_root, dict):
        return None
    note_map = _value(note_root, "noteDetailMap", "note_detail_map")
    note_map = _unwrap(note_map, unwrap_note=False)
    if isinstance(note_map, dict):
        direct = _unwrap(note_map.get(target_note_id))
        if isinstance(direct, dict):
            embedded_id = _note_id(direct)
            if not embedded_id or embedded_id == target_note_id:
                return direct
        for entry in note_map.values():
            candidate = _unwrap(entry)
            if isinstance(candidate, dict) and _note_id(candidate) == target_note_id:
                return candidate
    candidate = _unwrap(_value(note_root, "note", "noteDetail", "note_detail"))
    if isinstance(candidate, dict) and _note_id(candidate) == target_note_id:
        return candidate
    return None


def _as_urls(value):
    if isinstance(value, str):
        return [value] if value.startswith(("http://", "https://")) else []
    if isinstance(value, list):
        result = []
        for child in value:
            result.extend(_as_urls(child))
        return result
    if isinstance(value, dict):
        result = []
        for key in (
            "url", "masterUrl", "master_url", "downloadUrl", "download_url",
            "backupUrls", "backup_urls", "urlList", "url_list",
        ):
            result.extend(_as_urls(value.get(key)))
        return result
    return []


def _original_object_path(url):
    parsed = urlparse(url or "")
    if not parsed.hostname or not parsed.hostname.lower().endswith("xhscdn.com"):
        return ""
    path = unquote(parsed.path).lstrip("/").split("!", 1)[0]
    if not path:
        return ""
    for marker in ("spectrum/", "notes_pre_post/"):
        marker_index = path.find(marker)
        if marker_index >= 0:
            return path[marker_index:]
    return path.rsplit("/", 1)[-1]


def image_candidates(image):
    if isinstance(image, str):
        image = {"url": image}
    if not isinstance(image, dict):
        return []
    explicit_originals = []
    defaults = []
    previews = []
    for key in ("original", "originalUrl", "original_url", "urlOriginal", "url_original"):
        explicit_originals.extend(_as_urls(image.get(key)))
    for key in ("urlDefault", "url_default", "url", "downloadUrl", "download_url"):
        defaults.extend(_as_urls(image.get(key)))
    for key in ("urlPre", "url_pre", "preview", "urlList", "url_list", "infoList"):
        previews.extend(_as_urls(image.get(key)))
    source_urls = explicit_originals + defaults + previews
    restored = []
    for source_url in source_urls:
        object_path = _original_object_path(source_url)
        if object_path:
            restored.extend(f"{cdn}/{object_path}" for cdn in IMAGE_CDNS)
    return list(dict.fromkeys(explicit_originals + restored + defaults + previews))


def extract_image_candidates(note):
    images = _value(note, "imageList", "image_list", "imagesList", "images_list") or []
    if not isinstance(images, list):
        return []
    return [candidates for candidates in map(image_candidates, images) if candidates]


def _number(value):
    try:
        return int(float(value or 0))
    except (TypeError, ValueError):
        return 0


def _is_direct_video_url(url):
    parsed = urlparse(url or "")
    host = (parsed.hostname or "").lower()
    text = (url or "").lower()
    is_segmented_stream = re.search(
        r"m3u8|/hls(?:[/?#]|$)|[?&](?:format|type|protocol)=hls(?:[&#]|$)|"
        r"\.(?:ts|m2ts)(?:[?#]|$)",
        text,
    )
    return (
        host.endswith("xhscdn.com")
        and is_segmented_stream is None
        and ("sns-video" in host or ".mp4" in parsed.path.lower())
    )


def _video_url(value):
    return next((url for url in _as_urls(value) if _is_direct_video_url(url)), "")


def _stream_items(stream, codec):
    if not isinstance(stream, list):
        return []
    result = []
    for item in stream:
        if not isinstance(item, dict):
            continue
        url = _video_url(item)
        if not url:
            continue
        result.append({
            "url": url,
            "urls": list(dict.fromkeys(url for url in _as_urls(item) if _is_direct_video_url(url))),
            "width": _number(_value(item, "width", "videoWidth", "video_width")),
            "height": _number(_value(item, "height", "videoHeight", "video_height")),
            "fps": _number(_value(item, "fps", "frameRate", "frame_rate")),
            "bitrate": _number(_value(item, "avgBitrate", "avg_bitrate", "bitrate", "videoBitrate")),
            "size": _number(_value(item, "size", "fileSize", "file_size")),
            "codec": codec.upper().replace("H264", "H.264").replace("H265", "H.265"),
            "source": "stream",
        })
    return result


def _recursive_video_urls(value, depth=0):
    if depth > 10:
        return []
    if isinstance(value, str):
        return [value] if _is_direct_video_url(value) else []
    if isinstance(value, list):
        result = []
        for child in value:
            result.extend(_recursive_video_urls(child, depth + 1))
        return result
    if isinstance(value, dict):
        result = []
        for child in value.values():
            result.extend(_recursive_video_urls(child, depth + 1))
        return result
    return []


def extract_video_variants(note):
    video = _value(note, "video", "videoInfo", "video_info") or {}
    media = _value(video, "media") or {}
    stream_root = _value(media, "stream") or _value(video, "stream") or {}
    variants = []
    if isinstance(stream_root, dict):
        for codec_key, streams in stream_root.items():
            variants.extend(_stream_items(streams, str(codec_key)))

    consumer = _value(video, "consumer") or {}
    origin_key = _value(
        consumer,
        "originVideoKey",
        "origin_video_key",
    ) or _value(video, "originVideoKey", "origin_video_key")
    if isinstance(origin_key, str) and origin_key:
        origin_url = origin_key if origin_key.startswith("http") else (
            "https://sns-video-bd.xhscdn.com/" + origin_key.lstrip("/")
        )
        if _is_direct_video_url(origin_url):
            variants.append({
                "url": origin_url,
                "urls": [origin_url],
                "width": 0,
                "height": 0,
                "fps": 0,
                "bitrate": 0,
                "size": 0,
                "codec": "未知",
                "source": "origin",
            })

    if not variants:
        for url in dict.fromkeys(_recursive_video_urls(video)):
            variants.append({
                "url": url,
                "urls": [url],
                "width": 0,
                "height": 0,
                "fps": 0,
                "bitrate": 0,
                "size": 0,
                "codec": "未知",
                "source": "fallback",
            })

    unique = {}
    for variant in variants:
        if variant["url"] not in unique:
            unique[variant["url"]] = variant
        elif len(variant["urls"]) > len(unique[variant["url"]]["urls"]):
            unique[variant["url"]]["urls"] = variant["urls"]

    def score(item):
        return (
            item["width"] * item["height"],
            item["fps"],
            item["bitrate"],
            item["size"],
            item["codec"] == "H.264",
            item["source"] == "stream",
        )

    return sorted(unique.values(), key=score, reverse=True)


def _shape(value, depth=0):
    if depth >= 6:
        return type(value).__name__
    if isinstance(value, dict):
        return {key: _shape(child, depth + 1) for key, child in value.items()}
    if isinstance(value, list):
        return [_shape(value[0], depth + 1)] if value else []
    return type(value).__name__


def normalize_note(note, note_id, canonical_url):
    user = _value(note, "user", "author") or {}
    images = extract_image_candidates(note)
    variants = extract_video_variants(note)
    note_type = str(_value(note, "type", "noteType", "note_type") or "").lower()
    is_video = note_type == "video" or bool(variants)
    if not is_video and not images:
        raise XiaohongshuParseError("MEDIA_EMPTY", "笔记中没有找到可下载的图片或视频")
    if is_video and not variants:
        raise XiaohongshuParseError(
            "MEDIA_EMPTY",
            "当前笔记没有可直接下载的视频档位，可能只提供了 HLS 流",
        )

    cover = ""
    if images:
        cover = images[0][0]
    else:
        cover_data = _value(note, "cover") or {}
        cover = next(iter(image_candidates(cover_data)), "")

    return {
        "ok": True,
        "platform": "xiaohongshu",
        "content_id": note_id,
        "canonical_url": canonical_url,
        "referer": XHS_HOME_URL,
        "kind": "video" if is_video else "image",
        "author": str(_value(user, "nickname", "nickName", "nick_name", "name") or ""),
        "description": str(_value(note, "title", "displayTitle", "display_title") or
                           _value(note, "desc", "description") or ""),
        "cover_url": cover,
        "variants": [{
            "width": item["width"],
            "height": item["height"],
            "bitrate": item["bitrate"],
            "fps": item["fps"],
            "codec": item["codec"],
            "size": item["size"],
            "size_source": "api" if item["size"] > 0 else "unknown",
            "urls": item["urls"],
        } for item in variants],
        "audio_urls": [],
        "image_urls": [item[0] for item in images],
        "image_candidates": images,
        "music_urls": [],
        "response_shape": _shape(note),
    }


def parse_xiaohongshu_share(share_text, cookie_header="", request_get=requests.get):
    try:
        source_url = _extract_share_url(share_text)
        response = request_get(
            source_url,
            headers=_request_headers(),
            cookies=_cookie_dict(cookie_header),
            allow_redirects=True,
            timeout=25,
        )
        response.raise_for_status()
        canonical_url = response.url
        redirect_url = _redirect_target(canonical_url)
        if redirect_url:
            response = request_get(
                redirect_url,
                headers=_request_headers(),
                cookies=_cookie_dict(cookie_header),
                allow_redirects=True,
                timeout=25,
            )
            response.raise_for_status()
            canonical_url = response.url
        note_id = _note_id_from_url(canonical_url) or _note_id_from_url(source_url)
        if not note_id:
            raise XiaohongshuParseError(
                "URL_RESOLVE_FAILED",
                "短链接已打开，但没有识别到小红书笔记 ID，请重新复制最新分享链接",
            )
        body_text = response.text or ""
        unavailable = re.search(
            r"该笔记已被删除|笔记不存在|暂时无法浏览|无法浏览|无法查看|违规|私密",
            body_text,
        )
        if unavailable:
            raise XiaohongshuParseError("CONTENT_UNAVAILABLE", f"小红书笔记{unavailable.group(0)}")
        if "/login" in canonical_url or "登录后查看" in body_text:
            raise XiaohongshuParseError("LOGIN_REQUIRED", "需要登录或刷新小红书解析环境")
        state = extract_initial_state(body_text)
        if not state:
            raise XiaohongshuParseError("DETAIL_EMPTY", "页面没有返回小红书笔记状态")
        note = find_target_note(state, note_id)
        if not note:
            raise XiaohongshuParseError("DETAIL_EMPTY", "页面状态中没有匹配目标笔记")
        return normalize_note(note, note_id, canonical_url)
    except XiaohongshuParseError as error:
        return {
            "ok": False,
            "platform": "xiaohongshu",
            "error_code": error.code,
            "message": str(error),
        }
    except requests.HTTPError as error:
        status = error.response.status_code if error.response is not None else 0
        code = "AUTH_OR_RISK" if status in (401, 403, 429) else "HTTP_ERROR"
        return {
            "ok": False,
            "platform": "xiaohongshu",
            "error_code": code,
            "message": f"小红书详情请求失败（HTTP {status}）",
        }
    except requests.RequestException as error:
        return {
            "ok": False,
            "platform": "xiaohongshu",
            "error_code": "NETWORK",
            "message": f"网络请求失败：{type(error).__name__}",
        }
    except Exception as error:
        return {
            "ok": False,
            "platform": "xiaohongshu",
            "error_code": "PARSE_FAILED",
            "message": str(error),
        }
