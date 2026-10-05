# Phone slideshow setup and updates

Install **KelliKanvas-1.0.25.apk** on the TV. Versions 1.0.22 and newer can use
**System → App updates → Check for updates**. Versions through 1.0.21 were
built without an update verification key, so they cannot bootstrap this update.
The new build keeps the existing signing certificate and preserves app data.
Version 1.0.24 draws photos in the app's composed window to avoid black slides
during navigation transitions, and uses the controller's Back button throughout.
Version 1.0.25 preloads the next photo while the current one stays visible and
uses the saved transition type and duration in both the app and screensaver.
Slow or unreadable photos retain the current frame; loading is shown only before
the first photo. Random transitions choose crossfade, slides, or pan/zoom;
an explicitly selected Fade Through Black retains its intentional dark phase.

1. On the TV, open **Account setup** and scan its QR code with your phone.
2. Sign in at **https://kanvas.kelli.photo** and approve the matching TV code.
3. Add **Immich**, select the linked TV, and tap **Find Immich on my network**.
   A single discovered server is selected automatically; when several are found,
   choose DarklingNAS. The selected address is supplied automatically, so there is
   no URL to type. Use **Enter a different server address** for manual setup.
   Then enter Kelli's Immich email and password. The TV
   signs in locally and creates a restricted key for her photos. If Immich requires
   changing her initial password, do that in Immich first. **API key · Advanced**
   remains available for an existing key with `album.read`, `asset.read`,
   `asset.download`, and `asset.view` permissions.
4. Choose **Choose photos**, open her **Kanvas** album, and add it to the slideshow.
5. **Save slideshow**. The linked TV receives the selection within 30 seconds
   while Kanvas is open. The default checkbox makes account selections the TV's
   slideshow; clear it to retain additional folders selected locally on that TV.

Kelli can organize photos in Immich and add or remove them from that album
without selecting the album again. For an actual NAS directory, add **NAS / SMB**,
enter the share `Kelli`, then browse to `Digital Photos` and the desired subfolder.
The phone can use mobile data: a linked TV performs LAN directory browsing.
Discovery also runs on the linked TV: keep Kanvas open and connect the TV to
the NAS's network. It checks saved hosts, common Immich names, and port 2283 on
one private IPv4 subnet. Enter `http://darklingnas:2283` manually if discovery
does not find it, or use a manual address for a different port or network.

Connections may be saved before choosing photos. Their stored credentials are
encrypted and never returned to the phone account page. Browsing jobs expire in
two minutes and return folder names, IDs and photo counts only.
Immich passwords are encrypted while queued, then discarded after completion;
the temporary login session is logged out. Only the restricted key is retained.

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
