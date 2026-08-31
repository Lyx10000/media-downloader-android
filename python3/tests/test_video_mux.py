import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


TEST_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON_DIR = os.path.dirname(TEST_DIR)
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)

import douyin_downloader  # noqa: E402


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "需要 ffmpeg")
class VideoMuxTests(unittest.TestCase):
    def _make_muxed_source(self, filepath):
        subprocess.run(
            [
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                "color=c=black:s=64x64:d=0.2", "-f", "lavfi", "-i",
                "sine=frequency=1000:duration=0.2", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", filepath,
            ],
            check=True,
        )

    def _stream_types(self, filepath):
        probe = subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", filepath,
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        return set(probe.stdout.split())

    def test_dash_video_and_audio_are_stream_copied_into_one_mp4(self):
        with tempfile.TemporaryDirectory() as folder:
            video_source = os.path.join(folder, "source-video.mp4")
            audio_source = os.path.join(folder, "source-audio.m4a")
            output = os.path.join(folder, "result.mp4")

            subprocess.run(
                [
                    "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "color=c=black:s=64x64:d=0.2", "-an", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", video_source,
                ],
                check=True,
            )
            subprocess.run(
                [
                    "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "sine=frequency=1000:duration=0.2", "-vn", "-c:a", "aac",
                    audio_source,
                ],
                check=True,
            )

            def fake_download(item, filepath, _headers):
                source = audio_source if item["addr"] == "audio" else video_source
                shutil.copyfile(source, filepath)
                return filepath

            item = {
                "addr": "video",
                "addrs": ["video"],
                "audio_addrs": ["audio"],
                "referer": "https://www.douyin.com/",
            }
            item["download_mode"] = "merge_keep"
            with mock.patch.object(douyin_downloader, "download_file", fake_download):
                paths = douyin_downloader.download_video(item, output, {})

            video_track = os.path.join(folder, "result_video.mp4")
            audio_track = os.path.join(folder, "result_audio.m4a")
            self.assertEqual(paths, [video_track, audio_track, output])
            self.assertTrue(os.path.isfile(video_track))
            self.assertTrue(os.path.isfile(audio_track))

            probe = subprocess.run(
                [
                    "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
                    "-of", "csv=p=0", output,
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(set(probe.stdout.split()), {"video", "audio"})

    def test_tracks_mode_does_not_create_merged_file(self):
        with tempfile.TemporaryDirectory() as folder:
            video_source = os.path.join(folder, "source-video.mp4")
            audio_source = os.path.join(folder, "source-audio.m4a")
            output = os.path.join(folder, "result.mp4")
            with open(video_source, "wb") as file_obj:
                file_obj.write(b"video")
            with open(audio_source, "wb") as file_obj:
                file_obj.write(b"audio")

            def fake_download(item, filepath, _headers):
                source = audio_source if item["addr"] == "audio" else video_source
                shutil.copyfile(source, filepath)
                return filepath

            item = {
                "addr": "video",
                "addrs": ["video"],
                "audio_addrs": ["audio"],
                "referer": "https://www.douyin.com/",
                "download_mode": "tracks",
            }
            with mock.patch.object(douyin_downloader, "download_file", fake_download):
                paths = douyin_downloader.download_video(item, output, {})

            self.assertEqual(
                paths,
                [
                    os.path.join(folder, "result_video.mp4"),
                    os.path.join(folder, "result_audio.m4a"),
                ],
            )
            self.assertFalse(os.path.exists(output))

    def test_missing_ffmpeg_keeps_both_tracks(self):
        with tempfile.TemporaryDirectory() as folder:
            output = os.path.join(folder, "result.mp4")

            def fake_download(item, filepath, _headers):
                with open(filepath, "wb") as file_obj:
                    file_obj.write(item["addr"].encode())
                return filepath

            item = {
                "addr": "video",
                "addrs": ["video"],
                "audio_addrs": ["audio"],
                "referer": "https://www.douyin.com/",
                "download_mode": "merge_keep",
            }
            with (
                mock.patch.object(douyin_downloader, "download_file", fake_download),
                mock.patch.object(douyin_downloader, "_has_ffmpeg", return_value=False),
            ):
                paths = douyin_downloader.download_video(item, output, {})

            self.assertEqual(len(paths), 2)
            self.assertTrue(all(os.path.isfile(path) for path in paths))
            self.assertFalse(os.path.exists(output))

    def test_muxed_source_default_keeps_source_and_extracts_both_tracks(self):
        with tempfile.TemporaryDirectory() as folder:
            source = os.path.join(folder, "source.mp4")
            output = os.path.join(folder, "result.mp4")
            self._make_muxed_source(source)

            def fake_download(_item, filepath, _headers):
                shutil.copyfile(source, filepath)
                return filepath

            item = {
                "addr": "muxed",
                "addrs": ["muxed"],
                "audio_addrs": [],
                "referer": "https://www.douyin.com/",
                "download_mode": "merge_keep",
            }
            with mock.patch.object(douyin_downloader, "download_file", fake_download):
                paths = douyin_downloader.download_video(item, output, {})

            video_track = os.path.join(folder, "result_video.mp4")
            audio_track = os.path.join(folder, "result_audio.m4a")
            self.assertEqual(paths, [video_track, audio_track, output])
            self.assertEqual(self._stream_types(output), {"video", "audio"})
            self.assertEqual(self._stream_types(video_track), {"video"})
            self.assertEqual(self._stream_types(audio_track), {"audio"})

    def test_muxed_source_supports_tracks_video_and_audio_modes(self):
        for mode, expected_names in (
            ("tracks", ["result_video.mp4", "result_audio.m4a"]),
            ("video_only", ["result_video.mp4"]),
            ("audio_only", ["result_audio.m4a"]),
        ):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as folder:
                source = os.path.join(folder, "source.mp4")
                output = os.path.join(folder, "result.mp4")
                self._make_muxed_source(source)

                def fake_download(_item, filepath, _headers):
                    shutil.copyfile(source, filepath)
                    return filepath

                item = {
                    "addr": "muxed",
                    "addrs": ["muxed"],
                    "audio_addrs": [],
                    "referer": "https://www.douyin.com/",
                    "download_mode": mode,
                }
                with mock.patch.object(douyin_downloader, "download_file", fake_download):
                    paths = douyin_downloader.download_video(item, output, {})

                self.assertEqual(
                    paths,
                    [os.path.join(folder, name) for name in expected_names],
                )
                self.assertFalse(os.path.exists(output))
                for path in paths:
                    expected_type = "audio" if path.endswith(".m4a") else "video"
                    self.assertEqual(self._stream_types(path), {expected_type})


if __name__ == "__main__":
    unittest.main()
