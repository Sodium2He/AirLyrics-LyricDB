# 架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

## 组成

- `windows/AirLyrics.Maintainer`：Windows/.NET WinForms 扫描、原始内嵌歌词读取、增量状态、SQLite 分片发布及 WebDAV 上传。当前代码将扫描目录名硬编码为 `Album`／`Main`、输出目录名硬编码为同级 `$DB`。
- `shared`：数据库结构和跨语言测试样本。
- `app`：Kotlin Android 应用，包含同步、索引、元数据匹配、播放时钟和悬浮窗。
- `lyrics-core`：继承的 Rust/JNI 在线源，不在当前测试范围内。

## 数据路径

Windows 标签 → 清单及 SQLite 分片 → WebDAV 或目录导入 → Android 本地索引 → MediaSession 元数据匹配 → 原始歌词 → 数据库适配 → 显示过滤及渲染。

悬浮服务直接查询数据库；不通过 Android 路径、文件名或持久媒体 ID 匹配。歌手、专辑、时长及碟号／曲号约束候选，有歧义时明确报告。分片验证完成后事务切换数据库。强制同步允许主动换库或回退，但不允许损坏数据。预取缓存保留原始负载，改变过滤设置无需重写数据库。

## 文字与时间

`ShardLyricsAdapter` 保留原文，并生成普通双语行和分别独立的原文／译文时间。同时间后续逐字行作为译文适配，末尾时间标签用于保留句末。

`PlaybackClockSnapshot` 使用单调时钟、速度、跳转和暂停状态计算进度。`FloatingLyricsRenderer` 选择逻辑句并附加不影响排版的行时间信息。预览和实际悬浮窗共用 `core/text/LyricTextAppearance` 的字号、不透明度和相邻句样式。

`FloatingLyricsTextView` 为每个显示行缓存完整排版，在同一排版上裁切绘制高亮，避免每次进度更新改变文字塑形分段。长句依据当前字或句子进度滚动；背景伸缩改变测量边界，不缩放文字。保留 metadata 内容，仅在显示时去除标签语法。可选过滤在双语配对前处理原始歌词行。

## 边界与验证

UI 通过 host 接口操作，平台存储和服务协调不放入 UI。设置保存在应用沙箱。包名与原版分离，源码及 JNI 命名空间不变。
