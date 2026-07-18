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
        versionCode = 5
        versionName = "1.0.4-debug"
        buildConfigField(
            "String",
            "UPDATE_METADATA_PUBLIC_KEY_BASE64",
            "\"${metadataPublicKeyBase64.orEmpty()}\"",
        )
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
