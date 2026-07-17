# QNAP APK Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy a LAN-only nginx site on DarklingNAS that lists every published KelliKanvas APK version and lets a phone user copy a direct APK URL into Send to TV Quick.

**Architecture:** A tested Python generator scans a QNAP content directory and atomically writes a self-contained mobile index page. Container Station runs a locked-down nginx container on port 8088 with the content directory mounted read-only. APK publication copies and verifies the versioned file first, then runs the generator so incomplete files are never advertised.

**Tech Stack:** Python 3 standard library and `unittest`, nginx Alpine, Docker Compose through QNAP Container Station, PowerShell/Posh-SSH for deployment, HTTP smoke tests.

---

## File Map

- `tools/generate_apk_index.py`: Discover valid versioned APKs and atomically generate `index.html`.
- `tools/tests/test_generate_apk_index.py`: Verify filtering, semantic ordering, empty state, metadata, copy controls, and atomic replacement.
- `deploy/qnap/compose.yaml`: Define the isolated nginx service, read-only mounts, health check, and restart policy.
- `deploy/qnap/nginx.conf`: Restrict methods and paths, set APK MIME/caching, and add browser security headers.
- `deploy/qnap/README.md`: Document deployment, publication, rollback, and verification commands.

### Task 1: Build the APK index generator

**Files:**
- Create: `tools/generate_apk_index.py`
- Create: `tools/tests/test_generate_apk_index.py`

- [ ] **Step 1: Write the failing generator tests**

Create `tools/tests/test_generate_apk_index.py`:

```python
import os
import tempfile
import unittest
from pathlib import Path

from tools.generate_apk_index import discover_apks, generate_index


class ApkIndexTest(unittest.TestCase):
    def test_empty_directory_generates_empty_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = generate_index(Path(directory))

            self.assertEqual(output, Path(directory) / "index.html")
            page = output.read_text(encoding="utf-8")
            self.assertIn("No APK versions have been published yet.", page)
            self.assertNotIn('class="apk-card"', page)

    def test_discovers_semver_apks_newest_first_and_ignores_other_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in (
                "KelliKanvas-1.9.0.apk",
                "KelliKanvas-1.10.0-beta.1.apk",
                "KelliKanvas-1.10.0.apk",
                ".KelliKanvas-2.0.0.apk.tmp",
                "OtherApp-9.0.0.apk",
                "notes.txt",
            ):
                (root / name).write_bytes(b"apk")

            names = [apk.filename for apk in discover_apks(root)]

            self.assertEqual(
                names,
                [
                    "KelliKanvas-1.10.0.apk",
                    "KelliKanvas-1.10.0-beta.1.apk",
                    "KelliKanvas-1.9.0.apk",
                ],
            )

    def test_page_contains_metadata_relative_links_and_copy_controls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "KelliKanvas-2.3.4.apk"
            apk.write_bytes(b"x" * 1536)
            os.utime(apk, (1_700_000_000, 1_700_000_000))

            page = generate_index(root).read_text(encoding="utf-8")

            self.assertIn("Latest version", page)
            self.assertIn("Version 2.3.4", page)
            self.assertIn('href="KelliKanvas-2.3.4.apk"', page)
            self.assertIn('data-path="KelliKanvas-2.3.4.apk"', page)
            self.assertIn("1.5 KiB", page)
            self.assertIn("new URL(button.dataset.path, window.location.href)", page)
            self.assertIn("navigator.clipboard", page)
            self.assertIn('document.execCommand("copy")', page)

    def test_generation_atomically_replaces_existing_page(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "index.html"
            output.write_text("old", encoding="utf-8")

            generate_index(root)

            self.assertNotEqual(output.read_text(encoding="utf-8"), "old")
            self.assertEqual(list(root.glob(".index-*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
python -m unittest tools.tests.test_generate_apk_index -v
```

Expected: `ModuleNotFoundError: No module named 'tools.generate_apk_index'`.

- [ ] **Step 3: Implement the generator**

Create `tools/generate_apk_index.py`:

