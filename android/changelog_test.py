import json
import tempfile
import unittest
from pathlib import Path

import changelog


class ChangelogTest(unittest.TestCase):
    def test_parse_notes_bilingual_and_comments(self):
        md = """
## Summary
Refactor internal helpers.

<!-- ## What's new
- Should be ignored -->

## What’s new
- Show what's new when updating
* Fix bus alarm sound

## Technical notes
- Not for users

## 新機能
- アップデート時に変更内容を表示するようにしました
"""
        en, ja = changelog.parse_notes(md)
        self.assertEqual(
            en,
            ["Show what's new when updating", "Fix bus alarm sound"],
        )
        self.assertEqual(ja, ["アップデート時に変更内容を表示するようにしました"])

    def test_technical_pr_has_no_notes(self):
        md = "## Summary\nBump gradle plugin and fix R8 keep rule.\n- Internal change\n"
        self.assertEqual(changelog.parse_notes(md), ([], []))

    def test_round_trip_release_notes(self):
        body = changelog.format_release_notes(
            42,
            ["Add changelog on update"],
            ["アップデート時の変更履歴を追加"],
        )
        en, ja = changelog.parse_notes(body)
        self.assertEqual(en, ["Add changelog on update"])
        self.assertEqual(ja, ["アップデート時の変更履歴を追加"])

    def test_build_changelog_merges_sorts_skips_empty_and_caps(self):
        prs = [
            {"body": "## What's new\n- First feature\n- Shared item"},
            {"body": "## Summary\nTech only"},
            {"body": "## What's new\n- Shared item\n- Second feature\n## 新機能\n- 日本語"},
        ]
        releases = [
            {"tagName": "android-41", "name": "Android 2.0.41", "body": "versionCode 41"},
            {
                "tagName": "android-40",
                "name": "Android 2.0.40",
                "body": "## What's new\n- Older change\n",
            },
            {
                "tagName": "android-39",
                "name": "Android 2.0.39",
                "body": "## What's new\n- Oldest change\n",
            },
            {"tagName": "v1.0.0", "name": "Server", "body": "## What's new\n- Ignore non-android"},
        ]
        entries, cur_en, cur_ja = changelog.build_changelog(
            version=42,
            version_name="2.0.42",
            prs=prs,
            releases=releases,
            limit=2,
        )
        self.assertEqual(cur_en, ["First feature", "Shared item", "Second feature"])
        self.assertEqual(cur_ja, ["日本語"])
        self.assertEqual(
            entries,
            [
                {
                    "version": 42,
                    "version_name": "2.0.42",
                    "en": ["First feature", "Shared item", "Second feature"],
                    "ja": ["日本語"],
                },
                {
                    "version": 40,
                    "version_name": "2.0.40",
                    "en": ["Older change"],
                },
            ],
        )

    def test_play_notes_and_truncation(self):
        long_items = [f"Item {i}: " + ("x" * 120) for i in range(6)]
        md = changelog.format_release_notes(42, long_items, ["日本語の更新"])
        notes = changelog.format_play_notes(md)
        self.assertEqual(len(notes), 2)
        self.assertEqual(notes[0]["language"], "en-US")
        self.assertLessEqual(len(notes[0]["text"]), 500)
        self.assertIn("Item 0:", notes[0]["text"])
        self.assertEqual(notes[1], {"language": "ja-JP", "text": "- 日本語の更新"})

    def test_cli_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            prs_file = tmp_path / "prs.json"
            releases_file = tmp_path / "releases.json"
            out_json = tmp_path / "assets" / "changelog.json"
            out_notes = tmp_path / "release-notes.md"
            prs_file.write_text(json.dumps([{"body": "## What's new\n- Faster sync"}]), encoding="utf-8")
            releases_file.write_text("[]", encoding="utf-8")
            rc = changelog.main(
                [
                    "build",
                    "--version",
                    "42",
                    "--version-name",
                    "2.0.42",
                    "--prs-file",
                    str(prs_file),
                    "--releases-file",
                    str(releases_file),
                    "--out-json",
                    str(out_json),
                    "--out-release-notes",
                    str(out_notes),
                ]
            )
            self.assertEqual(rc, 0)
            data = json.loads(out_json.read_text(encoding="utf-8"))
            self.assertEqual(data, [{"version": 42, "version_name": "2.0.42", "en": ["Faster sync"]}])
            self.assertIn("## What's new\n- Faster sync", out_notes.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
