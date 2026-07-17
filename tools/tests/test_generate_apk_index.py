import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from tools.generate_apk_index import ApkRelease, discover_apks, generate_index, render_page


class ApkIndexTest(unittest.TestCase):
    def test_empty_directory_generates_empty_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = generate_index(Path(directory))

            self.assertEqual(output, Path(directory) / "index.html")
            page = output.read_text(encoding="utf-8")
            self.assertIn("No APK versions have been published yet.", page)
            self.assertNotIn('class="apk-card"', page)

    def test_discovers_strict_semver_apks_newest_first(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            accepted = (
                "KelliKanvas-1.9.0.apk",
                "KelliKanvas-2.0.0-alpha.apk",
                "KelliKanvas-2.0.0-alpha.1.apk",
                "KelliKanvas-2.0.0-beta.apk",
                "KelliKanvas-2.0.0-beta.2.apk",
                "KelliKanvas-2.0.0-beta.11.apk",
                "KelliKanvas-2.0.0-rc.1.apk",
                "KelliKanvas-2.0.0+build.1.apk",
                "KelliKanvas-2.0.0+build.2.apk",
                "KelliKanvas-2.0.0.apk",
            )
            for name in accepted:
                (root / name).write_bytes(b"apk")

            ignored = (
                ".KelliKanvas-3.0.0.apk.tmp",
                "KelliKanvas-01.0.0.apk",
                "KelliKanvas-1.01.0.apk",
                "KelliKanvas-1.0.01.apk",
                "KelliKanvas-2.0.0-alpha.01.apk",
                "KelliKanvas-2.0.0-.apk",
                "KelliKanvas-2.0.0+.apk",
                "KelliKanvas-2.0.apk",
                "KelliKanvas-v2.0.0.apk",
                "KelliKanvas-2.0.0_alpha.apk",
                "OtherApp-9.0.0.apk",
                "notes.txt",
            )
            for name in ignored:
                (root / name).write_bytes(b"not an accepted apk")

            (root / "KelliKanvas-8.0.0.apk").mkdir()
            symlink = root / "KelliKanvas-9.0.0.apk"
            try:
                symlink.symlink_to(root / "KelliKanvas-2.0.0.apk")
            except OSError as error:
                self.skipTest(f"symlink creation is unavailable: {error}")

            releases = discover_apks(root)

            self.assertEqual(
                [release.filename for release in releases],
                [
                    "KelliKanvas-2.0.0.apk",
                    "KelliKanvas-2.0.0+build.2.apk",
                    "KelliKanvas-2.0.0+build.1.apk",
                    "KelliKanvas-2.0.0-rc.1.apk",
                    "KelliKanvas-2.0.0-beta.11.apk",
                    "KelliKanvas-2.0.0-beta.2.apk",
                    "KelliKanvas-2.0.0-beta.apk",
                    "KelliKanvas-2.0.0-alpha.1.apk",
                    "KelliKanvas-2.0.0-alpha.apk",
                    "KelliKanvas-1.9.0.apk",
                ],
            )
            self.assertTrue(all(isinstance(release, ApkRelease) for release in releases))
            self.assertEqual(releases[0].version, "2.0.0")

    def test_page_contains_metadata_relative_links_and_copy_controls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "KelliKanvas-2.3.4.apk"
            apk.write_bytes(b"x" * 1536)
            os.utime(apk, (1_700_000_000, 1_700_000_000))

            page = generate_index(root).read_text(encoding="utf-8")

            self.assertIn("Latest version", page)
            self.assertIn("Version 2.3.4", page)
            self.assertIn("1.5 KiB", page)
            self.assertIn("2023-11-14 22:13 UTC", page)
            self.assertIn('href="KelliKanvas-2.3.4.apk"', page)
            self.assertIn('data-path="KelliKanvas-2.3.4.apk"', page)
            self.assertIn("new URL(button.dataset.path, window.location.href)", page)
            self.assertIn("navigator.clipboard", page)
            self.assertIn("window.isSecureContext", page)
            self.assertIn('document.execCommand("copy")', page)
            self.assertIn('window.prompt("Copy this APK URL:", url)', page)
            self.assertIn('aria-live="polite"', page)

    def test_render_page_escapes_dynamic_content(self) -> None:
        release = ApkRelease(
            filename='KelliKanvas-1.0.0+build"&<.apk',
            version='1.0.0+build"&<',
            size=1,
            modified=datetime(2026, 7, 17, tzinfo=timezone.utc),
            sort_key=(),
        )

        page = render_page([release])

        self.assertNotIn('build"&<', page)
        self.assertIn("build&quot;&amp;&lt;", page)

    def test_generation_atomically_replaces_existing_page(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            output.write_text("old", encoding="utf-8")

            result = generate_index(root)

            self.assertEqual(result, output)
            self.assertNotEqual(output.read_text(encoding="utf-8"), "old")
            self.assertEqual(list(root.glob(".index-*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
