package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Base64

class PhoneNasPageTest {
    @Test fun phoneFormUsesLocalSubmissionAndDoesNotPersistCredentials() {
        val logo = Base64.getEncoder().encodeToString(File("../../core/ui-tv/src/main/res/drawable-nodpi/kanvas_logo.png").readBytes())
        val page = phoneNasPage("preview-nonce", logo)
        assertThat(page).contains("fetch('/credentials'")
        assertThat(page).contains("X-KelliKanvas-Pairing")
        assertThat(page).contains("type=\"password\"")
        assertThat(page).contains("form.reset()")
        assertThat(page).doesNotContain("localStorage")
        assertThat(page).doesNotContain("sessionStorage")
        assertThat(page).doesNotContain("https://")
        val file = File("build/reports/nas-phone/phone-form.html")
        file.parentFile?.mkdirs()
        file.writeText(page)
        val connector = phoneNasPage("preview-nonce", logo, PhonePairingOptions("Jellyfin", "Enter your Jellyfin server address and login, then confirm on your TV.", needsEndpoint = true, secretLabel = "Password or app password", usernameLabel = "Username"))
        assertThat(connector).contains("name=\"endpoint\"")
        assertThat(connector).contains("Connect Jellyfin")
        File(file.parentFile, "connector-form.html").writeText(connector)
    }
}
