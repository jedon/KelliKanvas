package com.jedon.kellikanvas.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesSourceTest {
    @Test
    fun credentialPreferencesAreExcludedFromBackupAndDeviceTransfer() {
        val appMain = File(projectRoot(), "app/src/main")
        val manifest = parse(File(appMain, "AndroidManifest.xml"))
        val backupRules = parse(File(appMain, "res/xml/backup_rules.xml"))
        val extractionRules = parse(File(appMain, "res/xml/data_extraction_rules.xml"))
        val preferencesFile = "${AndroidCredentialVault.PREFERENCES_NAME}.xml"
        val application = manifest.getElementsByTagName("application").item(0) as Element

        assertThat(application.androidAttribute("allowBackup")).isEqualTo("false")
        assertThat(application.androidAttribute("fullBackupContent")).isEqualTo("@xml/backup_rules")
        assertThat(application.androidAttribute("dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")
        assertThat(exclusionCount(backupRules, preferencesFile)).isEqualTo(1)
        assertThat(exclusionCount(extractionRules, preferencesFile)).isEqualTo(2)
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(ACCESS_EXTERNAL_DTD, "")
        setAttribute(ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(file)

    private fun Element.androidAttribute(name: String): String = getAttributeNS(
        "http://schemas.android.com/apk/res/android",
        name,
    )

    private fun exclusionCount(
        document: org.w3c.dom.Document,
        preferencesFile: String,
    ): Int = (0 until document.getElementsByTagName("exclude").length)
        .map { document.getElementsByTagName("exclude").item(it) as Element }
        .count {
            it.getAttribute("domain") == "sharedpref" &&
                it.getAttribute("path") in setOf(".", preferencesFile)
        }

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }

    private companion object {
        const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
    }
}
