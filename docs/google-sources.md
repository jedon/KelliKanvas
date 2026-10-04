# Google photo sources

KelliKanvas can select image folders from Google Drive and display Google Photos albums through Google's Ambient API. Both sources appear in Collection and use the existing slideshow and Android screensaver. They can be combined with NAS, DLNA, and local folders.

The Drive REST integration follows the readonly folder browsing approach in `G:/Inertia/NexusWebClient/src/features/files/services/google-drive.service.ts`. KelliKanvas uses native Android authorization in place of that project's browser popup. The existing web client's credentials and user grants are not copied.

## Enable Google Drive

1. Select a Google Cloud project, enable the Google Drive API, and configure the OAuth consent screen and testing audience.
2. Create an **Android** OAuth client for package `com.jedon.kellikanvas` and the SHA-1 of the certificate signing the installed APK. Run `./gradlew.bat :app:signingReport` to obtain local fingerprints. Register the production certificate separately when distributing a signed release.
3. Add the `https://www.googleapis.com/auth/drive.readonly` scope to the consent configuration. This allows browsing existing folders and reading their images. Publishing with this scope may require Google's verification process.
4. Open Collection → Connect Drive → Continue with Google. Select a folder, choose whether to include subfolders, and select Add this folder. A Drive folder link or ID can also open a shared folder that the authorized account can access.

For the October 3, 2026 public debug preview, register Android package `com.jedon.kellikanvas` with SHA-1 `B0:F0:D8:6F:F4:F7:72:2F:7A:AA:9B:90:BB:7C:39:4E:BC:01:3C:FA`. This fingerprint belongs to the local debug certificate; production releases require their own certificate registration.

Drive uses `AuthorizationClient` from Google Play services. The Android client is identified by package and signing certificate; this direct API flow does not require an embedded web client secret or a backend. Access grants are renewed through Play services and bound to the selected account. See [Android authorization setup](https://developer.android.com/identity/authorization) and [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

The folder listing is paginated, includes shared-drive support, and excludes non-image files. Photos are streamed from Drive in their original format. Reconnect returns to Google consent and permits selecting another folder for the same account.

## Enable Google Photos

Use the **Google Photos Ambient API**, which supports TVs and photo frames. Configure a **TVs and Limited Input devices** OAuth client in the Google Cloud project with the Ambient API enabled. This client is separate from the Android Drive client and any browser OAuth client. Public Ambient apps require Google's review. Follow [Google's Ambient configuration guide](https://developers.google.com/photos/ambient/guides/configure-your-app).

Set these build inputs as environment variables or in the ignored `local.properties` or `.env` file, then rebuild:

```properties
KELLIKANVAS_GOOGLE_TV_CLIENT_ID=your-tv-client-id
KELLIKANVAS_GOOGLE_TV_CLIENT_SECRET=your-tv-client-secret
```

These values identify Google's limited-input OAuth client and are packaged into the Android application as required by that flow. They must not be confidential web-server credentials. Never commit actual values. Changing this client ID requires reconnecting Photos because Ambient devices belong to the client that created them.

The configured Google Photos preview successfully requested a device code and received the expected `authorization_pending` response when checking its client secret. No user account was authorized during that check. This verifies the OAuth client pair, but API enablement, the consent testing audience, and album access still need to be checked during real sign-in.

Open Collection → Connect Photos → Sign in using your phone. Scan the QR code with your phone camera and enter the displayed code if Google requests it. After sign-in, Google opens album selection on the phone. The app passes one UUID request ID in OAuth state and reuses it when registering the Ambient device, following Google's [single-QR flow](https://developers.google.com/photos/ambient/guides/configure-your-app#streamlined-authentication-flow-for-the-ambient-api). The TV also offers the album-selection QR as a fallback if the redirect does not open.

The screen shows a code expiry countdown and a typed URL/code fallback. If Google supplies a complete verification URL, the QR uses it directly. Start again cancels the current sign-in attempt; expired codes are replaced by a restart prompt. Once Google confirms album selection, select Add Google Photos. Missing build configuration disables sign-in and explains the next step.

Photos uses scope `https://www.googleapis.com/auth/photosambient.mediaitems` and the official device-code flow, including pending requests, slow-down responses, expiry, and renewable access. Unfinished device selections are saved and resumed after restart. Authorization polling stops when setup is cancelled.

The current implementation requests the curated Ambient feed across selected albums, up to 100 images per refresh. It shares an in-memory snapshot between gallery preview and playback, refreshes during active playback every ten minutes, and requests images sized for a 4K display without cropping. It does not browse the entire Google Photos library or play videos. Google's [media list reference](https://developers.google.com/photos/ambient/reference/rest/v1/mediaItems/list) describes curated feeds and the 240-request daily limit per device.

## Saved access and removal

Google access grants, refresh grants, and the Drive account name are stored through the app's encrypted credential vault. Room stores only connection metadata and selected folder references. Pairing codes and temporary photo URLs remain in memory. Google response bodies and grants are excluded from diagnostic messages.

Each saved Google folder has Reconnect and Remove actions. Removing the last folder for a connection clears its stored grant and metadata. Photos additionally attempts to delete its Ambient device. If Google is unreachable during deletion, local removal still completes; the device can be removed through Google Photos settings. Removing a Drive source does not revoke access for other connections using that Cloud project. Account-wide revocation is available through Google Account's third-party access settings.

Catalog migration 4 → 5 adds Google connection metadata without deleting existing collections or folders. Long-running Photos refreshes preserve the current photo when possible and retain pause/play state. Failed refreshes retain the current playlist and retry on the next interval.

## Validation and remaining live checks

Automated checks cover readonly folder requests, paging, expired/rejected grants, Photos pairing and renewal, matched OAuth/device request IDs, request/body cancellation, URL validation, catalog migration and removal, and native TV/phone setup layouts. QR codes are decoded from the rendered TV and phone screenshots to verify their destinations. Network tests use a local mock server; they do not authenticate a real Google account.

Live sign-in requires the Cloud clients above. Google consent, album selection, grant renewal across app restarts, and actual Hisense Google Play services behavior must be checked on a configured physical TV. Drive's readonly scope is not supported by Google's generic TV device-code flow, so Drive deliberately uses native Android authorization. A VIDAA-only TV cannot run this Android APK.
