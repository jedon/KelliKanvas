package com.jedon.kellikanvas.source.connected

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Test
import java.time.Instant

class PhotoConnectorsTest {
    @Test fun `S3 signature matches the published AWS list objects example`() {
        // These are AWS's published documentation fixtures, not account credentials.
        val headers = s3Signature("https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J".toHttpUrl(), "GET", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", Instant.parse("2013-05-24T00:00:00Z"))
        assertThat(headers.getValue("Authorization")).endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7")
    }
    private val id = SourceProfileId("connector-test")
    private val client = OkHttpClient()
    private fun config(server: MockWebServer, provider: PhotoConnector) = ConnectorConfiguration(provider, server.url("/").toString(), "fixture-user", "fixture-secret")
    private fun MockWebServer.json(body: String) = enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    private suspend fun original(source: ConnectedPhotoSource, photo: SourceEntry.Photo): String = source.open(photo.asset).use {
        val buffer = Buffer()
        while (it.read(buffer, 16) != -1L) { }
        buffer.readUtf8()
    }

    @Test fun `Jellyfin and Emby authenticate once then page photos and stream originals`() = runBlocking {
        for (provider in listOf(PhotoConnector.JELLYFIN, PhotoConnector.EMBY)) {
            MockWebServer().use { server ->
                server.json("""{"AccessToken":"fixture-token","User":{"Id":"viewer"}}""")
                server.json("""{"TotalRecordCount":2,"Items":[{"Id":"photo","Name":"Portrait","Type":"Photo","MediaSources":[{"Path":"/photos/portrait.png"}]}]}""")
                server.json("""{"TotalRecordCount":2,"Items":[{"Id":"album","Name":"Family","Type":"PhotoAlbum"}]}""")
                server.enqueue(MockResponse().setBody("original"))
                val source = connectedPhotoSource(id, config(server, provider), client)
                val first = source.listChildren(source.rootFolder, null, 1)
                val photo = first.items.single() as SourceEntry.Photo
                assertThat(photo.asset.mimeType).isEqualTo("image/png")
                val auth = server.takeRequest()
                assertThat(auth.path).isEqualTo("/Users/AuthenticateByName")
                assertThat(Json.parseToJsonElement(auth.body.readUtf8()).jsonObject["Pw"]!!.jsonPrimitive.content).isEqualTo("fixture-secret")
                val listing = server.takeRequest()
                assertThat(listing.getHeader("X-Emby-Token")).isEqualTo("fixture-token")
                assertThat(listing.requestUrl!!.queryParameter("UserId")).isEqualTo("viewer")
                assertThat(source.listChildren(source.rootFolder, first.nextCursor, 1).items.single()).isInstanceOf(SourceEntry.Folder::class.java)
                assertThat(server.takeRequest().requestUrl!!.queryParameter("StartIndex")).isEqualTo("1")
                assertThat(original(source, photo)).isEqualTo("original")
                assertThat(server.takeRequest().path).isEqualTo("/Items/photo/Download")
                assertThat(server.requestCount).isEqualTo(4)
            }
        }
    }

    @Test fun `Immich searches album contents with v3 API and ignores videos`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""[{"id":"album-id","albumName":"Wedding"}]""")
            server.json("""{"assets":{"items":[{"id":"p1","type":"IMAGE","originalFileName":"wedding.jpg","originalMimeType":"image/jpeg"},{"id":"v1","type":"VIDEO","originalFileName":"clip.mp4"}],"nextPage":"2"}}""")
            server.json("""{"assets":{"items":[],"nextPage":null}}""")
            server.enqueue(MockResponse().setBody("original"))
            val source = ImmichPhotoSource(id, config(server, PhotoConnector.IMMICH), client)
            val album = source.listChildren(source.rootFolder, null).items[1] as SourceEntry.Folder
            assertThat(server.takeRequest().path).isEqualTo("/api/albums")
            val first = source.listChildren(album.ref, null, 25)
            assertThat(first.items).hasSize(1)
            val search = server.takeRequest()
            assertThat(search.path).isEqualTo("/api/search/metadata")
            assertThat(search.getHeader("x-api-key")).isEqualTo("fixture-secret")
            assertThat(search.body.readUtf8()).contains("\"albumIds\":[\"album-id\"]")
            assertThat(source.listChildren(album.ref, first.nextCursor, 25).nextCursor).isNull()
            assertThat(server.takeRequest().body.readUtf8()).contains("\"page\":2")
            assertThat(original(source, first.items.single() as SourceEntry.Photo)).isEqualTo("original")
            assertThat(server.takeRequest().path).isEqualTo("/api/assets/p1/original")
        }
    }

    @Test fun `Plex filters libraries and uses original media parts`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Movies"},{"key":"2","type":"photo","title":"Photography"}]}}""")
            server.json("""{"MediaContainer":{"totalSize":1,"Metadata":[{"ratingKey":"p1","type":"photo","title":"Bat","Media":[{"Part":[{"key":"/library/parts/10/file.jpg","file":"bat.jpg","size":8}]}]}]}}""")
            server.enqueue(MockResponse().setBody("original"))
            val source = PlexPhotoSource(id, config(server, PhotoConnector.PLEX), client)
            val root = source.listChildren(source.rootFolder, null).items.single() as SourceEntry.Folder
            assertThat(root.name).isEqualTo("Photography")
            assertThat(server.takeRequest().getHeader("X-Plex-Token")).isEqualTo("fixture-secret")
            val photo = source.listChildren(root.ref, null).items.single() as SourceEntry.Photo
            assertThat(server.takeRequest().path).startsWith("/library/sections/2/all?")
            assertThat(original(source, photo)).isEqualTo("original")
            assertThat(server.takeRequest().path).isEqualTo("/library/parts/10/file.jpg")
        }
    }

    @Test fun `WebDAV includes direct photo children and rejects external paths and failed properties`() = runBlocking {
        MockWebServer().use { server ->
            val config = ConnectorConfiguration(PhotoConnector.NEXTCLOUD, server.url("/dav/photos/").toString(), "fixture-user", "fixture-secret")
            val source = WebDavPhotoSource(id, config, client)
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/dav/photos/</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/photos/portrait%20one.png</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:getcontenttype>image/png</d:getcontenttype><d:getcontentlength>8</d:getcontentlength></d:prop></d:propstat></d:response>
                <d:response><d:href>https://evil.example/dav/photos/private.png</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:getcontenttype>image/png</d:getcontenttype></d:prop></d:propstat></d:response>
                <d:response><d:href>/dav/photos/failed.png</d:href><d:propstat><d:status>HTTP/1.1 403 Forbidden</d:status><d:prop><d:getcontenttype>image/png</d:getcontenttype></d:prop></d:propstat></d:response>
                </d:multistatus>""",
                ),
            )
            val first = source.listChildren(source.rootFolder, null)
            val photo = first.items.single() as SourceEntry.Photo
            assertThat(photo.name).isEqualTo("portrait one.png")
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("PROPFIND")
            assertThat(request.getHeader("Depth")).isEqualTo("1")
            assertThat(request.getHeader("Authorization")).startsWith("Basic ")
            server.enqueue(MockResponse().setBody("original"))
            assertThat(original(source, photo)).isEqualTo("original")
            assertThat(server.takeRequest().path).isEqualTo("/dav/photos/portrait%20one.png")
            assertThat(runCatching { source.listChildren(FolderRef(id, ProviderObjectId("/outside/")), null) }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM "file:///no-read">]><d:multistatus xmlns:d="DAV:">&x;</d:multistatus>"""))
            assertThat(runCatching { source.listChildren(source.rootFolder, null) }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
        }
    }

    @Test fun `Dropbox pages with continuation and posts download arguments in headers`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"entries":[{".tag":"file","id":"id:photo","name":"portrait.jpg","size":8},{".tag":"file","id":"id:doc","name":"notes.txt"}],"has_more":true,"cursor":"next-page"}""")
            server.json("""{"entries":[],"has_more":false,"cursor":"last"}""")
            server.enqueue(MockResponse().setBody("original"))
            val source = DropboxPhotoSource(id, config(server, PhotoConnector.DROPBOX), client, server.url("/2/"), server.url("/content/2/"))
            val first = source.listChildren(source.rootFolder, null, 25)
            assertThat(first.items).hasSize(1)
            assertThat(server.takeRequest().path).isEqualTo("/2/files/list_folder")
            source.listChildren(source.rootFolder, first.nextCursor, 25)
            val more = server.takeRequest()
            assertThat(more.path).isEqualTo("/2/files/list_folder/continue")
            assertThat(more.body.readUtf8()).isEqualTo("""{"cursor":"next-page"}""")
            assertThat(original(source, first.items.single() as SourceEntry.Photo)).isEqualTo("original")
            val download = server.takeRequest()
            assertThat(download.method).isEqualTo("POST")
            assertThat(download.getHeader("Dropbox-API-Arg")).isEqualTo("""{"path":"id:photo"}""")
        }
    }

    @Test fun `OneDrive refuses a foreign continuation before sending bearer credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"value":[{"id":"p1","name":"bat.jpg","file":{"mimeType":"image/jpeg"}},{"id":"v1","name":"video.mp4","file":{"mimeType":"video/mp4"}}],"@odata.nextLink":"https://evil.example/v1.0/me/drive/root/children"}""")
            val source = OneDrivePhotoSource(id, config(server, PhotoConnector.ONEDRIVE), client, server.url("/v1.0/"))
            val first = source.listChildren(source.rootFolder, null, 24)
            assertThat(first.items).hasSize(1)
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fixture-secret")
            assertThat(runCatching { source.listChildren(source.rootFolder, first.nextCursor, 24) }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun `Box marker pages show only photo files and folders`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"entries":[{"type":"folder","id":"album","name":"Portraits"},{"type":"file","id":"p1","name":"portrait.jpg","size":8},{"type":"file","id":"doc","name":"notes.txt"}],"next_marker":"next"}""")
            server.json("""{"entries":[],"next_marker":null}""")
            val source = BoxPhotoSource(id, config(server, PhotoConnector.BOX), client, server.url("/2.0/"))
            val first = source.listChildren(source.rootFolder, null, 25)
            assertThat(first.items).hasSize(2)
            val list = server.takeRequest()
            assertThat(list.path).contains("/2.0/folders/0/items?")
            assertThat(list.requestUrl!!.queryParameter("usemarker")).isEqualTo("true")
            assertThat(source.listChildren(source.rootFolder, first.nextCursor, 25).nextCursor).isNull()
            assertThat(server.takeRequest().requestUrl!!.queryParameter("marker")).isEqualTo("next")
        }
    }

    @Test fun `S3 signs bucket listing and streams encoded object keys`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>true</IsTruncated><CommonPrefixes><Prefix>Wedding/</Prefix></CommonPrefixes><Contents><Key>bat &amp; gold!.jpg</Key><Size>8</Size></Contents><Contents><Key>notes.txt</Key><Size>1</Size></Contents><NextContinuationToken>opaque+/=</NextContinuationToken></ListBucketResult>"""))
            server.enqueue(MockResponse().setBody("original"))
            val source = S3PhotoSource(id, config(server, PhotoConnector.S3), client) { Instant.parse("2026-10-03T19:00:00Z") }
            val first = source.listChildren(source.rootFolder, null, 25)
            assertThat(first.items).hasSize(2)
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.queryParameter("delimiter")).isEqualTo("/")
            assertThat(request.getHeader("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=fixture-user/20261003/us-east-1/s3/aws4_request,")
            assertThat(request.getHeader("x-amz-date")).isEqualTo("20261003T190000Z")
            assertThat(original(source, first.items[1] as SourceEntry.Photo)).isEqualTo("original")
            assertThat(server.takeRequest().path).isEqualTo("/bat%20%26%20gold%21.jpg")
        }
    }

    @Test fun `Flickr public albums filter videos and do not require an embedded app secret`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"stat":"ok","photosets":{"pages":1,"photoset":[{"id":"set1","title":{"_content":"Photography"}}]}}""")
            server.json("""{"stat":"ok","photoset":{"pages":1,"photo":[{"id":"p1","title":"Bat"}]}}""")
            val source = FlickrPhotoSource(id, config(server, PhotoConnector.FLICKR), client, server.url("/services/rest/"))
            val album = source.listChildren(source.rootFolder, null).items.single() as SourceEntry.Folder
            assertThat(album.name).isEqualTo("Photography")
            assertThat(server.takeRequest().requestUrl!!.queryParameter("method")).isEqualTo("flickr.photosets.getList")
            assertThat(source.listChildren(album.ref, null).items.single()).isInstanceOf(SourceEntry.Photo::class.java)
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.queryParameter("media")).isEqualTo("photos")
            assertThat(request.requestUrl!!.queryParameter("user_id")).isEqualTo("fixture-user")
        }
    }

    @Test fun `redirects and auth errors are curated and cancellation closes pending requests`() = runBlocking {
        MockWebServer().use { server ->
            val http = ConnectorHttp(id, client, { mapOf("Authorization" to "Bearer fixture-token") }, server.url("/"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
            assertThat(runCatching { http.json(server.url("/")) }.exceptionOrNull()).isInstanceOf(SourceFailure.SourceUnavailable::class.java)
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-account-details"))
            val failure = runCatching { http.json(server.url("/")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SourceFailure.AuthenticationRequired::class.java)
            assertThat(failure!!.message).doesNotContain("private-account-details")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async { http.json(server.url("/slow")) }
            withTimeout(2_000) { while (server.requestCount < 3) delay(10) }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertThat(pending.isCancelled).isTrue()
        }
    }

    @Test fun `cursors cannot be reused across folders or page sizes`() = runBlocking {
        MockWebServer().use { server ->
            server.json("""[{"id":"a","albumName":"A"},{"id":"b","albumName":"B"}]""")
            val source = ImmichPhotoSource(id, config(server, PhotoConnector.IMMICH), client)
            val first = source.listChildren(source.rootFolder, null, 1)
            assertThat(first.nextCursor).isNotNull()
            assertThat(runCatching { source.listChildren(FolderRef(id, ProviderObjectId("all")), first.nextCursor, 1) }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(runCatching { source.listChildren(source.rootFolder, first.nextCursor, 2) }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun `remote cleartext and credentials inside URLs are rejected`() {
        assertThat(runCatching { ConnectorConfiguration(PhotoConnector.WEBDAV, "http://cloud.example/", "user", "fixture") }.isFailure).isTrue()
        assertThat(runCatching { ConnectorConfiguration(PhotoConnector.WEBDAV, "https://user:fixture@cloud.example/", "user", "fixture") }.isFailure).isTrue()
        assertThat(ConnectorConfiguration(PhotoConnector.DROPBOX, "https://evil.example/", "", "fixture").endpoint.host).isEqualTo("api.dropboxapi.com")
    }
}
