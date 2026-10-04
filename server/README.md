# KelliKanvas account server

ASP.NET Core 10, PostgreSQL and WorkOS AuthKit. The browser portal and account API run in one container. This follows `../kelli.photo`'s .NET 10/external Postgres deployment and `../ncontxt`'s AuthKit plus host nginx/Portainer layout. The application is independent of their databases and authentication cookies.

## What works

The TV requests a pairing code from this server. Its QR code opens the public HTTPS `/pair` page. The phone signs in through WorkOS, checks the TV name and code, and explicitly approves the screen. The TV polls using a separate private secret, receives an opaque device session once, and stores it in its encrypted Android vault. The phone can use cellular data or a different Wi-Fi network.

The portal saves themes, slide duration, clock/photo overlays and connections. Android sync covers every existing display preference, reduced motion, selected photo roots and file-type filters. NAS, generic connectors and existing Google grants can be backed up and restored. Local/USB URI permissions and DLNA discoveries remain device-local. The server stores connection payloads with AES-256-GCM, bound to the account and connection ID. The encryption key stays in the server environment. Database session and device polling tokens are stored as hashes. Browser connection lists return metadata only.

The TV connects directly to its photo providers and NAS; OVH does not need access to the home NAS. The server does not fetch arbitrary user-supplied server URLs. A remote TV still needs its own network/Tailscale route to a private NAS.

## Deploy to OVH / Portainer

The reference Ncontxt deployment uses host nginx, Docker/Portainer on OVH, and loopback frontend ports. This stack follows that layout and defaults to **https://kanvas.kelli.photo**, with loopback port **6080**. Change the hostname in the variables and nginx example if needed.

1. Create DNS for the chosen hostname pointing to the OVH server. Configure its TLS certificate through the existing host nginx/Certbot setup. The HTTPS example is `nginx.conf`; bootstrap an HTTP ACME virtual host before referencing certificate files that do not yet exist.
2. Create **kellikanvas** and **kellikanvas_app** on the existing PostgreSQL instance. `provision-database.sql` is an optional psql script; it takes `KANVAS_DB_PASSWORD` from the process environment and does not rotate existing roles. Do not use the KelliPhoto or Ncontxt application databases. The server initializes only its `kanvas` schema, under a startup advisory lock.
3. In WorkOS, configure an AuthKit application/environment and allow the exact callback **https://kanvas.kelli.photo/api/auth/callback**. Enable the desired sign-in methods. Set `WORKOS_CLIENT_ID` and `WORKOS_API_KEY` on the server only. Optional `WORKOS_ORGANIZATION_ID` selects a WorkOS organization; optional `ALLOWED_EMAILS` limits the deployment to specific verified emails.
4. Generate `CONNECTION_ENCRYPTION_KEY` with `openssl rand -base64 32`. Keep it with encrypted backups; changing or losing it makes existing connection payloads unreadable. Configure the values described in `.env.example` through Portainer variables or an ignored `.env` file.
5. Attach the stack to the **existing Postgres Docker network** using `POSTGRES_NETWORK`. Set `DATABASE_CONNECTION` to the database's internal hostname/port and dedicated KelliKanvas credentials. For a remote Postgres server, use certificate-validated TLS (`SSL Mode=VerifyFull`) and the appropriate network route.
6. Build on the Docker host with `docker compose -f server/compose.yaml --env-file server/.env build`, then `docker compose -f server/compose.yaml --env-file server/.env up -d`. In a Portainer Git stack, use compose path `server/compose.yaml`. This requires the current server source to be present in the repository; the release source bundle can also be copied to the host and built locally. An image-based Portainer stack can use the same compose file after publishing an image and setting `SERVER_IMAGE`; no image has been pushed by this change.
7. Install the nginx virtual host, run `nginx -t`, and reload nginx. Check `/health/live` and `/health/ready` through HTTPS. Keep port 6080 bound to loopback. Set `TRUSTED_PROXY_CIDRS` only to the ingress Docker subnet if forwarded client IPs are needed for rate limiting; forwarded headers from other addresses are ignored.
8. Install the new Android APK. Open **System → Account and phone sign-in**, or the hosted-account action in NAS/connector setup. Scan the QR and approve on the phone. On a new account choose **Start a new account with this TV's setup**; on an existing account choose **Restore account to this TV**. Subsequent foreground sync runs every 30 seconds. Android still permits manual server-origin entry for other deployments.

