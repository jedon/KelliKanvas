package com.jedon.kellikanvas.platform.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InterruptedIOException
import java.nio.file.Files
import java.security.MessageDigest

class OkHttpUpdateTransportTest {
    private val server = MockWebServer()

    @Test
    fun `does not follow redirects across origins`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:1/evil"))
        server.start()
        val policy = UpdateOriginPolicy.forTest(server.url("/").toUri())
        val response = OkHttpUpdateTransport(originPolicy = policy).open(server.url("/manifest").toUri(), 1024)
        response.body.use {
            org.junit.Assert.assertEquals(302, response.statusCode)
            org.junit.Assert.assertTrue(response.redirected)
        }
        org.junit.Assert.assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `bounds chunked response while streaming`() {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(2048), 17))
        server.start()
        val policy = UpdateOriginPolicy.forTest(server.url("/").toUri())
        val response = OkHttpUpdateTransport(originPolicy = policy).open(server.url("/apk").toUri(), 1024)
        assertThrows(UpdateRejected::class.java) { response.body.use { it.readBytes() } }
        server.shutdown()
    }

    @Test
    fun `propagates cancellation without retaining response`() {
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(4096))
                .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS),
        )
        server.start()
        val policy = UpdateOriginPolicy.forTest(server.url("/").toUri())
        val response = OkHttpUpdateTransport(originPolicy = policy).open(server.url("/apk").toUri(), 8192)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { response.body.read() }
        } finally {
            Thread.interrupted()
            response.body.close()
            server.shutdown()
        }
    }

    @Test
    fun `mismatched real response length cleans partial and preserves verified destination`() {
        val apk = "signed-apk".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        server.start()
        val policy = UpdateOriginPolicy.forTest(server.url("/").toUri())
        val manifest =
            UpdateManifest(
                schema = 1,
                sequence = 1,
                packageName = UpdateLimits.PACKAGE_NAME,
                versionCode = 2,
                versionName = "2",
                apkUrl = server.url("/release.apk").toUri(),
                checksumUrl = server.url("/release.apk.sha256").toUri(),
                sizeBytes = apk.size.toLong(),
                sha256 = hash,
                signerSha256 = "A".repeat(64),
            )
        server.enqueue(MockResponse().setBody("$hash\n"))
        server.enqueue(MockResponse().setBody(apk.toString(Charsets.UTF_8)).setHeader("Content-Length", apk.size + 1))
        val directory = Files.createTempDirectory("real-update").toFile()
        val destination = directory.resolve("kellikanvas-2.apk").apply { writeText("previous") }
        val repository =
            UpdateRepository(
                OkHttpUpdateTransport(originPolicy = policy),
                ApkVerifier { ArchivePackage(UpdateLimits.PACKAGE_NAME, 2, setOf("A".repeat(64))) },
                directory,
                policy,
            )
        try {
            assertThrows(UpdateRejected::class.java) {
                repository.downloadAndVerify(
                    manifest,
                    InstalledPackage(UpdateLimits.PACKAGE_NAME, 1, setOf("A".repeat(64))),
                )
            }
            org.junit.Assert.assertEquals("previous", destination.readText())
            org.junit.Assert.assertFalse(directory.resolve("kellikanvas-2.apk.part").exists())
            server.enqueue(MockResponse().setBody("$hash\n"))
            server.enqueue(MockResponse().setBody(apk.toString(Charsets.UTF_8)))
            repository.downloadAndVerify(
                manifest,
                InstalledPackage(UpdateLimits.PACKAGE_NAME, 1, setOf("A".repeat(64))),
            )
            org.junit.Assert.assertArrayEquals(apk, destination.readBytes())
        } finally {
            server.shutdown()
        }
    }
}
