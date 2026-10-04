package com.jedon.kellikanvas.source.connected

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

/** All errors are curated: Connector's response bodies can contain personal data and URLs. */
class ConnectorHttp(
    val profileId: SourceProfileId,
    client: OkHttpClient,
    private val headers: () -> Map<String, String>,
    private val origin: HttpUrl,
    private val signer: ((HttpUrl, String) -> Map<String, String>)? = null,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun json(url: HttpUrl, method: String = "GET", body: RequestBody? = null): kotlinx.serialization.json.JsonElement = safeConnectorOperation(profileId, "request") {
        Json.parseToJsonElement(text(url, method, body))
    }

    suspend fun text(url: HttpUrl, method: String = "GET", body: RequestBody? = null): String = safeConnectorOperation(profileId, "request") {
        response(url, method, body).use { response ->
            val source = response.response.body.source()
            val buffer = Buffer()
            response.call.withConnectorCancellation {
                while (buffer.size <= MAX_JSON_BYTES) {
                    val read = source.read(buffer, minOf(8192L, MAX_JSON_BYTES + 1 - buffer.size))
                    if (read == -1L) break
                }
            }
            if (buffer.size > MAX_JSON_BYTES) throw SourceFailure.ProtocolFailure(profileId, "request", "The service returned an oversized response")
            buffer.readUtf8()
        }
    }

    suspend fun delete(url: HttpUrl) = safeConnectorOperation(profileId, "disconnect") { response(url, "DELETE", null).close() }

    suspend fun open(url: HttpUrl, method: String = "GET", body: RequestBody? = null): PhotoByteStream = safeConnectorOperation(profileId, "open") {
        val response = response(url, method, body)
        ConnectorPhotoStream(profileId, response)
    }

    private suspend fun response(url: HttpUrl, method: String, body: RequestBody?): ConnectorResponse {
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) { "Cross-origin request rejected" }
        val request = Request.Builder().url(url).method(method, body)
        (headers() + signer?.invoke(url, method).orEmpty()).forEach { (name, value) -> request.header(name, value) }
        val call = client.newCall(request.build())
        val response = call.awaitConnectorResponse()
        if (response.isSuccessful) return ConnectorResponse(call, response)
        val status = response.code
        response.close()
        throw connectorStatusFailure(profileId, status)
    }

    private companion object {
        const val MAX_JSON_BYTES = 2 * 1024 * 1024L
    }
}

internal suspend fun Call.awaitConnectorResponse(): Response = suspendCancellableCoroutine { continuation ->
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

private class ConnectorResponse(val call: Call, val response: Response) : Closeable {
    override fun close() = response.close()
}

private class ConnectorPhotoStream(private val id: SourceProfileId, private val response: ConnectorResponse) : PhotoByteStream(response.response.body.contentLength().takeIf { it >= 0 }) {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = safeConnectorOperation(id, "read") {
        try {
            response.call.withConnectorCancellation { response.response.body.source().read(sink, byteCount) }
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }
    override fun close() = response.close()
}

internal fun connectorStatusFailure(id: SourceProfileId, status: Int): SourceFailure = when (status) {
    401 -> SourceFailure.AuthenticationRequired(id, "connect", "Reconnect this source")
    403 -> SourceFailure.PermissionRevoked(id, "connect", "Access was denied. Check the connection and enabled API")
    404 -> SourceFailure.NotFound(id, "request", "The photo is no longer available")
    429 -> SourceFailure.SourceUnavailable(id, "request", "The service request limit reached. Try again later")
    else -> SourceFailure.SourceUnavailable(id, "request", "The service could not complete the request")
}

internal suspend fun <T> safeConnectorOperation(id: SourceProfileId, operation: String, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: SourceFailure) {
    throw failure
} catch (_: SocketTimeoutException) {
    throw SourceFailure.Timeout(id, operation)
} catch (_: IOException) {
    throw SourceFailure.SourceUnavailable(id, operation, "The service is unreachable. Check your internet connection")
} catch (_: IllegalArgumentException) {
    throw SourceFailure.ProtocolFailure(id, operation, "The service returned an invalid response")
} catch (_: Exception) {
    throw SourceFailure.ProtocolFailure(id, operation, "The service could not complete this operation")
}

/** Socket reads ignore thread interruption; cancel the call from a separate dispatcher. */
internal suspend fun <T> Call.withConnectorCancellation(block: () -> T): T = coroutineScope {
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
