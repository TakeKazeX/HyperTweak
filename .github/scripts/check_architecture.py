#!/usr/bin/env python3
"""Guard source-level layer boundaries, including imports and fully qualified references."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = {
    "ui": {"hook"},
    "util": {"hook", "ui", "app"},
    "settings": {"hook", "ui", "util", "app", "presentation"},
    "presentation": {"hook", "ui", "app"},
    "hook": {"ui", "app"},
}
# Ignore comments and literals, including KDoc and Kotlin raw strings. This is a source guard,
# not a compiler dependency graph; reflection, generated code and runtime wiring need review.
NON_CODE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
REFERENCE = re.compile(r'\bcom\s*\.\s*takekazex\s*\.\s*hypertweak\s*\.\s*(\w+)\b')


def violations(layer, source):
    code = NON_CODE.sub(lambda match: " " * len(match[0]), source)
    return [(source.count("\n", 0, match.start()) + 1, match[1])
            for match in REFERENCE.finditer(code) if match[1] in FORBIDDEN.get(layer, set())]


def main():
    failures = []
    for language in ("kotlin", "java"):
        base = ROOT / f"app/src/main/{language}/com/takekazex/hypertweak"
        for path in sorted(base.rglob("*")):
            if path.suffix not in (".kt", ".java"):
                continue
            layer = path.relative_to(base).parts[0]
            failures.extend(f"{path.relative_to(ROOT)}:{line}: forbidden {layer} -> {target}"
                            for line, target in violations(layer, path.read_text()))
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Architecture source boundaries passed (Kotlin and Java).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
