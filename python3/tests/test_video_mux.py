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
            with mock.patch.object(douyin_downloader, "download_file", fake_download):
                douyin_downloader.download_video(item, output, {})

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


if __name__ == "__main__":
    unittest.main()
