package com.jedon.kellikanvas.platform.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

fun interface PackageArchiveReader {
    fun readArchive(apk: File): ArchivePackage
}

class AndroidArchiveInspector(private val reader: PackageArchiveReader) : ArchiveInspector {
    override fun inspect(apk: File): ArchivePackage = reader.readArchive(apk)
}

class PackageManagerArchiveReader(private val packageManager: PackageManager) : PackageArchiveReader {
    override fun readArchive(apk: File): ArchivePackage {
        val info =
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw UpdateRejected("Android could not inspect APK archive")
        return info.toArchivePackage()
    }
}

class InstalledPackageReader(private val packageManager: PackageManager) {
    fun read(packageName: String = UpdateLimits.PACKAGE_NAME): InstalledPackage {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return InstalledPackage(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            signerSha256 = info.signerDigests(),
        )
    }
}

private fun PackageInfo.toArchivePackage() = ArchivePackage(
    packageName = packageName,
    versionCode = longVersionCode,
    signerSha256 = signerDigests(),
)

private fun PackageInfo.signerDigests(): Set<String> {
    val signers =
        signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        }.orEmpty()
    return signers.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
    }.toSet()
}