```python
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


APK_PATTERN = re.compile(
    r"^KelliKanvas-"
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"\.apk$"
)


@dataclass(frozen=True)
class ApkRelease:
    filename: str
    version: str
    size: int
    modified: datetime
    sort_key: tuple[object, ...]


def _prerelease_key(value: str | None) -> tuple[tuple[int, object], ...]:
    if value is None:
        return ()
    return tuple(
        (0, int(part)) if part.isdigit() else (1, part)
        for part in value.split(".")
    )


def _release(path: Path) -> ApkRelease | None:
    if path.is_symlink() or not path.is_file():
        return None
    match = APK_PATTERN.fullmatch(path.name)
    if match is None:
        return None
    major, minor, patch, prerelease, _build = match.groups()
    version = path.name.removeprefix("KelliKanvas-").removesuffix(".apk")
    stat = path.stat()
    return ApkRelease(
        filename=path.name,
        version=version,
        size=stat.st_size,
        modified=datetime.fromtimestamp(stat.st_mtime, timezone.utc),
        sort_key=(
            int(major),
            int(minor),
            int(patch),
            prerelease is None,
            _prerelease_key(prerelease),
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


def _size(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if amount < 1024 or unit == "GiB":
            return f"{amount:.0f} {unit}" if unit == "B" else f"{amount:.1f} {unit}"
        amount /= 1024
    raise AssertionError("unreachable")


def _cards(releases: Iterable[ApkRelease]) -> str:
    cards: list[str] = []
    for position, release in enumerate(releases):
        filename = html.escape(release.filename, quote=True)
        version = html.escape(release.version)
        latest = '<span class="badge">Latest version</span>' if position == 0 else ""
        cards.append(
            f"""
      <article class="apk-card">
        <div class="title-row"><h2>Version {version}</h2>{latest}</div>
        <p>{_size(release.size)} · {release.modified:%Y-%m-%d %H:%M UTC}</p>
        <div class="actions">
          <a class="download" href="{filename}">Download APK</a>
          <button type="button" data-path="{filename}">Copy URL</button>
        </div>
      </article>"""
        )
    return "\n".join(cards)


def render_page(releases: list[ApkRelease]) -> str:
    content = _cards(releases)
    if not releases:
        content = '<p class="empty">No APK versions have been published yet.</p>'
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>KelliKanvas APK Downloads</title>
  <style>
    :root {{ color-scheme: light dark; font-family: system-ui, sans-serif; }}
    body {{ margin: 0; background: #101318; color: #f6f7f9; }}
    main {{ width: min(44rem, calc(100% - 2rem)); margin: 2rem auto; }}
    h1 {{ margin-bottom: .25rem; }} .intro {{ color: #bdc5d1; margin-top: 0; }}
    .apk-card, .empty {{ background: #1b212b; border: 1px solid #354052;
      border-radius: 1rem; margin: 1rem 0; padding: 1rem; }}
    .title-row {{ align-items: center; display: flex; flex-wrap: wrap; gap: .75rem; }}
    h2 {{ font-size: 1.15rem; margin: 0; }} .badge {{ background: #a7f3d0;
      border-radius: 999px; color: #064e3b; font-size: .78rem; padding: .25rem .55rem; }}
    .actions {{ display: grid; gap: .65rem; grid-template-columns: 1fr 1fr; }}
    a, button {{ border: 1px solid #60a5fa; border-radius: .7rem; cursor: pointer;
      font: inherit; padding: .8rem; text-align: center; }}
    a {{ background: #2563eb; color: white; text-decoration: none; }}
    button {{ background: transparent; color: #bfdbfe; }}
    #status {{ min-height: 1.5rem; color: #a7f3d0; }}
    @media (max-width: 30rem) {{ .actions {{ grid-template-columns: 1fr; }} }}
  </style>
</head>
<body>
  <main>
    <h1>KelliKanvas APK Downloads</h1>
    <p class="intro">Copy a direct URL and send it to the TV downloader.</p>
    <p id="status" role="status" aria-live="polite"></p>
    {content}
  </main>
  <script>
    const status = document.querySelector("#status");
    async function copyUrl(button) {{
      const url = new URL(button.dataset.path, window.location.href).href;
      try {{
        if (navigator.clipboard && window.isSecureContext) {{
          await navigator.clipboard.writeText(url);
        }} else {{
          const input = document.createElement("textarea");
          input.value = url;
          input.setAttribute("readonly", "");
          input.style.position = "fixed";
          input.style.opacity = "0";
          document.body.appendChild(input);
          input.select();
          if (!document.execCommand("copy")) throw new Error("copy failed");
          input.remove();
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
    output = output or directory / "index.html"
    page = render_page(discover_apks(directory))
    temporary_name: str | None = None
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
            temporary.write(page)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_name = temporary.name
        os.replace(temporary_name, output)
        return output
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the KelliKanvas APK index")
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    generate_index(arguments.directory, arguments.output)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```powershell
python -m unittest tools.tests.test_generate_apk_index -v
```

Expected: four tests pass.

- [ ] **Step 5: Commit the generator**

```powershell
git add tools/generate_apk_index.py tools/tests/test_generate_apk_index.py
git commit -m "feat: generate mobile APK version index"
```

### Task 2: Add the locked-down nginx deployment

**Files:**
- Create: `deploy/qnap/compose.yaml`
- Create: `deploy/qnap/nginx.conf`

- [ ] **Step 1: Write the deployment configuration**

Create `deploy/qnap/compose.yaml`:

```yaml
services:
  apk-host:
    image: nginx:1.30.3-alpine@sha256:0d3b80406a13a767339fbe2f41406d6c7da727ab89cf8fae399e81f780f814d1
    container_name: kellikanvas-apk-host
    user: "101:101"
    ports:
      - "8088:8080"
    volumes:
      - /share/Public/KelliKanvas:/usr/share/nginx/html:ro
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    read_only: true
    tmpfs:
      - /tmp:size=16m,mode=1777
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/healthz"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
```

Create `deploy/qnap/nginx.conf`:

```nginx
worker_processes auto;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;

