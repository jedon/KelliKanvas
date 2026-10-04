package com.jedon.kellikanvas.source.google

import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** All errors are curated: Google's response bodies can contain personal data and URLs. */
class GoogleHttp(
    val profileId: SourceProfileId,
    client: OkHttpClient,
    private val access: GoogleAccess,
) {
    private val client = client.newBuilder().followSslRedirects(false).build()

    suspend fun json(url: HttpUrl, method: String = "GET", body: RequestBody? = null): JsonObject = safeGoogleOperation(profileId, "request") {
        response(url, method, body).use { response ->
            val source = response.response.body.source()
            val buffer = Buffer()
            response.call.withGoogleCancellation {
                while (buffer.size <= MAX_JSON_BYTES) {
                    val read = source.read(buffer, minOf(8192L, MAX_JSON_BYTES + 1 - buffer.size))
                    if (read == -1L) break
                }
            }
            if (buffer.size > MAX_JSON_BYTES) throw SourceFailure.ProtocolFailure(profileId, "request", "Google returned an oversized response")
            Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject ?: throw IllegalArgumentException("Invalid Google response")
        }
    }

    suspend fun delete(url: HttpUrl) = safeGoogleOperation(profileId, "disconnect") { response(url, "DELETE", null).close() }

    suspend fun open(url: HttpUrl): PhotoByteStream = safeGoogleOperation(profileId, "open") {
        val response = response(url, "GET", null)
        GooglePhotoStream(profileId, response)
    }

    private suspend fun response(url: HttpUrl, method: String, body: RequestBody?): GoogleResponse {
        var token = access.token(false)
        repeat(2) { attempt ->
            val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, body).build())
            val response = call.awaitGoogleResponse()
            if (response.isSuccessful) return GoogleResponse(call, response)
            val status = response.code
            response.close()
            if (status == 401 && attempt == 0) {
                token = access.token(true)
            } else {
                throw googleStatusFailure(profileId, status)
            }
        }
        throw SourceFailure.AuthenticationRequired(profileId, "connect", "Reconnect your Google account")
    }

    private companion object {
        const val MAX_JSON_BYTES = 2 * 1024 * 1024L
    }
}

internal suspend fun Call.awaitGoogleResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

private class GoogleResponse(val call: Call, val response: Response) : Closeable {
    override fun close() = response.close()
}

private class GooglePhotoStream(private val id: SourceProfileId, private val response: GoogleResponse) : PhotoByteStream(response.response.body.contentLength().takeIf { it >= 0 }) {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = safeGoogleOperation(id, "read") {
        try {
            response.call.withGoogleCancellation { response.response.body.source().read(sink, byteCount) }
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }
    override fun close() = response.close()
}

internal fun googleStatusFailure(id: SourceProfileId, status: Int): SourceFailure = when (status) {
    401 -> SourceFailure.AuthenticationRequired(id, "connect", "Reconnect your Google account")
    403 -> SourceFailure.PermissionRevoked(id, "connect", "Google access was denied. Check the connection and enabled API")
    404 -> SourceFailure.NotFound(id, "request", "The Google item is no longer available")
    429 -> SourceFailure.SourceUnavailable(id, "request", "Google request limit reached. Try again later")
    else -> SourceFailure.SourceUnavailable(id, "request", "Google could not complete the request")
}

internal suspend fun <T> safeGoogleOperation(id: SourceProfileId, operation: String, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: SourceFailure) {
    throw failure
} catch (_: SocketTimeoutException) {
    throw SourceFailure.Timeout(id, operation)
} catch (_: IOException) {
    throw SourceFailure.SourceUnavailable(id, operation, "Google is unreachable. Check your internet connection")
} catch (_: IllegalArgumentException) {
    throw SourceFailure.ProtocolFailure(id, operation, "Google returned an invalid response")
} catch (_: Exception) {
    throw SourceFailure.ProtocolFailure(id, operation, "Google could not complete this operation")
}

/** Socket reads ignore thread interruption; cancel the call from a separate dispatcher. */
internal suspend fun <T> Call.withGoogleCancellation(block: () -> T): T = coroutineScope {
    val completed = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            if (!completed.get()) cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }.also { completed.set(true) }
    } catch (failure: Exception) {
        // Call.cancel() can wake a blocked read with IOException. Preserve parent cancellation.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        completed.set(true)
        watcher.cancel()
    }
}
