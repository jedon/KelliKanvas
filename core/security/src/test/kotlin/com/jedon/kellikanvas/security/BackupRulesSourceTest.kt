package com.jedon.kellikanvas.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class BackupRulesSourceTest {
    @Test
    fun credentialPreferencesAreExcludedFromBackupAndDeviceTransfer() {
        val appMain = File(projectRoot(), "app/src/main")
        val manifest = File(appMain, "AndroidManifest.xml").readText()
        val backupRules = File(appMain, "res/xml/backup_rules.xml").readText()
        val extractionRules = File(appMain, "res/xml/data_extraction_rules.xml").readText()
        val preferencesFile = "${AndroidCredentialVault.PREFERENCES_NAME}.xml"

        assertThat(manifest).contains("""android:allowBackup="false"""")
        assertThat(manifest).contains("""android:fullBackupContent="@xml/backup_rules"""")
        assertThat(manifest).contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        assertThat(backupRules).contains("""domain="sharedpref" path="$preferencesFile"""")
        assertThat(extractionRules.split("""domain="sharedpref" path="$preferencesFile"""")).hasSize(3)
    }

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }
}
