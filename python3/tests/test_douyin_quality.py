import os
import sys
import unittest


TEST_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON_DIR = os.path.dirname(TEST_DIR)
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)

from douyin_quality import (  # noqa: E402
    choose_video_download_mode,
    choose_video_variant,
    extract_audio_urls,
    extract_image_urls,
    extract_video_variants,
)


def _rate(width, height, bitrate, codec="h264", fps=30, suffix="main"):
    return {
        "bit_rate": bitrate,
        "is_h265": codec == "h265",
        "FPS": fps,
        "play_addr": {
            "width": width,
            "height": height,
            "data_size": bitrate * 10,
            "url_list": [f"https://cdn.example/{suffix}.mp4"],
        },
    }


class VideoVariantTests(unittest.TestCase):
    def test_resolution_precedes_bitrate_and_duplicate_ladders_are_collapsed(self):
        video = {
            "bit_rate": [
                _rate(1080, 1920, 8_000_000, suffix="1080-high"),
                _rate(2160, 3840, 5_000_000, codec="h265", fps=60, suffix="4k"),
                _rate(1080, 1920, 6_000_000, suffix="1080-low"),
                _rate(720, 1280, 3_000_000, suffix="720"),
            ]
        }

        variants = extract_video_variants(video)

        self.assertEqual((variants[0]["width"], variants[0]["height"]), (2160, 3840))
        self.assertEqual(variants[0]["codec"], "H.265")
        self.assertEqual(len(variants), 3)
        self.assertEqual(variants[1]["addrs"][0], "https://cdn.example/1080-high.mp4")

    def test_audio_urls_use_highest_audio_bitrate(self):
        video = {
            "bit_rate_audio": [
                {"audio_meta": {"bitrate": 64_000, "url_list": {"main_url": "https://cdn.example/64.m4a"}}},
                {"audio_meta": {"bitrate": 192_000, "url_list": {
                    "main_url": "https://cdn.example/192.m4a",
                    "backup_url": "https://backup.example/192.m4a",
                }}},
            ]
        }

        self.assertEqual(
            extract_audio_urls(video),
            ["https://cdn.example/192.m4a", "https://backup.example/192.m4a"],
        )

    def test_interactive_selection_replaces_download_addresses(self):
        variants = extract_video_variants({
            "bit_rate": [
                _rate(1080, 1920, 6_000_000, suffix="1080"),
                _rate(720, 1280, 3_000_000, suffix="720"),
            ]
        })
        answers = iter(["2"])
        output = []
        item = {
            "addr": variants[0]["addrs"][0],
            "addrs": variants[0]["addrs"],
            "variants": variants,
        }

        selected = choose_video_variant(
            item,
            input_fn=lambda _prompt: next(answers),
            output_fn=output.append,
        )

        self.assertEqual(selected["addr"], "https://cdn.example/720.mp4")
        self.assertEqual(selected["selected_variant"]["height"], 1280)

    def test_download_mode_defaults_to_merge_and_keep_tracks(self):
        output = []
        item = {"audio_addrs": ["https://cdn.example/audio.m4a"]}

        selected = choose_video_download_mode(
            item,
            input_fn=lambda _prompt: "",
            output_fn=output.append,
        )

        self.assertEqual(selected["download_mode"], "merge_keep")

    def test_download_mode_supports_audio_only(self):
        item = {"audio_addrs": ["https://cdn.example/audio.m4a"]}

        selected = choose_video_download_mode(
            item,
            input_fn=lambda _prompt: "4",
            output_fn=lambda _message: None,
        )

        self.assertEqual(selected["download_mode"], "audio_only")


class ImageVariantTests(unittest.TestCase):
    def test_origin_url_precedes_compressed_cdn_variants(self):
        image = {
            "origin_url": {"url_list": ["https://cdn.example/original.jpeg"]},
            "url_list": [
                "https://cdn.example/display-q75.jpeg",
                "https://cdn.example/display-q90.jpeg",
            ],
        }

        urls = extract_image_urls(image)

        self.assertEqual(urls[0], "https://cdn.example/original.jpeg")
        self.assertEqual(urls[1], "https://cdn.example/display-q90.jpeg")


if __name__ == "__main__":
    unittest.main()
