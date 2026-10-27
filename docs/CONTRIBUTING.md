# Development

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)


## Build

Use the versions declared in Gradle files; the Android Studio bundled JDK and installed SDK can be used. Configure local SDK paths through environment variables or ignored `local.properties`, and keep proxy settings out of tracked files.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

dotnet test windows/AirLyrics.Maintainer.Tests/AirLyrics.Maintainer.Tests.csproj
dotnet publish windows/AirLyrics.Maintainer/AirLyrics.Maintainer.csproj -c Release
```

The Windows project targets `net10.0-windows`. Android builds use Gradle and the Android SDK.

Run `scripts/check_localization.sh` and `scripts/check_architecture_boundaries.sh` with Bash, and `git diff --check`. Test data and rendering assertions should check user behavior, not only that code compiles. Device tests must be run deliberately on a test installation; they can replace app data.

## Source hygiene


`applicationId` is `com.andsi.airlyrics.lyricdb`. Keep source namespace `com.andsi.airlyrics` for existing classes. Increment `versionCode` for published updates and use an upstream-base plus LyricDB suffix in `versionName`. Debug and release signatures differ; use a consistent signing key for upgrades within this fork.
