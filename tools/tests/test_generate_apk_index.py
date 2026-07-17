import os
import stat
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

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

    def test_discovery_rejects_unicode_digits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "KelliKanvas-1١.0.0.apk").write_bytes(b"not strict SemVer")

            self.assertEqual(discover_apks(root), [])

    def test_discovery_rejects_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "payload.bin"
            target.write_bytes(b"apk")
            symlink = root / "KelliKanvas-9.0.0.apk"
            try:
                symlink.symlink_to(target)
            except OSError as error:
                self.skipTest(f"symlink creation is unavailable: {error}")

            self.assertEqual(discover_apks(root), [])

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

    @unittest.skipUnless(os.name == "posix", "POSIX mode semantics required")
    def test_generated_index_has_mode_0644(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            output.write_text("old", encoding="utf-8")
            output.chmod(0o644)

            generate_index(root)

            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o644)

    def test_generation_requests_same_directory_temp_and_uses_replace(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_parent = root / "site"
            output_parent.mkdir()
            output = output_parent / "downloads.html"

            with patch(
                "tools.generate_apk_index.tempfile.NamedTemporaryFile",
                wraps=tempfile.NamedTemporaryFile,
            ) as named_temporary:
                with patch("tools.generate_apk_index.os.replace") as replace:
                    result = generate_index(root, output)

            self.assertEqual(
                named_temporary.call_args.kwargs["dir"],
                output.parent,
            )
            replace.assert_called_once()
            temporary_path, replacement_path = replace.call_args.args
            self.assertEqual(Path(temporary_path).parent, output.parent)
            self.assertEqual(replacement_path, output)
            self.assertEqual(result, output)
            self.assertFalse(output.exists())
            self.assertEqual(list(output.parent.glob(".index-*.tmp")), [])

    def test_generation_flushes_and_fsyncs_before_replace(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            events: list[str] = []
            temporary = MagicMock()
            temporary.name = str(root / ".index-order.tmp")
            temporary.fileno.return_value = 42
            context_manager = MagicMock()
            context_manager.__enter__.return_value = temporary
            temporary.flush.side_effect = lambda: events.append("flush")

            with patch(
                "tools.generate_apk_index.tempfile.NamedTemporaryFile",
                return_value=context_manager,
            ):
                with patch(
                    "tools.generate_apk_index.os.chmod",
                    side_effect=lambda _path, _mode: events.append("chmod"),
                ) as chmod:
                    with patch(
                        "tools.generate_apk_index.os.fsync",
                        side_effect=lambda _fd: events.append("fsync"),
                    ) as fsync:
                        with patch(
                            "tools.generate_apk_index.os.replace",
                            side_effect=lambda _source, _output: events.append("replace"),
                        ):
                            generate_index(root, output)

            temporary.write.assert_called_once()
            chmod.assert_called_once_with(Path(temporary.name), 0o644)
            fsync.assert_called_once_with(42)
            self.assertEqual(events, ["flush", "fsync", "chmod", "replace"])

    def test_generation_cleans_temp_when_chmod_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            output.write_text("old", encoding="utf-8")

            with patch(
                "tools.generate_apk_index.os.chmod",
                side_effect=OSError("chmod failed"),
            ):
                with patch("tools.generate_apk_index.os.replace") as replace:
                    with self.assertRaisesRegex(OSError, "chmod failed"):
                        generate_index(root, output)

            replace.assert_not_called()
            self.assertEqual(output.read_text(encoding="utf-8"), "old")
            self.assertEqual(list(root.glob(".index-*.tmp")), [])

    def test_generation_cleans_temp_when_replace_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            output.write_text("old", encoding="utf-8")

            with patch(
                "tools.generate_apk_index.os.replace",
                side_effect=OSError("replace failed"),
            ):
                with self.assertRaisesRegex(OSError, "replace failed"):
                    generate_index(root, output)

            self.assertEqual(output.read_text(encoding="utf-8"), "old")
            self.assertEqual(list(root.glob(".index-*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
