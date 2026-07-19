package com.jedon.kellikanvas.source.dlna

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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DlnaSecurityException(message: String) : SecurityException(message)

class DlnaEndpointPolicy(
    discoveredLocation: URI,
    private val privateAddressPredicate: (InetAddress) -> Boolean = ::isPrivateLanAddress,
) {
    private val scheme = discoveredLocation.scheme?.lowercase()
        ?: throw DlnaSecurityException("Endpoint scheme missing")
    private val host = discoveredLocation.host?.lowercase()
        ?: throw DlnaSecurityException("Endpoint host missing")
    private val discoveredAddresses: Set<InetAddress>

    init {
        validateShape(discoveredLocation)
        val addresses = resolve(discoveredLocation)
        if (addresses.isEmpty() || addresses.any { !privateAddressPredicate(it) }) {
            throw DlnaSecurityException("Discovered endpoint is not a private LAN address")
        }
        discoveredAddresses = addresses.toSet()
    }

    fun validateInitial(uri: URI) {
        validateShape(uri)
        validatePinnedEndpoint(uri)
    }

    fun validateRedirect(
        from: URI,
        to: URI,
        redirectCount: Int,
    ) {
        if (redirectCount > MAX_REDIRECTS) throw DlnaSecurityException("Too many redirects")
        validateShape(to)
        if (!from.scheme.equals(to.scheme, ignoreCase = true)) {
            throw DlnaSecurityException("Cross-security redirects are forbidden")
        }
        validatePinnedEndpoint(to)
    }

    internal fun pinnedAddresses(hostname: String): List<InetAddress> {
        if (!hostname.equals(host, ignoreCase = true)) {
            throw DlnaSecurityException("Endpoint differs from discovered server")
        }
        return discoveredAddresses.toList()
    }

    private fun validatePinnedEndpoint(uri: URI) {
        val candidateScheme = uri.scheme.lowercase()
        if ((candidateScheme == "http" && scheme != "http") || !uri.host.equals(host, ignoreCase = true)) {
            throw DlnaSecurityException("Endpoint differs from discovered server")
        }
        val currentAddresses = resolve(uri)
        if (currentAddresses.isEmpty() ||
            currentAddresses.any { !privateAddressPredicate(it) || it !in discoveredAddresses }
        ) {
            throw DlnaSecurityException("Endpoint address changed or is not private")
        }
    }

    private fun validateShape(uri: URI) {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw DlnaSecurityException("Unsupported endpoint scheme")
        }
        if (uri.userInfo != null) throw DlnaSecurityException("Endpoint credentials are forbidden")
        if (uri.host.isNullOrBlank()) throw DlnaSecurityException("Endpoint host missing")
    }

    private fun resolve(uri: URI): List<InetAddress> = runCatching {
        InetAddress.getAllByName(uri.host).toList()
    }.getOrElse {
        throw DlnaSecurityException("Endpoint host could not be resolved")
    }

    companion object {
        const val MAX_REDIRECTS = 3
    }
}

class DlnaPhotoLoader(
    httpClient: OkHttpClient,
    private val endpointPolicy: DlnaEndpointPolicy,
) {
    private val client =
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(endpointPolicy::pinnedAddresses)
            .proxy(Proxy.NO_PROXY)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .addNetworkInterceptor(NO_CREDENTIALS_INTERCEPTOR)
            .build()

    suspend fun open(uri: URI): DlnaPhotoByteStream {
        endpointPolicy.validateInitial(uri)
        var current = uri
        var redirects = 0
        while (true) {
            val request =
                Request.Builder()
                    .url(current.toURL())
                    .removeHeader("Authorization")
                    .removeHeader("Proxy-Authorization")
                    .get()
                    .build()
            val call = client.newCall(request)
            val response = call.awaitResponse()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                    ?: run {
                        response.close()
                        throw DlnaProtocolException("Redirect location missing")
                    }
                val next = current.resolve(location)
                response.close()
                redirects++
                endpointPolicy.validateRedirect(current, next, redirects)
                current = next
                continue
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw DlnaProtocolException("Photo HTTP $code")
            }
            val body = response.body
            val stream = DlnaPhotoByteStream(call, response, body.contentLength().takeIf { it >= 0 })
            try {
                currentCoroutineContext().ensureActive()
                return stream
            } catch (failure: Throwable) {
                stream.close()
                throw failure
            }
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class DlnaPhotoByteStream internal constructor(
    private val call: Call,
    private val response: Response,
    contentLength: Long?,
) : PhotoByteStream(contentLength) {
    private val source = response.body.source()

    @Volatile
    private var closed = false

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Stream is closed" }
        val temporary = Buffer()
        val read =
            try {
                withCanceledCall(call) {
                    source.read(temporary, byteCount)
                }
            } catch (failure: Throwable) {
                throwIfCancellation(failure, call.isCanceled())
                throw failure
            }
        if (read > 0) sink.write(temporary, read)
        return read
    }

    override fun close() {
        if (!closed) {
            closed = true
            call.cancel()
            response.close()
        }
    }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(
        object : Callback {
            override fun onFailure(
                call: Call,
                e: IOException,
            ) {
                if (!continuation.isActive) return
                try {
                    throwIfCancellation(e, call.isCanceled())
                } catch (cancel: CancellationException) {
                    continuation.resumeWithException(cancel)
                    return
                }
                continuation.resumeWithException(e)
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { _, lateResponse, _ -> lateResponse.close() }
            }
        },
    )
}

internal suspend fun Call.readBoundedCancellable(
    source: okio.BufferedSource,
    maxBytes: Int,
    label: String,
): ByteArray =
    try {
        withCanceledCall(this) {
            readBounded(source, maxBytes, label)
        }
    } catch (failure: Throwable) {
        throwIfCancellation(failure, isCanceled())
        throw failure
    }

private suspend fun <T> withCanceledCall(
    call: Call,
    block: () -> T,
): T =
    coroutineScope {
        val completed = AtomicBoolean(false)
        // Watch on Default so cancel is not starved behind the blocking IO dispatcher thread.
        val cancelWatcher =
            launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    if (!completed.get()) call.cancel()
                }
            }
        try {
            withContext(Dispatchers.IO) {
                block()
            }.also { completed.set(true) }
        } finally {
            completed.set(true)
            cancelWatcher.cancel()
        }
    }

internal val NO_CREDENTIALS_INTERCEPTOR =
    Interceptor { chain ->
        chain.proceed(
            chain.request()
                .newBuilder()
                .removeHeader("Authorization")
                .removeHeader("Proxy-Authorization")
                .build(),
        )
    }

/** RFC1918 IPv4 and unique-local IPv6 only; denies link-local, loopback, multicast, and public. */
internal fun isPrivateLanAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress ||
        address.isMulticastAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress
    ) {
        return false
    }
    return when (address) {
        is Inet4Address -> {
            val octets = address.address.map { it.toInt() and 0xff }
            octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
        is Inet6Address -> {
            val first = address.address[0].toInt() and 0xff
            first and 0xfe == 0xfc
        }
        else -> false
    }
}
