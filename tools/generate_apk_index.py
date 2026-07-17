from __future__ import annotations

import argparse
import html
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


_CORE_IDENTIFIER = r"(?:0|[1-9][0-9]*)"
_PRERELEASE_IDENTIFIER = (
    r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
)
_BUILD_IDENTIFIER = r"[0-9A-Za-z-]+"
APK_PATTERN = re.compile(
    rf"^KelliKanvas-"
    rf"(?P<major>{_CORE_IDENTIFIER})\."
    rf"(?P<minor>{_CORE_IDENTIFIER})\."
    rf"(?P<patch>{_CORE_IDENTIFIER})"
    rf"(?:-(?P<prerelease>{_PRERELEASE_IDENTIFIER}"
    rf"(?:\.{_PRERELEASE_IDENTIFIER})*))?"
    rf"(?:\+(?P<build>{_BUILD_IDENTIFIER}(?:\.{_BUILD_IDENTIFIER})*))?"
    rf"\.apk$"
)


@dataclass(frozen=True)
class ApkRelease:
    filename: str
    version: str
    size: int
    modified: datetime
    sort_key: tuple[object, ...]


def _prerelease_key(value: str | None) -> tuple[tuple[object, ...], ...]:
    if value is None:
        return ()
    return tuple(
        (0, int(identifier), "")
        if identifier.isdigit()
        else (1, 0, identifier)
        for identifier in value.split(".")
    )


def _release(path: Path) -> ApkRelease | None:
    if path.is_symlink() or not path.is_file():
        return None

    match = APK_PATTERN.fullmatch(path.name)
    if match is None:
        return None

    stat = path.stat()
    prerelease = match.group("prerelease")
    version = path.name.removeprefix("KelliKanvas-").removesuffix(".apk")
    return ApkRelease(
        filename=path.name,
        version=version,
        size=stat.st_size,
        modified=datetime.fromtimestamp(stat.st_mtime, timezone.utc),
        sort_key=(
            int(match.group("major")),
            int(match.group("minor")),
            int(match.group("patch")),
            prerelease is None,
            _prerelease_key(prerelease),
            stat.st_mtime_ns,
            path.name,
        ),
    )


def discover_apks(directory: Path) -> list[ApkRelease]:
    releases = (
        release
        for release in (_release(path) for path in directory.iterdir())
        if release is not None
    )
    return sorted(releases, key=lambda release: release.sort_key, reverse=True)


def _human_size(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if amount < 1024 or unit == "TiB":
            if unit == "B":
                return f"{amount:.0f} {unit}"
            return f"{amount:.1f} {unit}"
        amount /= 1024
    raise AssertionError("unreachable")


def _render_cards(releases: Iterable[ApkRelease]) -> str:
    cards: list[str] = []
    for position, release in enumerate(releases):
        filename = html.escape(release.filename, quote=True)
        version = html.escape(release.version, quote=True)
        size = html.escape(_human_size(release.size), quote=True)
        published = html.escape(
            release.modified.astimezone(timezone.utc).strftime(
                "%Y-%m-%d %H:%M UTC"
            ),
            quote=True,
        )
        latest = '<span class="badge">Latest version</span>' if position == 0 else ""
        cards.append(
            f"""
      <article class="apk-card">
        <div class="title-row">
          <h2>Version {version}</h2>
          {latest}
        </div>
        <p class="filename">{filename}</p>
        <p class="metadata">{size} · {published}</p>
        <div class="actions">
          <a class="download" href="{filename}">Download APK</a>
          <button type="button" data-path="{filename}">Copy URL</button>
        </div>
      </article>"""
        )
    return "\n".join(cards)


def render_page(releases: list[ApkRelease]) -> str:
    content = _render_cards(releases)
    if not releases:
        content = '<p class="empty">No APK versions have been published yet.</p>'

    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>KelliKanvas APK Downloads</title>
  <style>
    :root {{
      color-scheme: dark;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      background: #0d1117;
      color: #f0f6fc;
    }}
    main {{
      width: min(44rem, calc(100% - 2rem));
      margin: 2rem auto;
    }}
    h1 {{ margin-bottom: .35rem; }}
    .intro, .filename, .metadata {{ color: #b1bac4; }}
    .intro {{ margin-top: 0; }}
    .apk-card, .empty {{
      margin: 1rem 0;
      padding: 1rem;
      border: 1px solid #30363d;
      border-radius: 1rem;
      background: #161b22;
    }}
    .title-row {{
      display: flex;
      flex-wrap: wrap;
      gap: .75rem;
      align-items: center;
    }}
    h2 {{ margin: 0; font-size: 1.2rem; }}
    .badge {{
      padding: .25rem .55rem;
      border-radius: 999px;
      background: #a7f3d0;
      color: #064e3b;
      font-size: .78rem;
      font-weight: 700;
    }}
    .filename {{
      overflow-wrap: anywhere;
      font-family: ui-monospace, "Cascadia Mono", monospace;
      font-size: .9rem;
    }}
    .actions {{
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: .65rem;
    }}
    a, button {{
      min-height: 2.8rem;
      padding: .75rem;
      border: 1px solid #58a6ff;
      border-radius: .7rem;
      cursor: pointer;
      font: inherit;
      text-align: center;
    }}
    a {{
      background: #1f6feb;
      color: #fff;
      text-decoration: none;
    }}
    button {{ background: transparent; color: #79c0ff; }}
    a:focus-visible, button:focus-visible {{
      outline: 3px solid #f2cc60;
      outline-offset: 2px;
    }}
    #status {{ min-height: 1.5rem; color: #7ee787; }}
    @media (max-width: 30rem) {{
      main {{ margin-top: 1rem; }}
      .actions {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
  <main>
    <h1>KelliKanvas APK Downloads</h1>
    <p class="intro">Copy a direct APK URL and send it to the TV downloader.</p>
    <p id="status" role="status" aria-live="polite"></p>
    {content}
  </main>
  <script>
    const status = document.querySelector("#status");

    function copyWithFallback(url) {{
      const input = document.createElement("textarea");
      input.value = url;
      input.setAttribute("readonly", "");
      input.style.position = "fixed";
      input.style.opacity = "0";
      document.body.appendChild(input);
      input.select();

      let copied = false;
      try {{
        copied = document.execCommand("copy");
      }} finally {{
        input.remove();
      }}
      if (!copied) throw new Error("copy failed");
    }}

    async function copyUrl(button) {{
      const url = new URL(button.dataset.path, window.location.href).href;
      try {{
        if (window.isSecureContext && navigator.clipboard) {{
          await navigator.clipboard.writeText(url);
        }} else {{
          copyWithFallback(url);
        }}
        status.textContent = `Copied ${{url}}`;
      }} catch (_) {{
        window.prompt("Copy this APK URL:", url);
      }}
    }}

    document.querySelectorAll("button[data-path]").forEach((button) => {{
      button.addEventListener("click", () => copyUrl(button));
    }});
  </script>
</body>
</html>
"""


def generate_index(directory: Path, output: Path | None = None) -> Path:
    directory = directory.resolve()
    output = output if output is not None else directory / "index.html"
    page = render_page(discover_apks(directory))
    temporary_path: Path | None = None

    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output.parent,
            prefix=".index-",
            suffix=".tmp",
            delete=False,
            newline="\n",
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(page)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.chmod(temporary_path, 0o644)
        os.replace(temporary_path, output)
        return output
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate the KelliKanvas APK version index"
    )
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    generate_index(arguments.directory, arguments.output)


if __name__ == "__main__":
    main()
