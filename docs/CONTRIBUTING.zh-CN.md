# 开发说明

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)


## 构建

以 Gradle 文件声明的版本为准，可使用 Android Studio 内置 JDK 和已安装 SDK。本机 SDK 路径通过环境变量或忽略的 `local.properties` 配置，不要提交代理设置。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -Pairlyrics.skipRustBuild=true

dotnet test windows/AirLyrics.Maintainer.Tests/AirLyrics.Maintainer.Tests.csproj
dotnet publish windows/AirLyrics.Maintainer/AirLyrics.Maintainer.csproj -c Release
```

Windows 项目目标为 `net10.0-windows`。跳过 Rust 适用于仅数据库构建，不代表在线原生源已验证；完整原生构建需要 `app/build.gradle.kts` 所配置的 Rust／NDK 工具链。

用 Bash 执行 `scripts/check_localization.sh` 和 `scripts/check_architecture_boundaries.sh`，并执行 `git diff --check`。测试应检查用户可观察行为。实机测试需在专用安装上主动执行，可能替换应用数据。

## 源码整理


`applicationId` 为 `com.andsi.airlyrics.lyricdb`，源码命名空间保持 `com.andsi.airlyrics`，兼容既有类与 JNI。发布更新递增 `versionCode`，`versionName` 使用原版基底版本加 LyricDB 后缀。调试与正式签名不同，本分支内覆盖更新需保持签名一致。