events {
    worker_connections 256;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    access_log /dev/stdout;
    client_body_temp_path /tmp/client_body;
    proxy_temp_path /tmp/proxy;
    fastcgi_temp_path /tmp/fastcgi;
    uwsgi_temp_path /tmp/uwsgi;
    scgi_temp_path /tmp/scgi;

    server {
        listen 8080;
        server_name _;
        root /usr/share/nginx/html;
        server_tokens off;
        disable_symlinks on;

        add_header X-Content-Type-Options nosniff always;
        add_header Referrer-Policy no-referrer always;

        if ($request_method !~ ^(GET|HEAD)$) {
            return 405;
        }

        location = /healthz {
            access_log off;
            default_type text/plain;
            return 200 "ok\n";
        }

        location = / {
            add_header X-Content-Type-Options nosniff always;
            add_header Referrer-Policy no-referrer always;
            add_header Cache-Control "no-store" always;
            add_header Content-Security-Policy "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'" always;
            try_files /index.html =404;
        }

        location = /index.html {
            add_header X-Content-Type-Options nosniff always;
            add_header Referrer-Policy no-referrer always;
            add_header Cache-Control "no-store" always;
            add_header Content-Security-Policy "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'" always;
            try_files $uri =404;
        }

        location ~ ^/KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$ {
            types {
                application/vnd.android.package-archive apk;
            }
            add_header X-Content-Type-Options nosniff always;
            add_header Referrer-Policy no-referrer always;
            add_header Cache-Control "public, max-age=31536000, immutable" always;
            add_header Content-Disposition "attachment" always;
            try_files $uri =404;
        }

        location / {
            return 404;
        }
    }
}
```

- [ ] **Step 2: Validate Compose and nginx locally**

Run:

```powershell
docker compose -f deploy/qnap/compose.yaml config
docker run --rm `
  -v "${PWD}/deploy/qnap/nginx.conf:/etc/nginx/nginx.conf:ro" `
  nginx:1.30.3-alpine@sha256:0d3b80406a13a767339fbe2f41406d6c7da727ab89cf8fae399e81f780f814d1 nginx -t
```

Expected: Compose prints one `apk-host` service and nginx reports
`configuration file /etc/nginx/nginx.conf test is successful`.

If local Docker is unavailable, run both validations through Container Station
after copying the files in Task 4; do not mark validation complete without one
successful environment.

- [ ] **Step 3: Commit the container configuration**

```powershell
git add deploy/qnap/compose.yaml deploy/qnap/nginx.conf
git commit -m "feat: add QNAP nginx APK host"
```

### Task 3: Document publication and operation

**Files:**
- Create: `deploy/qnap/README.md`

- [ ] **Step 1: Write the operator guide**

Create `deploy/qnap/README.md`:

````markdown
# QNAP APK host

Container Station serves `/share/Public/KelliKanvas` through nginx at
`http://darklingnas:8088`.

## Deploy

Copy this directory to `/share/Container/KelliKanvas`, create
`/share/Public/KelliKanvas`, generate the initial index, then run:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" compose -f /share/Container/KelliKanvas/compose.yaml up -d
```

## Publish an APK

Use a stable semantic version filename:

```text
KelliKanvas-1.0.0.apk
```

Copy to a temporary filename, verify SHA-256, and rename it atomically:

```sh
CONTENT=/share/Public/KelliKanvas
cp /path/to/KelliKanvas-1.0.0.apk "$CONTENT/.KelliKanvas-1.0.0.apk.tmp"
sha256sum /path/to/KelliKanvas-1.0.0.apk "$CONTENT/.KelliKanvas-1.0.0.apk.tmp"
mv "$CONTENT/.KelliKanvas-1.0.0.apk.tmp" "$CONTENT/KelliKanvas-1.0.0.apk"
```

Regenerate the index using an ephemeral Python container:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine \
  python /app/generate.py --directory /content
```