The production server was deployed to OVH on October 4, 2026. Its WorkOS callback was registered by the owner; the deployment reuses the same WorkOS client as NContxt. See [the deployment record](DEPLOYMENT-OVH.md) for actual host paths, database/network configuration and maintenance commands. Real WorkOS sign-in and authenticated account access passed; physical phone-to-TV pairing remains to be checked on the Hisense.

## Account and connection behavior

The phone can add NAS/SMB, Jellyfin, Emby, Plex, Immich, supported WebDAV services, Dropbox, OneDrive, Box, S3 and public Flickr connections. The default selected folder is the service root (NAS share or optional relative NAS folder). The TV verifies connectivity when browsing/indexing photos. Select narrower folders on the TV when desired. The portal can update a saved password/token without changing selected folders. Advanced cloud services still need valid provider tokens; account pairing is distinct from provider OAuth. Existing Google grants can be restored, but their original application/device and token restrictions still apply; Google Photos currently requires the separately configured Android build. This server does not yet broker Google OAuth.

Settings and connections are account-wide. A foreground TV pushes local changes with an expected revision and otherwise pulls newer account revisions. A stale write returns 409 without overwriting the cloud or local setup. Account setup offers explicit restore/save after a conflict. Review local changes before choosing restore. Screensavers retain the local setup offline; they do not poll the account server while the main activity is closed.

Browser sessions last 12 hours; device sessions last 30 days and must then be linked again. WorkOS establishes identity; these application sessions have their own lifetime and revocation. Removing a screen in the portal immediately invalidates its API sessions. A remote revocation stops future server access; local/offline credential copies remain until disconnected or app data is cleared. Disconnecting from Android revokes the TV and removes account-managed local credentials and selected roots. Other local sources remain.

## Security and operations

OAuth uses PKCE, a random state bound to an encrypted ten-minute HttpOnly cookie, and an exact configured callback origin. Only verified WorkOS emails are accepted. Browser cookies are Secure in production; mutations require a session-bound CSRF token and matching Origin. Device sessions cannot use browser-account endpoints. Pairing requires authenticated approval, expires in ten minutes, and delivers credentials once under a database row lock. Pairing QR URLs contain no device secrets. Requests are rate-limited and capped at 2 MiB. Responses disable caching and apply CSP, frame restrictions and referrer protection. nginx request logging is disabled in the example to avoid logging OAuth callback codes. Request/response bodies and credential-containing exceptions are not logged.

Back up the dedicated database and the encryption key separately. Before upgrading, retain a database backup and the previous image. Current schema initialization is additive and idempotent; future schema changes should use versioned migrations. The app checks database readiness separately from process liveness. Do not publish `.env` files or bake secrets into container images/APKs.

## Local validation

```powershell
docker compose -f server/compose.test.yaml up -d --wait
dotnet test server/KelliKanvas.Server.Tests/KelliKanvas.Server.Tests.csproj
docker build -f server/Dockerfile -t kellikanvas-server:preview server
docker compose -f server/compose.test.yaml down
```

Tests use an isolated ephemeral Postgres on loopback port 16439. They cover restart persistence, encryption, account isolation, atomic pairing/replay/expiry/cancellation, revocation, optimistic concurrency, OAuth state/PKCE wiring, browser CSRF and API scope. HTTP auth tests stub WorkOS; real AuthKit sign-in subsequently passed on the live deployment. Phone-to-Hisense pairing remains a hardware check. API implementation follows [WorkOS AuthKit authorization](https://workos.com/docs/reference/authkit/authentication/get-authorization-url) and [code authentication](https://workos.com/docs/reference/authkit/authentication).
