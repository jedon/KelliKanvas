# Phone slideshow setup and updates

Install **KelliKanvas-1.0.22.apk** once on the TV. Versions through 1.0.21 were
built without an update verification key, so they cannot bootstrap this update.
The new build keeps the existing signing certificate and preserves app data.

1. On the TV, open **Account setup** and scan its QR code with your phone.
2. Sign in at **https://kanvas.kelli.photo** and approve the matching TV code.
3. Add **Immich** with `http://darklingnas:2283` and Kelli's own API key.
   In Immich, create the key under her account settings with `album.read`,
   `asset.read`, and `asset.download` permissions. Do not use an administrator's key.
4. Choose **Choose photos**, open her **Kanvas** album, and add it to the slideshow.
5. **Save slideshow**. The linked TV receives the selection within 30 seconds
   while Kanvas is open. The default checkbox makes account selections the TV's
   slideshow; clear it to retain additional folders selected locally on that TV.

Kelli can organize photos in Immich and add or remove them from that album
without selecting the album again. For an actual NAS directory, add **NAS / SMB**,
enter the share `Kelli`, then browse to `Digital Photos` and the desired subfolder.
The phone can use mobile data: a linked TV performs LAN directory browsing.

Connections may be saved before choosing photos. Their stored credentials are
encrypted and never returned to the phone account page. Browsing jobs expire in
two minutes and return folder names, IDs and photo counts only.

Future releases use **v1.0.22** style tags and **KelliKanvas-1.0.22.apk** style
filenames. On the TV, use **System → App updates → Check for updates**. Kanvas
checks authenticated metadata, download size, SHA-256, app ID and APK certificate,
then opens Android's installer. Allow installation from Kanvas if prompted;
returning to the app continues with the downloaded APK. Confirm the Android
installation prompt. Startup checks show availability without installing automatically.

The signed GitHub workflow isolates APK signing and metadata signing from
Gradle. Signing keys must be explicitly approved for storage in GitHub Actions
secrets. Public builds contain no preset NAS or Google client credentials.

The OVH update mirror checks stable GitHub releases every five minutes. It
verifies the signed metadata before downloading bounded assets, rejects older
sequences and versions, checks immutable filenames and hashes, and publishes
the control envelope last. It runs as a dedicated user with write access only
to its staging and public update directories. Only its public verification key
is deployed to the server.