The generator writes `index.html` atomically. nginx mounts the content read-only.

## Verify

```sh
curl -fsS http://127.0.0.1:8088/healthz
curl -fsS http://127.0.0.1:8088/
curl -I http://127.0.0.1:8088/KelliKanvas-1.0.0.apk
curl -o /dev/null -sS -w '%{http_code}\n' -X POST http://127.0.0.1:8088/
```

The health endpoint and page return 200, a published APK supports HEAD, and POST
returns 405. Do not forward port 8088 through the router.

## Roll back or remove a version

Existing version URLs remain unchanged. To remove one, delete its versioned APK
and rerun the generator. To stop the site:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" compose -f /share/Container/KelliKanvas/compose.yaml down
```
````

- [ ] **Step 2: Check documentation commands and repository whitespace**

Run:

```powershell
docker compose -f deploy/qnap/compose.yaml config
git diff --check
```

Expected: both commands exit zero.

- [ ] **Step 3: Commit the operator guide**

```powershell
git add deploy/qnap/README.md
git commit -m "docs: explain QNAP APK publication"
```

### Task 4: Deploy and verify on DarklingNAS

**Files:**
- Copy: `deploy/qnap/compose.yaml` to `/share/Container/KelliKanvas/compose.yaml`
- Copy: `deploy/qnap/nginx.conf` to `/share/Container/KelliKanvas/nginx.conf`
- Copy: `tools/generate_apk_index.py` to `/share/Container/KelliKanvas/generate_apk_index.py`
- Generate: `/share/Public/KelliKanvas/index.html`

- [ ] **Step 1: Create the QNAP directories and upload deployment files**

Load `QNAP_NAS_HOST`, `QNAP_NAS_USERNAME`, and `QNAP_NAS_PASSWORD` from `.env`
without printing them. Use Posh-SSH to create the two directories and SFTP the
three files. Set configuration and generator files to mode 0644.

Expected remote layout:

```text
/share/Container/KelliKanvas/compose.yaml
/share/Container/KelliKanvas/nginx.conf
/share/Container/KelliKanvas/generate_apk_index.py
/share/Public/KelliKanvas/
```

- [ ] **Step 2: Generate the empty version page**

Run over SSH:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine \
  python /app/generate.py --directory /content
```

Expected: `/share/Public/KelliKanvas/index.html` exists and contains
`No APK versions have been published yet.`

- [ ] **Step 3: Validate and start nginx**

Run over SSH:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml config
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml up -d
"$CS/bin/docker" inspect \
  --format '{{.State.Health.Status}}' kellikanvas-apk-host
```

Expected: Compose validation succeeds, the image is pulled if absent, and health
becomes `healthy`.

- [ ] **Step 4: Verify HTTP policy from the workstation**

Run:

```powershell
$page = Invoke-WebRequest http://darklingnas:8088/ -UseBasicParsing
$health = Invoke-WebRequest http://darklingnas:8088/healthz -UseBasicParsing
$postStatus = try {
    Invoke-WebRequest http://darklingnas:8088/ -Method Post -UseBasicParsing
    200
} catch {
    [int]$_.Exception.Response.StatusCode
}

$page.StatusCode
$page.Content -match "No APK versions have been published yet"
$health.StatusCode
$postStatus
```

Expected output:

```text
200
True
200
405
```

- [ ] **Step 5: Test a fixture APK and remove it**

Create a harmless fixture named `KelliKanvas-0.0.0-test.1.apk`, regenerate the
page, and verify it appears with a copy button and a reachable direct URL. Delete
the fixture, regenerate the page, and verify the empty state returns. This file
is not an installable APK and must not remain on the NAS.

- [ ] **Step 6: Verify restart behavior and final state**

Run over SSH:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" restart kellikanvas-apk-host
"$CS/bin/docker" inspect \
  --format '{{.State.Health.Status}}' kellikanvas-apk-host
```

Wait until the health state is `healthy`, then rerun the workstation HTTP checks.

- [ ] **Step 7: Run final repository verification**

```powershell
python -m unittest tools.tests.test_generate_apk_index -v
git diff --check
git status --short
```

Expected: all tests pass, whitespace checks pass, and only intentional changes
remain.

