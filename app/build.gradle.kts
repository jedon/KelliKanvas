plugins {
    id("com.jedon.kellikanvas.android.application")
    id("com.jedon.kellikanvas.android.compose")
}

val metadataPublicKeyBase64 = providers.environmentVariable("KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64").orNull

android {
    namespace = "com.jedon.kellikanvas"
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.jedon.kellikanvas"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "UPDATE_METADATA_PUBLIC_KEY_BASE64",
            "\"${metadataPublicKeyBase64.orEmpty()}\"",
        )
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
    if (name.contains("Release", ignoreCase = true) && name != "validateReleaseMetadataPin") {
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
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(project(":core:testing"))
}
