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
    format_video_variant,
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
    def test_formats_exact_estimated_and_unknown_sizes(self):
        base = {"width": 1080, "height": 1920, "bitrate": 4_000_000}

        exact = format_video_variant({**base, "size": 10_000_000, "size_source": "cdn"})
        estimated = format_video_variant({
            **base, "size": 10_000_000, "size_source": "estimated",
        })
        unknown = format_video_variant({**base, "size": 0, "size_source": "unknown"})

        self.assertIn("9.5 MB", exact)
        self.assertNotIn("约 9.5 MB", exact)
        self.assertIn("约 9.5 MB", estimated)
        self.assertIn("大小未知", unknown)

    def test_uses_exact_size_from_rate_when_play_address_omits_it(self):
        video = {
            "duration": 10_000,
            "bit_rate": [{
                "bit_rate": 4_000_000,
                "file_size": 7_654_321,
                "play_addr": {
                    "width": 1080,
                    "height": 1920,
                    "url_list": ["https://cdn.example/exact.mp4"],
                },
            }],
        }

        variant = extract_video_variants(video)[0]

        self.assertEqual(variant["size"], 7_654_321)
        self.assertEqual(variant["size_source"], "api")

    def test_estimates_missing_size_from_bitrate_and_duration(self):
        video = {
            "duration": 10_000,
            "bit_rate": [{
                "bit_rate": 4_000_000,
                "play_addr": {
                    "width": 1080,
                    "height": 1920,
                    "url_list": ["https://cdn.example/estimated.mp4"],
                },
            }],
        }

        variant = extract_video_variants(video)[0]

        self.assertEqual(variant["size"], 5_000_000)
        self.assertEqual(variant["size_source"], "estimated")

    def test_marks_size_unknown_without_size_or_duration(self):
        video = {
            "bit_rate": [{
                "bit_rate": 4_000_000,
                "play_addr": {
                    "width": 1080,
                    "height": 1920,
                    "url_list": ["https://cdn.example/unknown.mp4"],
                },
            }],
        }

        variant = extract_video_variants(video)[0]

        self.assertEqual(variant["size"], 0)
        self.assertEqual(variant["size_source"], "unknown")

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

    def test_download_mode_is_available_for_muxed_source(self):
        output = []
        item = {"audio_addrs": []}

        selected = choose_video_download_mode(
            item,
            input_fn=lambda _prompt: "4",
            output_fn=output.append,
        )

        self.assertEqual(selected["download_mode"], "audio_only")
        self.assertTrue(any("音视频合一" in message for message in output))
        self.assertTrue(any("[4]" in message for message in output))


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
