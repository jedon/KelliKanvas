package com.jedon.kellikanvas.source.dlna

import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class DlnaSecurityException(message: String) : SecurityException(message)

class DlnaEndpointPolicy(
    discoveredLocation: URI,
    private val privateAddressPredicate: (InetAddress) -> Boolean = ::isPrivateOrLinkLocal,
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
            throw DlnaSecurityException("Discovered endpoint is not private or link-local")
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
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .build()

    suspend fun open(uri: URI): DlnaPhotoByteStream = withContext(Dispatchers.IO) {
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
            val response = executeCancellable(call)
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
            return@withContext DlnaPhotoByteStream(call, response, body.contentLength().takeIf { it >= 0 })
        }
        error("Unreachable")
    }

    private suspend fun executeCancellable(call: Call): Response {
        val context = currentCoroutineContext()
        val cancellation = context[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        return try {
            call.execute()
        } finally {
            cancellation?.dispose()
            context.ensureActive()
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
    ): Long = withContext(Dispatchers.IO) {
        check(!closed) { "Stream is closed" }
        val context = currentCoroutineContext()
        val cancellation = context[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            source.read(sink, byteCount)
        } finally {
            cancellation?.dispose()
            context.ensureActive()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            call.cancel()
            response.close()
        }
    }
}

private fun isPrivateOrLinkLocal(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLoopbackAddress) return false
    if (address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    return when (address) {
        is Inet4Address -> {
            val octets = address.address.map { it.toInt() and 0xff }
            octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }
        is Inet6Address -> {
            val first = address.address[0].toInt() and 0xff
            first and 0xfe == 0xfc
        }
        else -> false
    }
}
