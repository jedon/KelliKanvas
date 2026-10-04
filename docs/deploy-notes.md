# Deploy notes

## Household NAS (SMB) phone setup

Photo hosts/shares/paths are baked into source (`HouseholdNasDefaults`).
NAS credentials are entered at runtime. Open **Collection → Connect NAS → Enter login using your phone**, scan the TV's QR code, enter the login on your phone, and select **Connect NAS** on the TV. A direct-entry form is also available on the device.

At runtime, passwords are stored in the Android Keystore-backed credential vault
(`kellikanvas_source_credentials`). Room `smb_connections` stores host/share/username only.

The TV hosts a temporary HTTP page on its local IPv4 address. Use a trusted home Wi-Fi network: this local HTTP connection is not TLS. The link has a random 256-bit token in its fragment, expires after ten minutes, accepts one login, and closes when the setup screen is cancelled. Requests must match the TV's Host, Origin, and pairing token. Request bodies, passwords, and pairing links are not logged; the page loads no third-party resources, sets no cookies, and stores no credentials in browser storage. Passwords are wiped from pending buffers on discard or cancellation. The TV asks for confirmation before connecting and stores the password in its encrypted vault only after a successful connection.

The APK always leaves the legacy household username/password BuildConfig fields empty, even when NAS environment variables or Actions secrets exist. NAS credentials are no longer build inputs. Previously saved SMB sources continue to use their encrypted vault entries. Re-entering a login updates an existing matching NAS source without duplicating selected folders. No private build repository is required.

## Probe-proven SMB photo path (LAN)

- Host: `192.168.68.62` (port `445`)
- Primary share: `Kelli`
- Example roots: `Digital Photos`, `Cell Phone Photos`, `Photos for frame TV and printing`
- Also available: `Multimedia\Canvas`, `Public\Media\Pictures`

## Hosted account preview

The account server is live at `https://kanvas.kelli.photo`, deployed to OVH on October 4, 2026. The WorkOS callback is `https://kanvas.kelli.photo/api/auth/callback`. Public HTTPS, real WorkOS sign-in, authenticated account access, database readiness and pairing creation/poll/cancel passed. The authenticated session survived a container restart. Physical phone-to-Hisense pairing remains to be checked on the TV. Public APKs omit embedded Google/NAS credentials.

See [the deployment record](../server/DEPLOYMENT-OVH.md) for the running stack, backups and maintenance commands, and [the server guide](../server/README.md) for configuration and account behavior.

## NAS slideshow fix — 1.0.21

The NAS handshake, authentication and file open now run on the IO dispatcher. Previously the gallery preview opened photos on IO, while slideshow playback called the same blocking SMB open from the Compose/UI dispatcher. That difference could prevent playback despite a successful connection and thumbnail. The shared backend fix also applies to system screensavers. A stream opened while playback is cancelled is closed before it can be lost during coroutine handoff.

A regression using the actual SMB client and a deliberately stalled local negotiation reproduced the old UI-thread block and passed with the fix. All 12 SMB and 23 slideshow tests passed, along with formatting and Android lint. The public APK is version 1.0.21 (22); its embedded NAS/Google credential fields are empty. Install it over the previous preview to retain app data. Physical Hisense playback still needs confirmation on the TV.
