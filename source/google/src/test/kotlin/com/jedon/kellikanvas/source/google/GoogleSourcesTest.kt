package com.jedon.kellikanvas.source.google

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoogleSourcesTest {
    private val id = SourceProfileId("test-google")
    private fun http(server: MockWebServer, access: GoogleAccess = GoogleAccess { "test-access" }) = GoogleHttp(id, OkHttpClient(), access)

    @Test fun `Drive lists only photos and folders using readonly shared-drive queries`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"files":[{"id":"album","name":"Family","mimeType":"application/vnd.google-apps.folder"},{"id":"photo","name":"Holiday.jpg","mimeType":"image/jpeg","size":"42","imageMediaMetadata":{"width":600,"height":400}},{"id":"doc","name":"Private notes","mimeType":"text/plain"}],"nextPageToken":"next"}"""))
            val adapter = GoogleDriveSourceAdapter(id, http(server), server.url("/drive/v3/"))
            val page = adapter.listChildren(adapter.rootFolder, null, 24)
            assertThat(page.items).hasSize(2)
            assertThat((page.items[1] as SourceEntry.Photo).width).isEqualTo(600)
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.queryParameter("q")).contains("'root' in parents and trashed = false")
            assertThat(request.requestUrl!!.queryParameter("supportsAllDrives")).isEqualTo("true")
            assertThat(request.requestUrl!!.queryParameter("includeItemsFromAllDrives")).isEqualTo("true")
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-access")
            val wrongFolder = runCatching { adapter.listChildren(FolderRef(id, ProviderObjectId("other")), page.nextCursor, 24) }.exceptionOrNull()
            val wrongLimit = runCatching { adapter.listChildren(adapter.rootFolder, page.nextCursor, 25) }.exceptionOrNull()
            assertThat(wrongFolder).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(wrongLimit).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun `Drive renews a rejected access grant once and streams the original file`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private error"))
            server.enqueue(MockResponse().setBody("original-photo-bytes"))
            val refreshes = mutableListOf<Boolean>()
            val adapter = GoogleDriveSourceAdapter(
                id,
                http(
                    server,
                    GoogleAccess { force ->
                        refreshes += force
                        if (force) "new-access" else "old-access"
                    },
                ),
                server.url("/drive/v3/"),
            )
            val sink = Buffer()
            adapter.open(AssetRef(id, ProviderObjectId("photo"), "image/jpeg")).use { stream ->
                while (stream.read(sink, 4) != -1L) Unit
            }
            assertThat(sink.readUtf8()).isEqualTo("original-photo-bytes")
            assertThat(refreshes).containsExactly(false, true).inOrder()
            assertThat(server.takeRequest().requestUrl!!.queryParameter("alt")).isEqualTo("media")
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new-access")
        }
    }

    @Test fun `Google errors never expose response bodies`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret alice@example.com https://private.example"))
            val failure = runCatching { http(server).json(server.url("/files")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SourceFailure.PermissionRevoked::class.java)
            assertThat(failure!!.message).doesNotContain("alice")
            assertThat(failure.message).doesNotContain("private.example")
        }
    }

    @Test fun `Photos preview and playback share a bounded curated snapshot and refresh new photos`() = runTest {
        MockWebServer().use { server ->
            var clock = 0L
            server.enqueue(MockResponse().setBody(photos("one", "two", "three")))
            val adapter = GooglePhotosSourceAdapter(id, "device", http(server), server.url("/v1/"), now = { clock })
            val preview = adapter.listChildren(adapter.rootFolder, null, 1)
            val continuation = adapter.listChildren(adapter.rootFolder, preview.nextCursor, 1)
            assertThat((continuation.items.single() as SourceEntry.Photo).asset.objectId.value).isEqualTo("two")
            val playback = adapter.listChildren(adapter.rootFolder, null, 64)
            assertThat(playback.items).hasSize(3)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(server.takeRequest().requestUrl!!.queryParameter("pageSize")).isEqualTo("100")
            clock = GooglePhotosSourceAdapter.SNAPSHOT_REFRESH_MILLIS
            server.enqueue(MockResponse().setBody(photos("new-photo")))
            val refreshed = adapter.listChildren(adapter.rootFolder, null, 64)
            assertThat((refreshed.items.single() as SourceEntry.Photo).asset.objectId.value).isEqualTo("new-photo")
            val oldCursor = runCatching { adapter.listChildren(adapter.rootFolder, preview.nextCursor, 1) }.exceptionOrNull()
            assertThat(oldCursor).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
        }
    }

    @Test fun `Photos rejects a content host outside Google before sending an access grant`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"mediaItems":[{"id":"one","mediaFile":{"mimeType":"image/jpeg","baseUrl":"https://evil.example/photo"}}]}"""))
            val adapter = GooglePhotosSourceAdapter(id, "device", http(server), server.url("/v1/"))
            val failure = runCatching { adapter.listChildren(adapter.rootFolder, null) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun `cancelled Google request cancels the actual network call promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(Dispatchers.IO) { http(server).json(server.url("/files")) }
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            request.cancel()
            withTimeout(1_000) { request.join() }
            assertThat(request.isCancelled).isTrue()
        }
    }

    @Test fun `cancelling a stalled Google response body closes the socket promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"files\":[]}").throttleBody(1, 1, TimeUnit.DAYS))
            val request = async(Dispatchers.IO) { http(server).json(server.url("/files")) }
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            delay(100)
            withTimeout(1_000) { request.cancelAndJoin() }
            assertThat(request.isCompleted).isTrue()
        }
    }

    @Test fun `Photos device pairing respects pending and slow-down before saving a refreshable grant`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"device-code","user_code":"ABCD","verification_url":"https://www.google.com/device","expires_in":600,"interval":5}"""))
            server.enqueue(MockResponse().setResponseCode(428).setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"slow_down"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}"""))
            val oauth = GooglePhotosOAuth(id, GoogleTvConfiguration("client", "client-secret"), OkHttpClient(), server.url("/"), now = { testScheduler.currentTime })
            val challenge = oauth.begin()
            val grant = oauth.awaitGrant(challenge)
            assertThat(grant.refreshToken).isEqualTo("test-refresh")
            assertThat(testScheduler.currentTime).isEqualTo(20_000)
            assertThat(server.takeRequest().body.readUtf8()).contains("photosambient.mediaitems")
            assertThat(grant.toString()).doesNotContain("test-access")
            assertThat(challenge.toString()).doesNotContain("device-code")
        }
    }

    @Test fun `phone sign-in state and ambient registration share the same request id`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"device-code","user_code":"ABCD","verification_url":"https://www.google.com/device","expires_in":600}"""))
            server.enqueue(MockResponse().setBody("""{"id":"ambient-device","settingsUri":"https://photos.google.com/settings","mediaSourcesSet":false}"""))
            val requestId = "e3539396-2c18-4782-838c-66a1d7c3cc0c"
            val oauth = GooglePhotosOAuth(id, GoogleTvConfiguration("client", "client-secret"), OkHttpClient(), server.url("/"))
            val challenge = oauth.begin(requestId)
            val device = GooglePhotosSourceAdapter.createDevice(http(server), requireNotNull(challenge.requestId), server.url("/v1/"))
            val request = server.takeRequest()
            val fields = ("https://example.test/?" + request.body.readUtf8()).toHttpUrl()
            val state = Json.parseToJsonElement(fields.queryParameter("state")!!).jsonObject
            assertThat(state["requestId"]?.jsonPrimitive?.content).isEqualTo(requestId)
            assertThat(state["displayName"]?.jsonPrimitive?.content).isEqualTo("KelliKanvas")
            assertThat(fields.queryParameter("client_secret")).isNull()
            assertThat(fields.queryParameter("scope")).isEqualTo(GOOGLE_PHOTOS_SCOPE)
            val registration = server.takeRequest()
            assertThat(registration.requestUrl?.queryParameter("requestId")).isEqualTo(requestId)
            assertThat(registration.method).isEqualTo("POST")
            assertThat(device.id).isEqualTo("ambient-device")
            assertThat(challenge.signInUrl).isEqualTo(challenge.verificationUrl)
        }
    }

    @Test fun `phone QR uses a complete verification link only when Google supplies it`() = runTest {
        MockWebServer().use { server ->
            val complete = "https://www.google.com/device?user_code=ABCD"
            server.enqueue(MockResponse().setBody("""{"device_code":"device-code","user_code":"ABCD","verification_uri":"https://www.google.com/device","verification_uri_complete":"$complete","expires_in":600}"""))
            val oauth = GooglePhotosOAuth(id, GoogleTvConfiguration("client", "client-secret"), OkHttpClient(), server.url("/"))
            assertThat(oauth.begin().signInUrl).isEqualTo(complete)
            server.enqueue(MockResponse().setBody("""{"device_code":"device-code","user_code":"ABCD","verification_url":"https://www.google.com/device","verification_url_complete":"https://evil.example/sign-in","expires_in":600}"""))
            assertThat(runCatching { oauth.begin() }.exceptionOrNull()).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
        }
    }

    @Test fun `Photos keeps an existing refresh grant when Google renews only the access grant`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","expires_in":3600}"""))
            val oauth = GooglePhotosOAuth(id, GoogleTvConfiguration("client", "client-secret"), OkHttpClient(), server.url("/"))
            val grant = oauth.refresh(GoogleGrant("old-access", 0, "existing-refresh", clientId = "client"))
            assertThat(grant.refreshToken).isEqualTo("existing-refresh")
            val mismatch = runCatching { oauth.refresh(GoogleGrant("old-access", 0, "existing-refresh", clientId = "other-client")) }.exceptionOrNull()
            assertThat(mismatch).isInstanceOf(SourceFailure.AuthenticationRequired::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun `expired Photos code stops without any network polling`() = runTest {
        MockWebServer().use { server ->
            val oauth = GooglePhotosOAuth(id, GoogleTvConfiguration("client", "client-secret"), OkHttpClient(), server.url("/"), now = { 100 })
            val failure = runCatching { oauth.awaitGrant(GoogleDeviceChallenge("old-code", "old", "https://www.google.com/device", 5, 99)) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SourceFailure.AuthenticationRequired::class.java)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    private fun photos(vararg ids: String) = """{"mediaItems":[${ids.joinToString(",") { """{"id":"$it","mediaFile":{"mimeType":"image/jpeg","baseUrl":"https://lh3.googleusercontent.com/$it","mediaFileMetadata":{"width":4000,"height":3000}}}""" }}]}"""
}
