plugins {
    id("com.jedon.kellikanvas.android.application")
    id("com.jedon.kellikanvas.android.compose")
}

val metadataPublicKeyBase64 = providers.environmentVariable("KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64").orNull?.takeIf { it.isNotBlank() }
    ?: rootProject.file("deploy/update-metadata-pins.txt").readText().trim()
val publicDistribution = providers.gradleProperty("publicDistribution").orNull == "true"

fun escapeBuildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}

fun loadDotEnv(file: java.io.File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val idx = line.indexOf('=')
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }
}

fun secretProperty(name: String): String {
    val fromEnv = providers.environmentVariable(name).orNull
    if (!fromEnv.isNullOrBlank()) return fromEnv
    val candidates =
        listOf(
            rootProject.file(".env"),
            rootProject.file("../.env"),
            rootProject.file("../../.env"),
            rootProject.file("local.properties"),
        )
    for (file in candidates) {
        val map = loadDotEnv(file)
        val value = map[name]
        if (!value.isNullOrBlank()) return value
    }
    return ""
}

android {
    namespace = "com.jedon.kellikanvas"
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.jedon.kellikanvas"
        versionCode = 25
        versionName = "1.0.24"
        buildConfigField(
            "String",
            "UPDATE_METADATA_PUBLIC_KEY_BASE64",
            "\"${metadataPublicKeyBase64.orEmpty()}\"",
        )
        // NAS login is supplied at runtime from the phone pairing form, never from build inputs.
        buildConfigField("String", "HOUSEHOLD_SMB_USERNAME", "\"\"")
        buildConfigField("String", "HOUSEHOLD_SMB_PASSWORD", "\"\"")
        buildConfigField("String", "CLOUD_SERVER_URL", escapeBuildConfigString(secretProperty("KELLIKANVAS_SERVER_URL").ifBlank { "https://kanvas.kelli.photo" }))
        buildConfigField("String", "GOOGLE_TV_CLIENT_ID", escapeBuildConfigString(if (publicDistribution) "" else secretProperty("KELLIKANVAS_GOOGLE_TV_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_TV_CLIENT_SECRET", escapeBuildConfigString(if (publicDistribution) "" else secretProperty("KELLIKANVAS_GOOGLE_TV_CLIENT_SECRET")))
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.register("validateReleaseMetadataPin") {
    inputs.property("metadataPublicKeyConfigured", !metadataPublicKeyBase64.isNullOrBlank())
    doLast {
        if (inputs.properties.getValue("metadataPublicKeyConfigured") != true) {
            throw GradleException("Release build requires KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64.")
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn("validateReleaseMetadataPin")
    }
}

dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:image"))
    implementation(project(":core:logging"))
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:source-api"))
    implementation(project(":core:ui-tv"))
    implementation(project(":feature:collection"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:slideshow"))
    implementation(project(":platform:ambient"))
    implementation(project(":platform:update"))
    implementation(project(":renderer:surface"))
    implementation(project(":source:dlna"))
    implementation(project(":source:google"))
    implementation(project(":source:connected"))
    implementation(project(":source:http"))
    implementation(project(":source:saf"))
    implementation(project(":source:smb"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    implementation(libs.okhttp)
    implementation(libs.google.auth)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
