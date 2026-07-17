package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

class UpdateRepositoryTest {
    private val bytes = "signed-apk".toByteArray()
    private val hash = bytes.sha256()
    private val manifest =
        UpdateManifest(
            schema = 1,
            packageName = UpdateLimits.PACKAGE_NAME,
            versionCode = 2,
            versionName = "0.2.0",
            apkUrl = URI("http://darklingnas:8088/kellikanvas-2.apk"),
            checksumUrl = URI("http://darklingnas:8088/kellikanvas-2.apk.sha256"),
            sizeBytes = bytes.size.toLong(),
            sha256 = hash,
            signerSha256 = "A".repeat(64),
        )

    @Test
    fun `streams exact bytes and verifies independent checksum archive and signer`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        val transport = FakeTransport(bytes, "$hash  kellikanvas-2.apk\n".toByteArray())
        val verifier = ApkVerifier(FakeInspector())
        val repository = UpdateRepository(transport, verifier, dir)

        val apk = repository.downloadAndVerify(manifest, installed())

        assertThat(apk.readBytes()).isEqualTo(bytes)
        assertThat(transport.maxRequested).containsExactly(
            UpdateLimits.METADATA_MAX_BYTES.toLong(),
            UpdateLimits.APK_MAX_BYTES,
        )
    }

    @Test
    fun `rejects redirects and deletes partial download`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        val transport = FakeTransport(bytes, "$hash\n".toByteArray(), redirected = true)
        val repository = UpdateRepository(transport, ApkVerifier(FakeInspector()), dir)

        assertThrows(UpdateRejected::class.java) {
            repository.downloadAndVerify(manifest, installed())
        }
        assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `rejects checksum truncation oversize hash package version and signer mismatch`() {
        val cases =
            listOf(
                Case("independent checksum", bytes, "0".repeat(64).toByteArray(), manifest, FakeInspector()),
                Case("truncation", bytes.dropLast(1).toByteArray(), null, manifest, FakeInspector()),
                Case("oversize", bytes, null, manifest.copy(sizeBytes = UpdateLimits.APK_MAX_BYTES + 1), FakeInspector()),
                Case("hash", "tampered!".toByteArray(), null, manifest, FakeInspector()),
                Case("package", bytes, null, manifest, FakeInspector(packageName = "other")),
                Case("version", bytes, null, manifest, FakeInspector(versionCode = 3)),
                Case("signer", bytes, null, manifest, FakeInspector(signer = "B".repeat(64))),
            )
        cases.forEach { case ->
            val dir = Files.createTempDirectory("update-${case.name}").toFile()
            val repository =
                UpdateRepository(
                    FakeTransport(case.apk, case.checksum ?: "$hash\n".toByteArray()),
                    ApkVerifier(case.inspector),
                    dir,
                )
            assertThrows(case.name, UpdateRejected::class.java) {
                repository.downloadAndVerify(case.manifest, installed())
            }
            assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
        }
    }

    @Test
    fun `cleanup removes stale update files`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        File(dir, "old.apk").writeText("old")
        File(dir, "old.part").writeText("partial")
        UpdateRepository(FakeTransport(bytes, "$hash\n".toByteArray()), ApkVerifier(FakeInspector()), dir)
            .deleteStaleFiles()
        assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `atomically replaces existing destination only after verification`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        val destination = File(dir, "kellikanvas-2.apk")
        destination.writeText("previous")
        val repository =
            UpdateRepository(
                FakeTransport(bytes, "$hash\n".toByteArray()),
                ApkVerifier(FakeInspector()),
                dir,
            )

        val installed = repository.downloadAndVerify(manifest, installed())

        assertThat(installed).isEqualTo(destination)
        assertThat(installed.readBytes()).isEqualTo(bytes)
        assertThat(dir.resolve("kellikanvas-2.apk.part").exists()).isFalse()
    }

    @Test
    fun `failed replacement preserves last verified apk`() {
        val dir = Files.createTempDirectory("update-test").toFile()
        val destination = File(dir, "kellikanvas-2.apk")
        destination.writeText("previous")
        val repository =
            UpdateRepository(
                FakeTransport("tampered!".toByteArray(), "$hash\n".toByteArray()),
                ApkVerifier(FakeInspector()),
                dir,
            )

        assertThrows(UpdateRejected::class.java) {
            repository.downloadAndVerify(manifest, installed())
        }
        assertThat(destination.readText()).isEqualTo("previous")
    }

    private fun installed() = InstalledPackage(UpdateLimits.PACKAGE_NAME, 1, setOf("A".repeat(64)))

    private data class Case(
        val name: String,
        val apk: ByteArray,
        val checksum: ByteArray?,
        val manifest: UpdateManifest,
        val inspector: ArchiveInspector,
    )

    private class FakeTransport(
        private val apk: ByteArray,
        private val checksum: ByteArray,
        private val redirected: Boolean = false,
    ) : UpdateTransport {
        val maxRequested = mutableListOf<Long>()

        override fun open(url: URI, maxBytes: Long): UpdateResponse {
            maxRequested += maxBytes
            val body = if (url.path.endsWith(".sha256")) checksum else apk
            return UpdateResponse(200, url, redirected, body.size.toLong(), ByteArrayInputStream(body))
        }
    }

    private class FakeInspector(
        private val packageName: String = UpdateLimits.PACKAGE_NAME,
        private val versionCode: Long = 2,
        private val signer: String = "A".repeat(64),
    ) : ArchiveInspector {
        override fun inspect(apk: File) = ArchivePackage(packageName, versionCode, setOf(signer))
    }
}

private fun ByteArray.sha256(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
