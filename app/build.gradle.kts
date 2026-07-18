plugins {
    id("com.jedon.kellikanvas.android.application")
    id("com.jedon.kellikanvas.android.compose")
}

val metadataPublicKeyBase64 = providers.environmentVariable("KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64").orNull

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

val householdSmbUsername = secretProperty("QNAP_NAS_USERNAME")
val householdSmbPassword = secretProperty("QNAP_NAS_PASSWORD")

android {
    namespace = "com.jedon.kellikanvas"
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.jedon.kellikanvas"
        versionCode = 14
        versionName = "1.0.13"
        buildConfigField(
            "String",
            "UPDATE_METADATA_PUBLIC_KEY_BASE64",
            "\"${metadataPublicKeyBase64.orEmpty()}\"",
        )
        // Household SMB credentials from env / .env / local.properties (never commit values).
        buildConfigField("String", "HOUSEHOLD_SMB_USERNAME", escapeBuildConfigString(householdSmbUsername))
        buildConfigField("String", "HOUSEHOLD_SMB_PASSWORD", escapeBuildConfigString(householdSmbPassword))
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

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
}
