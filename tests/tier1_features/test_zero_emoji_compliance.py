"""
Tier 1 Feature Test: 0-Emoji Compliance Scanner (ADR-001).

Recursively scans all source files, documentation, manifests, and test suites
for any Unicode emoji or symbol character violations.
Strict 0-emoji compliance enforced.
"""

import os
import re
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Comprehensive emoji regex covering astral plane symbols, dingbats, and presentation selectors
EMOJI_PATTERN = re.compile(
    r"[\U0001F000-\U0001FAFF]"  # Emoticons, Pictographs, Supplemental Symbols
    r"|[\U00002600-\U000027BF]"  # Miscellaneous Symbols & Dingbats
    r"|[\U00002300-\U000023FF]"  # Miscellaneous Technical
    r"|[\U00002B50-\U00002B55]"  # Stars / Circles
    r"|[\uFE0E\uFE0F]"            # Variation selectors
)

EXCLUDED_DIRS = {".git", ".gradle", ".kotlin", "bin", "obj", "build", "TestResults", ".gemini", ".agents", ".obsidian"}
ALLOWED_EXTENSIONS = {
    ".cs", ".kt", ".xaml", ".xml", ".kts", ".json", ".md", ".py", ".iss",
    ".properties", ".txt", ".gradle", ".manifest", ".ps1", ".bat", ".sh"
}


def scan_file_for_emojis(file_path: str) -> list[tuple[int, str]]:
    violations = []
    try:
        with open(file_path, "r", encoding="utf-8", errors="replace") as f:
            for line_idx, line in enumerate(f, start=1):
                matches = EMOJI_PATTERN.findall(line)
                if matches:
                    violations.append((line_idx, f"Found emoji codepoint(s): {matches} in line: {line.strip()}"))
    except Exception as err:
        violations.append((0, f"Error reading file: {err}"))
    return violations


class TestZeroEmojiCompliance(unittest.TestCase):

    def test_zero_emojis_in_repository(self):
        """Verify that zero emoji violations exist across all source, doc, and test files."""
        all_violations = []

        for root, dirs, files in os.walk(REPO_ROOT):
            dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS]
            for file in files:
                ext = os.path.splitext(file)[1].lower()
                if ext in ALLOWED_EXTENSIONS:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, REPO_ROOT)
                    violations = scan_file_for_emojis(full_path)
                    for line_num, msg in violations:
                        all_violations.append(f"{rel_path}:{line_num} -> {msg}")

        self.assertEqual(
            len(all_violations),
            0,
            f"Found {len(all_violations)} emoji violation(s):\n" + "\n".join(all_violations[:20]),
        )


if __name__ == "__main__":
    unittest.main()
