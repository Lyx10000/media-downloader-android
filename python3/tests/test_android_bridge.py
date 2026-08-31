import os
import sys
import unittest


TEST_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON_DIR = os.path.dirname(TEST_DIR)
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)

from android_bridge import _normalise  # noqa: E402


class AndroidBridgeTests(unittest.TestCase):
    def test_normalises_muxed_video_with_quality_ladder(self):
        detail = {
            "aweme_id": "1234567890123456789",
            "desc": "测试作品",
            "author": {"nickname": "测试作者"},
            "video": {
                "cover": {"url_list": ["https://cdn.example/cover.jpeg"]},
                "bit_rate": [
                    {
                        "bit_rate": 4_000_000,
                        "is_h265": True,
                        "FPS": 60,
                        "play_addr": {
                            "width": 2160,
                            "height": 3840,
                            "data_size": 10_000_000,
                            "url_list": ["https://cdn.example/4k.mp4"],
                        },
                    },
                    {
                        "bit_rate": 2_000_000,
                        "FPS": 30,
                        "play_addr": {
                            "width": 1080,
                            "height": 1920,
                            "data_size": 5_000_000,
                            "url_list": ["https://cdn.example/1080.mp4"],
                        },
                    },
                ],
            },
        }

        result = _normalise(detail, detail["aweme_id"], "video")

        self.assertTrue(result["ok"])
        self.assertEqual(result["author"], "测试作者")
        self.assertEqual(len(result["variants"]), 2)
        self.assertEqual(result["variants"][0]["codec"], "H.265")
        self.assertEqual(result["variants"][0]["height"], 3840)
        self.assertEqual(result["audio_urls"], [])

    def test_normalises_images_to_original_urls(self):
        detail = {
            "aweme_id": "1234567890123456789",
            "images": [{
                "origin_url": {"url_list": ["https://cdn.example/original.jpeg"]},
                "url_list": ["https://cdn.example/compressed-q75.jpeg"],
            }],
        }

        result = _normalise(detail, detail["aweme_id"], "note")

        self.assertEqual(result["kind"], "image")
        self.assertEqual(result["image_urls"], ["https://cdn.example/original.jpeg"])


if __name__ == "__main__":
    unittest.main()
