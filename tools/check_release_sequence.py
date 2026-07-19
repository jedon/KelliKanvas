#!/usr/bin/env python3
"""Enforce strictly monotonic release sequences against a tracked watermark."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

POSITIVE_INT = re.compile(r"^[1-9][0-9]*$")
NON_NEGATIVE_INT = re.compile(r"^(0|[1-9][0-9]*)$")
DEFAULT_LAST_FILE = "deploy/qnap/last-release-sequence.txt"


def read_last_sequence(path: pathlib.Path) -> int:
    """Return the highest published sequence, or 0 when the file is absent."""
    if not path.is_file():
        return 0
    text = path.read_text(encoding="ascii").strip()
    if not text:
        return 0
    if not NON_NEGATIVE_INT.fullmatch(text):
        raise SystemExit(f"invalid last-release-sequence file: {path}")
    return int(text)


def check_release_sequence(next_sequence: str, last: int) -> int:
    """Reject non-positive or non-monotonic sequences; return the accepted int."""
    if not POSITIVE_INT.fullmatch(next_sequence):
        raise SystemExit("sequence must be positive integer")
    nxt = int(next_sequence)
    if nxt <= last:
        raise SystemExit(f"release_sequence {nxt} must be > last {last}")
    return nxt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--last-file",
        type=pathlib.Path,
        default=pathlib.Path(DEFAULT_LAST_FILE),
        help="Tracked file holding the highest published release_sequence",
    )
    parser.add_argument("--next", required=True, help="Candidate release_sequence")
    args = parser.parse_args(argv)
    last = read_last_sequence(args.last_file)
    nxt = check_release_sequence(args.next, last)
    print(f"release_sequence {nxt} > last {last}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
