> **AI 修改声明：** 本仓库相对于[原版 AirLyrics](https://github.com/AirLyrics/AirLyrics) 新增或修改的功能和逻辑由 AI 编写，代码未经人工核查。不保证持续开发或修复程序问题；如有需要，请自行 fork 后修改。

# AirLyrics LyricDB

[English](README.md) · [简体中文](README.zh-CN.md)

基于 AirLyrics 的歌曲数据库分支，主要针对 **Windows WebDAV 歌曲库和 Symfonium 播放器**。在线匹配和其他播放器适配尚未测试，短期内无测试计划。

## 功能

- 提取音乐文件中的歌词，基于歌曲元数据创建数据库。
- Windows 维护工具扫描 `Album`、`Main`，在 `$DB` 生成数据库与发布清单；Android 通过 WebDAV 同步或导入。
- 根据播放器提供的歌曲元数据匹配歌词。
- 桌面歌词与 App 内样例采用一致的显示样式，改善双语及逐字歌词渲染效果。
- 译文字号、不透明度独立设置，支持长句滚动和背景框伸缩动画。
- 可选空行过滤和基于自定义字符的无效行过滤。

## 使用与构建

**歌曲元数据首选建议：** 艺术家、专辑艺术家、流派等多值字段优先采用原生多值标签：**Use single MP4 atom for multiple values**（MP4 单个 atom 内存储多个值），或 ID3v2.4 原生多值标签。传统分隔符具备兼容性支持，但不保证匹配可靠性。

[使用指南](docs/USER_GUIDE.zh-CN.md) · [构建说明](docs/CONTRIBUTING.zh-CN.md) · [隐私说明](PRIVACY.zh-CN.md)

版本：`1.2.5-lyricdb.2`。包名：`com.andsi.airlyrics.lyricdb`，可与原版共存。

[仓库](https://github.com/Sodium2He/AirLyrics-LyricDB/tree/lyricdb) · [问题反馈](https://github.com/Sodium2He/AirLyrics-LyricDB/issues)

原项目：[AirLyrics](https://github.com/AirLyrics/AirLyrics)。许可证：[MIT](LICENSE)。
