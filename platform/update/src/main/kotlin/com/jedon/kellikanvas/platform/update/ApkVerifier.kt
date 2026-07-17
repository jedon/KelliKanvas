package com.jedon.kellikanvas.platform.update

import java.io.File

data class ArchivePackage(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

data class InstalledPackage(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

fun interface ArchiveInspector {
    fun inspect(apk: File): ArchivePackage
}

class ApkVerifier(private val inspector: ArchiveInspector) {
    fun verify(apk: File, manifest: UpdateManifest, installed: InstalledPackage) {
        val archive = inspector.inspect(apk)
        if (installed.packageName != UpdateLimits.PACKAGE_NAME ||
            archive.packageName != UpdateLimits.PACKAGE_NAME ||
            archive.packageName != manifest.packageName
        ) {
            throw UpdateRejected("APK package mismatch")
        }
        if (archive.versionCode != manifest.versionCode || archive.versionCode <= installed.versionCode) {
            throw UpdateRejected("APK version mismatch")
        }
        if (installed.signerSha256.isEmpty() ||
            archive.signerSha256.isEmpty() ||
            archive.signerSha256.map(String::uppercase).toSet() !=
            installed.signerSha256.map(String::uppercase).toSet()
        ) {
            throw UpdateRejected("APK signer does not match installed app")
        }
        if (manifest.signerSha256.uppercase() !in archive.signerSha256.map(String::uppercase)) {
            throw UpdateRejected("authenticated metadata signer does not match APK")
        }
    }
}
