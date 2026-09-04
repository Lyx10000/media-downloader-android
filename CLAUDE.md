# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A local-only Android downloader for original-quality Douyin and Xiaohongshu media.

## Commands

Run `cd android && ./gradlew testDebugUnitTest assembleDebug` for local verification.

## Architecture

The app is Kotlin-only. `ParserGateway` routes to platform parsers; the Douyin parser signs the Web API locally, and the Xiaohongshu parser extracts the requested note from page initial state. Downloads run through WorkManager, task metadata is stored in Room, and media track operations use Android MediaExtractor/MediaMuxer without transcoding.

Do not reintroduce Python, Chaquopy, a remote parser service, or destructive recursive file operations.
