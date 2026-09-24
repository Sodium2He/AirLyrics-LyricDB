# User guide

[English](USER_GUIDE.md) · [简体中文](USER_GUIDE.zh-CN.md)

## Song database

> **Directory layout:** The Windows tool extracts metadata and embedded lyrics from `Album` and `Main` under the music root and generates the database in the sibling `$DB` directory. These names are hard-coded in `windows/AirLyrics.Maintainer/Paths/LibraryLayout.cs`; edit that file and rebuild to change them.

1. Run `AirLyrics.Maintainer` on Windows, select the music root containing `Album` and `Main`, and click **Scan & Publish**.
2. Publish `$DB/publish` through WebDAV.
3. In Android Settings → Lyrics → Song database, enter the full `manifest.json` URL and credentials, then sync over Wi-Fi. Local directory import is also available.
4. Play a song in Symfonium, select its media source in the app, and choose **Show** on the Floating page.

The WebDAV heading's `…` menu offers force full sync for rebuilding or rolling back the catalog. Server paths may differ from disk paths, such as `$DB` mapped to `/DB/`.

## Song metadata recommendations

**Preferred song metadata:** use native multi-value tags for artists, album artists, genres, and other multi-value fields: **Use single MP4 atom for multiple values**, or ID3v2.4 native multi-value tags. Traditional separators have compatibility support, but reliable matching is not guaranteed.

## Overlay

- **Font size / opacity**: separate controls for originals and translations.
- **Content / line range**: choose languages and neighboring lines.
- **Word-by-word lyrics**: each language follows its own timing; translations without word timing progress over the sentence duration.
- Long lyrics scroll with playback and follow pauses and seeks.
- **Behavior → Invalid lyric filter**: configure empty-line filtering and a character set. Enter `.` to filter dot-only rows; include a space to also filter space-only rows.

When both apps are installed, grant overlay and notification access separately. Enable status hints to investigate unmatched songs.
