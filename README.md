> **AI-authored fork notice:** Features and logic added or changed relative to [upstream AirLyrics](https://github.com/AirLyrics/AirLyrics) were written by AI. The code has not been reviewed by a human. Continued development and bug fixes are not guaranteed; fork and modify the project yourself if needed.

# AirLyrics LyricDB

[English](README.md) · [简体中文](README.zh-CN.md)

An AirLyrics fork built for **Windows WebDAV music libraries and Symfonium**. Online lyrics matching in this branch is expected to be retired soon; after that migration, the local lyrics database will be the overlay's only lyrics source. Other players remain untested, with no near-term testing planned.

> **Directory layout:** The Windows tool extracts metadata and embedded lyrics from `Album` and `Main` under the music root and generates the database in the sibling `$DB` directory. These names are hard-coded in `windows/AirLyrics.Maintainer/Paths/LibraryLayout.cs`; edit that file and rebuild to change them.

## Features

- Extracts embedded lyrics and builds a database from song metadata.
- The Windows maintainer scans embedded lyrics under `Album` and `Main` using the fixed layout above and publishes the database under `$DB`; Android syncs through WebDAV or imports it locally.
- Matches lyrics using metadata supplied by the player.
- Matches the desktop lyrics styling to the in-app preview and improves bilingual karaoke rendering.
- Separate translation size and opacity controls, long-line scrolling and animated background resizing.
- Optional empty-line and custom-character filtering.

## Usage and build

**Preferred song metadata:** use native multi-value tags for artists, album artists, genres, and other multi-value fields: **Use single MP4 atom for multiple values**, or ID3v2.4 native multi-value tags. Traditional separators have compatibility support, but reliable matching is not guaranteed.

[User guide](docs/USER_GUIDE.md) · [Build instructions](docs/CONTRIBUTING.md) · [Privacy](PRIVACY.md)

Version: `1.2.5-lyricdb.2`. Package: `com.andsi.airlyrics.lyricdb`, installable alongside upstream AirLyrics.

[Repository](https://github.com/Sodium2He/AirLyrics-LyricDB/tree/lyricdb) · [Issues](https://github.com/Sodium2He/AirLyrics-LyricDB/issues)

Original project: [AirLyrics](https://github.com/AirLyrics/AirLyrics). License: [MIT](LICENSE).
