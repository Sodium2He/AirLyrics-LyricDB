# Architecture

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

## Components

- `windows/AirLyrics.Maintainer`: Windows/.NET WinForms scanner, original embedded-lyrics readers, incremental state, SQLite shard publisher and WebDAV uploader. The current code hard-codes `Album`/`Main` as scan directory names and the sibling `$DB` as the output directory.
- `shared`: catalog schema and cross-language fixtures.
- `app`: Kotlin Android app, catalog synchronization/indexing, metadata matching, playback clock and overlay.
- `lyrics-core`: inherited Rust/JNI online providers, outside current tested scope.

## Data path

Windows tags → manifest + immutable SQLite shards → WebDAV or directory import → Android catalog index → MediaSession metadata matching → original lyric payload → catalog adapter → display filter and renderer.

The floating service uses catalog lookup directly. Android matching never relies on paths, filenames or persistent media IDs. Album, duration, artist and disc/track evidence constrain candidates; ambiguity is explicit. Catalog activation is transactional after shard validation. Forced sync permits intentional library/version replacement, not corrupt data. Prefetch stores original payloads so display filtering can be changed without rewriting catalog data.

## Text and time

`ShardLyricsAdapter` retains raw text and builds plain bilingual rows plus separate original/translation timing. Same-time subsequent word-tagged rows are adapted as translations; terminal tags preserve sentence ends.

`PlaybackClockSnapshot` derives position from monotonic time, speed, seek and pause state. `FloatingLyricsRenderer` selects logical sentences and attaches non-visual row timing. Shared `core/text/LyricTextAppearance` defines size, alpha and neighboring-line emphasis for both the preview and overlay.

`FloatingLyricsTextView` caches one complete layout per physical row. It draws the same shaped row twice under a moving highlight clip, avoiding per-tick shaping boundaries. Overwide rows scroll from the word frontier or sentence progress. Background resizing changes measured bounds without scaling text. Metadata is kept; only tag syntax is hidden when displayed. Optional filtering runs on raw timed rows before bilingual grouping.

## Boundaries and validation

UI talks to host interfaces; platform storage and service coordination stay outside UI. Settings live in the app sandbox. The package ID differs from upstream while source/JNI namespaces stay unchanged.
