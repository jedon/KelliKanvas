import tempfile
import unittest
from pathlib import Path

from tools import check_release_sequence


class CheckReleaseSequenceTest(unittest.TestCase):
    def test_missing_file_bootstraps_to_zero(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "missing.txt"
            self.assertEqual(check_release_sequence.read_last_sequence(path), 0)

    def test_reads_tracked_last_sequence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "last-release-sequence.txt"
            path.write_text("42\n", encoding="ascii")
            self.assertEqual(check_release_sequence.read_last_sequence(path), 42)

    def test_accepts_strictly_greater_positive_sequence(self):
        self.assertEqual(check_release_sequence.check_release_sequence("3", 2), 3)
        self.assertEqual(check_release_sequence.check_release_sequence("1", 0), 1)

    def test_rejects_non_positive_and_non_monotonic(self):
        with self.assertRaises(SystemExit) as zero:
            check_release_sequence.check_release_sequence("0", 0)
        self.assertIn("positive integer", str(zero.exception))

        with self.assertRaises(SystemExit) as equal:
            check_release_sequence.check_release_sequence("5", 5)
        self.assertIn("must be > last 5", str(equal.exception))

        with self.assertRaises(SystemExit) as lower:
            check_release_sequence.check_release_sequence("4", 5)
        self.assertIn("must be > last 5", str(lower.exception))

        with self.assertRaises(SystemExit) as padded:
            check_release_sequence.check_release_sequence("01", 0)
        self.assertIn("positive integer", str(padded.exception))

    def test_cli_accepts_monotonic_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "last-release-sequence.txt"
            path.write_text("7\n", encoding="ascii")
            self.assertEqual(
                check_release_sequence.main(["--last-file", str(path), "--next", "8"]),
                0,
            )

    def test_repo_watermark_file_is_non_negative_integer(self):
        watermark = (
            Path(__file__).resolve().parents[2] / "deploy/qnap/last-release-sequence.txt"
        )
        self.assertTrue(watermark.is_file())
        last = check_release_sequence.read_last_sequence(watermark)
        self.assertGreaterEqual(last, 0)


if __name__ == "__main__":
    unittest.main()
