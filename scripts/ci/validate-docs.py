#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IGNORED_PARTS = {".git", "node_modules", "target"}
INVENTORY_FILES = {
    REPO_ROOT / "README.md",
    REPO_ROOT / "docs" / "README.md",
    REPO_ROOT / "frontend" / "README.md",
    REPO_ROOT / "postman" / "README.md",
    REPO_ROOT / "scripts" / "README.md",
}

MARKDOWN_LINK_RE = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
CODE_SPAN_RE = re.compile(r"`([^`\n]+)`")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$", re.MULTILINE)
PATHISH_RE = re.compile(
    r"^(?:\./)?[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*(?:/[A-Za-z0-9._-]+)?/?$"
)
TITLE_RE = re.compile(r'^(?P<target>\S+?)(?:\s+"[^"]*")?$')
FILE_SUFFIXES = {".json", ".md", ".py", ".sh", ".yaml", ".yml"}


def iter_markdown_files() -> list[Path]:
    markdown_files: list[Path] = []
    for markdown_file in REPO_ROOT.rglob("*.md"):
        if any(part in IGNORED_PARTS for part in markdown_file.parts):
            continue
        markdown_files.append(markdown_file)
    return sorted(markdown_files)


MARKDOWN_FILES = iter_markdown_files()


def github_anchor_slug(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii")
    ascii_value = ascii_value.strip().lower()
    ascii_value = re.sub(r"[^\w\- ]", "", ascii_value)
    ascii_value = ascii_value.replace(" ", "-")
    ascii_value = re.sub(r"-+", "-", ascii_value)
    return ascii_value


def collect_anchors(markdown_file: Path) -> set[str]:
    content = markdown_file.read_text(encoding="utf-8")
    anchors: set[str] = set()
    seen: defaultdict[str, int] = defaultdict(int)

    for match in HEADING_RE.finditer(content):
        slug = github_anchor_slug(match.group(2))
        suffix = seen[slug]
        anchor = slug if suffix == 0 else f"{slug}-{suffix}"
        anchors.add(anchor)
        seen[slug] += 1

    return anchors


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def normalize_link_target(raw_target: str) -> str:
    candidate = raw_target.strip()
    if candidate.startswith("<") and candidate.endswith(">"):
        candidate = candidate[1:-1]
    title_match = TITLE_RE.match(candidate)
    return title_match.group("target") if title_match else candidate


def resolve_repo_path(source_file: Path, target: str) -> Path:
    if target.startswith("./"):
        return (source_file.parent / target[2:]).resolve()
    if target.startswith("../"):
        return (source_file.parent / target).resolve()
    return (REPO_ROOT / target.rstrip("/")).resolve()


def resolve_documented_path(source_file: Path, target: str) -> Path:
    if target.startswith(("./", "../")):
        return resolve_repo_path(source_file, target)
    if "/" in target or target.endswith("/"):
        return (REPO_ROOT / target.rstrip("/")).resolve()
    return (source_file.parent / target.rstrip("/")).resolve()


def looks_like_repo_path(candidate: str) -> bool:
    if candidate.startswith(("http://", "https://", "mailto:", "tel:")):
        return False
    if candidate.startswith(("/", "#", "$", "--")):
        return False
    if " " in candidate or ":" in candidate or "\\" in candidate:
        return False
    if not PATHISH_RE.match(candidate):
        return False
    if candidate.endswith("/"):
        return True
    return Path(candidate).suffix.lower() in FILE_SUFFIXES


errors: list[str] = []
anchor_cache: dict[Path, set[str]] = {}

for markdown_file in MARKDOWN_FILES:
    content = markdown_file.read_text(encoding="utf-8")

    for match in MARKDOWN_LINK_RE.finditer(content):
        line = line_number(content, match.start())
        raw_target = normalize_link_target(match.group(1))
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", raw_target):
            continue

        target_path_text, _, anchor = raw_target.partition("#")
        target_path = markdown_file if not target_path_text else resolve_repo_path(markdown_file, target_path_text)

        if not target_path.exists():
            errors.append(
                f"{markdown_file.relative_to(REPO_ROOT)}:{line}: missing markdown link target '{raw_target}'"
            )
            continue

        if anchor:
            if target_path.suffix.lower() != ".md":
                errors.append(
                    f"{markdown_file.relative_to(REPO_ROOT)}:{line}: anchor target '{raw_target}' is not a markdown file"
                )
                continue

            anchors = anchor_cache.setdefault(target_path, collect_anchors(target_path))
            if anchor not in anchors:
                errors.append(
                    f"{markdown_file.relative_to(REPO_ROOT)}:{line}: missing markdown anchor '{anchor}' in '{target_path.relative_to(REPO_ROOT)}'"
                )

    if markdown_file not in INVENTORY_FILES:
        continue

    for match in CODE_SPAN_RE.finditer(content):
        candidate = match.group(1).strip()
        if not looks_like_repo_path(candidate):
            continue

        repo_path = resolve_documented_path(markdown_file, candidate)
        if not repo_path.exists():
            line = line_number(content, match.start())
            errors.append(
                f"{markdown_file.relative_to(REPO_ROOT)}:{line}: missing repository path '{candidate}'"
            )

if errors:
    print("Documentation validation failed:", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    sys.exit(1)

print(f"Validated {len(MARKDOWN_FILES)} markdown files.")
