pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KelliKanvas"

include(
    ":app",
    ":core:model",
    ":core:source-api",
    ":core:catalog",
    ":core:security",
    ":core:image",
    ":core:ui-tv",
    ":core:testing",
    ":source:saf",
    ":source:http",
    ":source:smb",
    ":source:dlna",
    ":feature:setup",
    ":feature:collection",
    ":feature:settings",
    ":feature:slideshow",
    ":renderer:surface",
    ":platform:ambient",
    ":platform:update",
)
