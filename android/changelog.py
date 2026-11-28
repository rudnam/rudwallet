#!/usr/bin/env python3
"""Builds and formats the Android app's bilingual changelog from PR and release notes."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
_HEADING_RE = re.compile(r"^\s{0,3}(#{2,6})\s+(.+?)\s*#*\s*$")
_BULLET_RE = re.compile(r"^\s*[-*•]\s+(.*\S)\s*$")
_TAG_RE = re.compile(r"^android-(\d+)$")

_EN_HEADINGS = {"what's new", "what’s new"}
_JA_HEADINGS = {"新機能"}


def parse_notes(markdown: str) -> tuple[list[str], list[str]]:
    """Extract English and Japanese bullet items from PR or GitHub release markdown."""
    if not markdown:
        return [], []
    cleaned = _COMMENT_RE.sub("", markdown)
    en: list[str] = []
    ja: list[str] = []
    seen_en: set[str] = set()
    seen_ja: set[str] = set()
    section: str | None = None

    for line in cleaned.splitlines():
        heading = _HEADING_RE.match(line)
        if heading:
            title = heading.group(2).strip().casefold()
            if title in _EN_HEADINGS:
                section = "en"
            elif title in _JA_HEADINGS:
                section = "ja"
            else:
                section = None
            continue
        if section is None:
            continue
        bullet = _BULLET_RE.match(line)
        if not bullet:
            continue
        item = bullet.group(1).strip()
        if not item:
            continue
        if section == "en" and item not in seen_en:
            seen_en.add(item)
            en.append(item)
        elif section == "ja" and item not in seen_ja:
            seen_ja.add(item)
            ja.append(item)
    return en, ja


def format_release_notes(version_code: int, en: list[str], ja: list[str]) -> str:
    """Format the GitHub release body for android-<versionCode>."""
    parts = [
        f"versionCode {version_code}, published on wallet.rudnam.com. "
        "The .aab goes to the Play Console (android-play.yml), on the track PLAY_TRACK names."
    ]
    if en:
        parts.append("## What's new\n" + "\n".join(f"- {item}" for item in en))
    if ja:
        parts.append("## 新機能\n" + "\n".join(f"- {item}" for item in ja))
    return "\n\n".join(parts) + "\n"


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def build_changelog(
    version: int,
    version_name: str,
    prs: list[dict],
    releases: list[dict],
    limit: int = 20,
) -> tuple[list[dict], list[str], list[str]]:
    """Combine current PR notes and past android-* GitHub releases into changelog entries."""
    cur_en: list[str] = []
    cur_ja: list[str] = []
    for pr in prs:
        en, ja = parse_notes(pr.get("body") or "")
        cur_en.extend(en)
        cur_ja.extend(ja)
    cur_en = _dedupe(cur_en)
    cur_ja = _dedupe(cur_ja)

    entries_by_version: dict[int, dict] = {}
    for rel in releases:
        tag = rel.get("tagName") or ""
        m = _TAG_RE.match(tag)
        if not m:
            continue
        rel_version = int(m.group(1))
        if rel_version >= version:
            continue
        en, ja = parse_notes(rel.get("body") or "")
        if not en and not ja:
            continue
        name = (rel.get("name") or "").removeprefix("Android ").strip()
        entry: dict = {"version": rel_version}
        if name:
            entry["version_name"] = name
        if en:
            entry["en"] = en
        if ja:
            entry["ja"] = ja
        entries_by_version[rel_version] = entry

    if cur_en or cur_ja:
        entry = {"version": version}
        if version_name:
            entry["version_name"] = version_name
        if cur_en:
            entry["en"] = cur_en
        if cur_ja:
            entry["ja"] = cur_ja
        entries_by_version[version] = entry

    ordered = [entries_by_version[v] for v in sorted(entries_by_version, reverse=True)][:limit]
    return ordered, cur_en, cur_ja


def _format_play_text(items: list[str], max_chars: int = 500) -> str:
    lines = [f"- {item}" for item in items]
    text = "\n".join(lines)
    if len(text) <= max_chars:
        return text
    kept: list[str] = []
    for line in lines:
        candidate = "\n".join([*kept, line])
        if len(candidate) <= max_chars:
            kept.append(line)
        else:
            break
    if kept:
        return "\n".join(kept)
    return lines[0][: max_chars - 1] + "…"


def format_play_notes(markdown: str) -> list[dict[str, str]]:
    """Convert GitHub release markdown into Google Play Developer API releaseNotes objects."""
    en, ja = parse_notes(markdown)
    notes: list[dict[str, str]] = []
    if en:
        notes.append({"language": "en-US", "text": _format_play_text(en)})
    if ja:
        notes.append({"language": "ja-JP", "text": _format_play_text(ja)})
    return notes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    build_p = sub.add_parser("build", help="Build assets/changelog.json and release-notes.md")
    build_p.add_argument("--version", type=int, required=True)
    build_p.add_argument("--version-name", required=True)
    build_p.add_argument("--prs-file", required=True, type=Path)
    build_p.add_argument("--releases-file", required=True, type=Path)
    build_p.add_argument("--out-json", required=True, type=Path)
    build_p.add_argument("--out-release-notes", required=True, type=Path)
    build_p.add_argument("--limit", type=int, default=20)

    play_p = sub.add_parser("play-notes", help="Format Play Console releaseNotes JSON from markdown")
    play_p.add_argument("--body-file", type=Path)

    args = parser.parse_args(argv)
    if args.command == "build":
        prs = json.loads(args.prs_file.read_text(encoding="utf-8"))
        releases = json.loads(args.releases_file.read_text(encoding="utf-8"))
        entries, cur_en, cur_ja = build_changelog(
            version=args.version,
            version_name=args.version_name,
            prs=prs,
            releases=releases,
            limit=args.limit,
        )
        args.out_json.parent.mkdir(parents=True, exist_ok=True)
        args.out_json.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        args.out_release_notes.parent.mkdir(parents=True, exist_ok=True)
        args.out_release_notes.write_text(
            format_release_notes(args.version, cur_en, cur_ja), encoding="utf-8"
        )
    elif args.command == "play-notes":
        markdown = (
            args.body_file.read_text(encoding="utf-8") if args.body_file else sys.stdin.read()
        )
        print(json.dumps(format_play_notes(markdown), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
