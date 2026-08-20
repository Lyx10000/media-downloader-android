# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A Douyin (抖音 / Chinese TikTok) video downloader that strips watermarks. Single Python 3 CLI script.

## Commands

```bash
cd python3 && venv/bin/pip install requests && venv/bin/python douyin_downloader.py
```

No test suite, linter, or formatter is configured.

## Architecture

**Core watermark-removal technique (小红书视频):** Fetch the share page (`xiaohongshu.com` note page) → extract note data from the `__SETUP_SERVER_STATE__` embedded JSON → for videos use `video.consumer.originVideoKey` (builds the watermark-free CDN URL), for image posts extract `imageList` URLs (the `!h5_1080jpg` scene is the only one accessible anonymously; the original is 403-protected and carries no watermark).

**抖音 status (as of 2026-08):** Plain HTTP no longer returns work data. `parse_douyin` keeps the legacy HTML fast path, then falls back to Selenium + the Debian system Chromium. Chromium executes Douyin's current frontend/signing code, and the parser extracts the work detail from React Server Components (`self.__pace_f`). A persistent browser profile is kept at `python3/.douyin-browser-profile/`. Termux/PRoot requires `--no-sandbox`.

**Key files:**
- `douyin_downloader.py` — Single-file CLI. For XHS: extracts note data from `__SETUP_SERVER_STATE__` in the share page HTML. Interactive loop via `input()`, resource-type selection (video/image/BGM), downloads to `download/`, syncs to `/mnt/Android/douyin_download`. Real-time 0-100% progress via `\r`.
- `config.ini` — Two User-Agent strings (`[headers]` for desktop, `[android-headers]` for mobile).
- `run` — Launcher script: `cd python3 && exec venv/bin/python douyin_downloader.py`.

XHS may serve a JS challenge page (`formula-runtime`) when rate-limited; the parser detects it and asks the user to retry later.
