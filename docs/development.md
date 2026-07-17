# Development

KelliKanvas builds with JDK 17, Gradle 9.4.1, and Android SDK 37. Newer
JDKs may be installed on the host, but Gradle and Android builds must run
with JDK 17.

## Windows setup

Install Android Studio or the Android command-line tools, then install:

- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0 or newer
- Android SDK Platform-Tools

For a PowerShell session, set the environment explicitly:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH;$env:ANDROID_HOME\platform-tools"
```

Confirm that the expected tools are selected:

```powershell
java -version
adb version
Test-Path "$env:ANDROID_HOME\platforms\android-37.0\android.jar"
```

`java -version` must report Java 17 and the SDK check must report `True`.
Do not commit `local.properties`; Gradle can use `ANDROID_HOME`, or Android
Studio can write the local SDK path.

## Exact verification commands

Run these commands from the repository root:

```powershell
.\gradlew.bat --version
.\gradlew.bat help --warning-mode all
.\gradlew.bat -p build-logic test
.\gradlew.bat projects
.\gradlew.bat ktlintCheck lintDebug testDebugUnitTest assembleDebug --stacktrace
```

The wrapper version must be Gradle 9.4.1. The final command performs the same
smoke checks as continuous integration. Android compilation requires JDK 17
and SDK Platform 37.0; GitHub Actions is the reproducible fallback when either
is unavailable locally.
