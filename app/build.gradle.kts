plugins {
    id("com.jedon.kellikanvas.android.application")
    id("com.jedon.kellikanvas.android.compose")
}

val releaseSigningEnvironment =
    mapOf(
        "file" to providers.environmentVariable("KELLIKANVAS_KEYSTORE_FILE").orNull,
        "storePassword" to providers.environmentVariable("KELLIKANVAS_KEYSTORE_PASSWORD").orNull,
        "alias" to providers.environmentVariable("KELLIKANVAS_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("KELLIKANVAS_KEY_PASSWORD").orNull,
    )

android {
    namespace = "com.jedon.kellikanvas"

    defaultConfig {
        applicationId = "com.jedon.kellikanvas"
        versionCode = 1
        versionName = "0.1.0"
    }

    if (releaseSigningEnvironment.values.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningEnvironment["file"]))
                storePassword = releaseSigningEnvironment["storePassword"]
                keyAlias = releaseSigningEnvironment["alias"]
                keyPassword = releaseSigningEnvironment["keyPassword"]
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

val missingReleaseSigningVariables =
    releaseSigningEnvironment
        .filterValues { it.isNullOrBlank() }
        .keys
        .joinToString(",")

tasks.register("validateReleaseSigning") {
    inputs.property("missingReleaseSigningVariables", missingReleaseSigningVariables)
    doLast {
        if (inputs.properties.getValue("missingReleaseSigningVariables").toString().isNotEmpty()) {
            throw GradleException(
                "Release signing requires KELLIKANVAS_KEYSTORE_FILE, " +
                    "KELLIKANVAS_KEYSTORE_PASSWORD, KELLIKANVAS_KEY_ALIAS, and " +
                    "KELLIKANVAS_KEY_PASSWORD.",
            )
        }
    }
}

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name != "validateReleaseSigning") {
        dependsOn("validateReleaseSigning")
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
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(project(":core:testing"))
}
