#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
ALLOWED_SECTIONS = ("Added", "Changed", "Fixed", "Removed", "Security", "Docs")
SECTION_ORDER = {name: index for index, name in enumerate(ALLOWED_SECTIONS)}


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def main() -> int:
    content = CHANGELOG.read_text(encoding="utf-8")
    errors: list[str] = []

    unreleased_match = re.search(r"^##\s+Unreleased\s*$", content, re.MULTILINE)
    if not unreleased_match:
        print("CHANGELOG.md is missing the '## Unreleased' heading.", file=sys.stderr)
        return 1

    remaining = content[unreleased_match.end() :]
    next_release_match = re.search(r"^##\s+", remaining, re.MULTILINE)
    unreleased_body = remaining[: next_release_match.start()] if next_release_match else remaining

    section_matches = list(re.finditer(r"^###\s+(.+?)\s*$", unreleased_body, re.MULTILINE))
    if not section_matches:
        print("CHANGELOG.md must contain at least one subsection under '## Unreleased'.", file=sys.stderr)
        return 1

    prefix = unreleased_body[: section_matches[0].start()]
    if prefix.strip():
        errors.append("Unreleased content must be grouped under supported '###' subsections.")

    previous_index = -1
    seen_sections: set[str] = set()

    for index, match in enumerate(section_matches):
        section_name = match.group(1).strip()
        start = match.end()
        end = section_matches[index + 1].start() if index + 1 < len(section_matches) else len(unreleased_body)
        body = unreleased_body[start:end]
        body_lines = [line for line in body.splitlines() if line.strip()]
        heading_line = line_number(content, unreleased_match.end() + match.start())

        if section_name not in SECTION_ORDER:
            errors.append(
                f"CHANGELOG.md:{heading_line}: unsupported subsection '{section_name}'. "
                f"Use one of: {', '.join(ALLOWED_SECTIONS)}."
            )
            continue

        if section_name in seen_sections:
            errors.append(f"CHANGELOG.md:{heading_line}: duplicate subsection '{section_name}'.")
            continue

        seen_sections.add(section_name)

        current_index = SECTION_ORDER[section_name]
        if current_index < previous_index:
            errors.append(f"CHANGELOG.md:{heading_line}: subsection '{section_name}' is out of order.")
        previous_index = current_index

        if not body_lines:
            errors.append(f"CHANGELOG.md:{heading_line}: subsection '{section_name}' must include at least one bullet.")
            continue

        running_offset = 0
        for line in body_lines:
            if line.startswith("- ") or line.startswith("  "):
                running_offset = body.find(line, running_offset) + len(line)
                continue

            line_offset = body.find(line, running_offset)
            errors.append(
                f"CHANGELOG.md:{line_number(content, unreleased_match.end() + start + line_offset)}: "
                f"subsection '{section_name}' must contain only bullet entries."
            )
            break

    if errors:
        print("Changelog validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print("Validated CHANGELOG.md structure.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
