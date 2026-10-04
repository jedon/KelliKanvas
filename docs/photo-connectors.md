# Photo connectors

Open **Collection → Browse all connectors**. Search for a service, then use **Set up using your phone**. Scan the TV's QR code, enter the server details and login or API token, and confirm the connection on the TV. Browse to an album or folder and select **Add this photo folder**. Each source can include subfolders and coexist with the other sources.

The temporary phone form runs on the TV's local IPv4 address. Use the same trusted home network: the form uses HTTP, a ten-minute expiry and a one-use token. The TV validates the request's Host, Origin and pairing token. Passwords and tokens are hidden on screen and saved only in the encrypted Android credential vault after you select a folder. No NAS or connector credentials are embedded in APKs or written to the photo catalog. Removing a source's last selected folder removes its vault entry.

## Implemented providers

- **Jellyfin and Emby:** server URL plus account username/password. The app authenticates, browses folders and photo albums, pages through photos, and streams original image files. Account access restrictions remain enforced by the server. Reverse proxy base paths are supported.
- **Immich:** server URL and API key with `album.read`, `asset.read` and `asset.download`. Browse albums or All photos; album contents use metadata search, compatible with the v3 removal of `album.assets`. Videos are filtered out. Original image files are streamed.
- **Plex:** Plex Media Server URL and an `X-Plex-Token` with photo-library access. Browse photo libraries/albums and stream image media parts. Movie and TV libraries are excluded.
- **WebDAV:** complete photo-folder URL, username and app password. Branded setup entries cover **Nextcloud, ownCloud, Synology WebDAV Server, Seafile SeafDAV and PhotoPrism originals**. Generic WebDAV covers compatible QNAP and other file servers. These are WebDAV integrations, not the separate Synology Photos or PhotoPrism album APIs. WebDAV must be enabled on the server. Only successful direct-child properties are listed; external hosts, paths outside the selected endpoint and XML entity declarations are rejected.
- **Dropbox:** runtime access token with `files.metadata.read` and `files.content.read`. Folder listing, continuation and original download use Dropbox v2. Tokens expire; select Reconnect to enter a renewed token.
- **OneDrive:** runtime Microsoft Graph access token with `Files.Read`. Folder browsing uses Graph v1.0. Downloads use short-lived preauthorized URLs without sending the Graph bearer token to the file host. Tokens expire and currently require manual renewal.
- **Box:** runtime Box access token with read access to the selected folders. Marker-based folder browsing and original download links. Tokens expire and currently require manual renewal.
- **S3 compatible storage:** bucket endpoint, access key ID and secret access key. Read-only ListObjectsV2 and GetObject, with AWS Signature Version 4. Includes Amazon S3 and compatible MinIO/storage servers. AWS endpoint names determine the region; custom endpoints use `us-east-1`. Use the bucket URL, not a prefix inside the bucket; choose the prefix in the folder browser. Temporary session credentials, S3 Express and custom regions for non-AWS endpoints are not implemented.
- **Flickr public albums:** user ID (NSID) and an API application key. Public album browsing and the largest available image rendition. Private-album OAuth is not implemented. Original dimensions depend on Flickr's access and download permissions.

Existing **Google Drive, Google Photos Ambient, SMB/NAS, DLNA, local folders and USB** remain available in Collection. Existing Google Photos behavior and API-specific limits are described in [google-sources.md](google-sources.md).

Dropbox, OneDrive and Box are currently advanced token connections. They do not yet have built-in browser OAuth consent or automatic refresh. They require tokens from an appropriately registered provider application; entering an account password will not work. The phone QR code securely pairs the local form with the TV but is not provider OAuth.

## Coverage boundaries

There is no universal API for every photo service. This release implements the public protocols listed above and does not claim direct Apple iCloud Photos, Amazon Photos, Facebook/Instagram personal-library, private Flickr, SmugMug, 500px, FTP or SFTP access. A provider-compatible sync/export into an existing supported folder can make those photos available. Additional integrations require supported APIs and the corresponding application registration/access grants; the catalog does not show nonfunctional provider buttons.

## API references

Implementation was checked against primary documentation and source:

- [Jellyfin authentication](https://kotlin-sdk.jellyfin.org/guide/authentication.html) and [Items controller](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/ItemsController.cs).
- [Immich v3 migration](https://immich.app/blog/v3-migration), [metadata search controller](https://github.com/immich-app/immich/blob/main/server/src/controllers/search.controller.ts) and [asset media controller](https://github.com/immich-app/immich/blob/main/server/src/controllers/asset-media.controller.ts).
- [Plex server API](https://developer.plex.tv/pms/) and [Plex server URL commands](https://support.plex.tv/articles/201638786-plex-media-server-url-commands/).
- [Nextcloud WebDAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html) and [PhotoPrism WebDAV](https://docs.photoprism.app/user-guide/sync/webdav/).
- [Dropbox HTTP API](https://www.dropbox.com/developers/documentation/http/documentation).
- [Microsoft Graph folder children](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0) and [download content](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0).
- [Box folder items](https://developer.box.com/reference/get-folders-id-items) and [download content](https://developer.box.com/reference/get-files-id-content).
- [S3 ListObjectsV2](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html) and [Signature Version 4 examples](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html).
- [Flickr public album listing](https://www.flickr.com/services/api/flickr.photosets.getList.html) and [album photos](https://www.flickr.com/services/api/flickr.photosets.getPhotos.html).

## Validation

Provider tests use local HTTP fixtures, not production accounts. They exercise authentication, pagination, filters, original image streaming, credential-origin boundaries, cancellation, XML entity rejection and the published AWS signature test vector. Native Android tests exercise searchable TV catalog controls and saved connections. Phone form browser checks cover valid submission, expiry, missing tokens, hidden passwords and clearing submitted values. Live provider servers and the Hisense TV still need device verification.

Public distribution builds use `-PpublicDistribution=true`, omitting the Google TV OAuth client secret. Local configured builds can enable Google Photos. Both build types always require runtime NAS and connector credentials.
