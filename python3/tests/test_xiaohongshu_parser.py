import json
import os
import sys
import unittest


TEST_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON_DIR = os.path.dirname(TEST_DIR)
if PYTHON_DIR not in sys.path:
    sys.path.insert(0, PYTHON_DIR)

from xiaohongshu_parser import (  # noqa: E402
    _is_direct_video_url,
    _secure_xhscdn_url,
    extract_image_candidates,
    extract_initial_state,
    extract_video_variants,
    find_target_note,
    image_candidates,
    is_xiaohongshu_share,
    normalize_note,
    parse_xiaohongshu_share,
)


class XiaohongshuParserTests(unittest.TestCase):
    def test_upgrades_only_trusted_xhscdn_urls_to_https(self):
        self.assertEqual(
            _secure_xhscdn_url("http://sns-video-v2.xhscdn.com/video.mp4?token=abc"),
            "https://sns-video-v2.xhscdn.com/video.mp4?token=abc",
        )
        self.assertEqual(
            _secure_xhscdn_url("http://sns-bak-v1.xhscdn.com/video.mp4"),
            "https://sns-bak-v1.xhscdn.com/video.mp4",
        )
        self.assertEqual(
            _secure_xhscdn_url("http://xhscdn.com.evil.example/video.mp4"),
            "http://xhscdn.com.evil.example/video.mp4",
        )

    def test_recognises_public_and_short_links(self):
        self.assertTrue(is_xiaohongshu_share("https://www.xiaohongshu.com/explore/abc123"))
        self.assertTrue(is_xiaohongshu_share("复制 https://xhslink.cn/AbCdEf 打开小红书"))
        self.assertFalse(is_xiaohongshu_share("https://example.com/explore/abc123"))
        self.assertFalse(is_xiaohongshu_share("https://evilxiaohongshu.com/explore/abc123"))

    def test_extracts_initial_state_without_corrupting_strings(self):
        page = """
            <script>window.__INITIAL_STATE__={"note":{"text":"{undefined}","missing":undefined}};</script>
        """
        state = extract_initial_state(page)
        self.assertEqual(state["note"]["text"], "{undefined}")
        self.assertIsNone(state["note"]["missing"])

    def test_binds_only_the_requested_note(self):
        state = {
            "note": {
                "noteDetailMap": {
                    "wanted": {"note": {"noteId": "wanted", "title": "目标"}},
                    "recommended": {"note": {"noteId": "recommended", "title": "推荐"}},
                }
            }
        }
        self.assertEqual(find_target_note(state, "wanted")["title"], "目标")
        self.assertIsNone(find_target_note(state, "absent"))

    def test_preserves_special_original_image_paths(self):
        candidates = image_candidates({
            "urlDefault": "https://sns-img-qc.xhscdn.com/1040g008/spectrum/abc123!nd_dft_wlteh_webp_3"
        })
        self.assertEqual(candidates[0], "https://sns-img-bd.xhscdn.com/spectrum/abc123")
        self.assertIn("https://sns-img-qn.xhscdn.com/spectrum/abc123", candidates)
        self.assertTrue(candidates[-1].endswith("!nd_dft_wlteh_webp_3"))

        pre_post = image_candidates({
            "urlDefault": "https://sns-img-hw.xhscdn.com/a/b/notes_pre_post/xyz987!large"
        })
        self.assertEqual(pre_post[0], "https://sns-img-bd.xhscdn.com/notes_pre_post/xyz987")

    def test_collects_every_image_as_separate_candidate_list(self):
        result = extract_image_candidates({
            "imageList": [
                {"original": "https://sns-img-bd.xhscdn.com/spectrum/one"},
                {"urlDefault": "https://sns-img-qc.xhscdn.com/notes_pre_post/two!webp"},
            ]
        })
        self.assertEqual(len(result), 2)
        self.assertIn("spectrum/one", result[0][0])
        self.assertIn("notes_pre_post/two", result[1][0])

    def test_rejects_hls_and_transport_streams(self):
        self.assertFalse(_is_direct_video_url("https://sns-video-bd.xhscdn.com/a/master.m3u8"))
        self.assertFalse(_is_direct_video_url("https://sns-video-bd.xhscdn.com/hls/a.mp4"))
        self.assertFalse(_is_direct_video_url("https://sns-video-bd.xhscdn.com/a/segment.ts"))
        self.assertTrue(_is_direct_video_url("https://sns-video-bd.xhscdn.com/a/video.mp4"))

    def test_sorts_video_ladder_and_prefers_h264_on_equal_quality(self):
        note = {
            "video": {
                "media": {
                    "stream": {
                        "h265": [{
                            "masterUrl": "https://sns-video-bd.xhscdn.com/4k-hevc.mp4",
                            "width": 2160,
                            "height": 3840,
                            "fps": 60,
                            "avgBitrate": 8_000_000,
                            "size": 80_000_000,
                        }],
                        "h264": [{
                            "masterUrl": "http://sns-video-bd.xhscdn.com/4k-avc.mp4",
                            "width": 2160,
                            "height": 3840,
                            "fps": 60,
                            "avgBitrate": 8_000_000,
                            "size": 80_000_000,
                        }, {
                            "masterUrl": "https://sns-video-bd.xhscdn.com/1080.mp4",
                            "width": 1080,
                            "height": 1920,
                        }],
                    }
                }
            }
        }
        variants = extract_video_variants(note)
        self.assertEqual(variants[0]["codec"], "H.264")
        self.assertEqual(variants[0]["width"], 2160)
        self.assertTrue(variants[0]["url"].startswith("https://"))
        self.assertEqual(variants[-1]["width"], 1080)

    def test_normalises_image_and_video_notes(self):
        image = normalize_note({
            "noteId": "image-note",
            "type": "normal",
            "title": "图片标题",
            "user": {"nickname": "作者"},
            "imageList": [{
                "urlDefault": "https://sns-img-qc.xhscdn.com/spectrum/image-one!webp"
            }],
        }, "image-note", "https://www.xiaohongshu.com/explore/image-note")
        self.assertEqual(image["platform"], "xiaohongshu")
        self.assertEqual(image["kind"], "image")
        self.assertEqual(image["content_id"], "image-note")

        video = normalize_note({
            "noteId": "video-note",
            "type": "video",
            "video": {"consumer": {"originVideoKey": "origin/video-file"}},
        }, "video-note", "https://www.xiaohongshu.com/explore/video-note")
        self.assertEqual(video["kind"], "video")
        self.assertTrue(video["variants"][0]["urls"][0].startswith("https://sns-video-bd.xhscdn.com/"))

    def test_end_to_end_uses_redirect_target_note(self):
        note_id = "64abc123"
        state = {
            "note": {"noteDetailMap": {note_id: {"note": {
                "noteId": note_id,
                "type": "normal",
                "imageList": [{
                    "urlDefault": "https://sns-img-qc.xhscdn.com/spectrum/end-to-end!webp"
                }],
            }}}}
        }

        class Response:
            url = f"https://www.xiaohongshu.com/explore/{note_id}"
            text = f"<script>window.__INITIAL_STATE__={json.dumps(state)}</script>"

            def raise_for_status(self):
                pass

        result = parse_xiaohongshu_share(
            "复制 https://xhslink.cn/test 打开小红书",
            request_get=lambda *args, **kwargs: Response(),
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["content_id"], note_id)


if __name__ == "__main__":
    unittest.main()
