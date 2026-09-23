# Privacy Policy

[English](PRIVACY.md) · [简体中文](PRIVACY.zh-CN.md)

AirLyrics is designed to show floating synced lyrics on Android.

WebDAV sync sends requests and credentials to the configured server and downloads the lyric database. Connection settings are stored privately in the app. Windows uploads contain lyrics and song metadata.

## Data collection

AirLyrics does not collect personal data.

AirLyrics does not use analytics, advertising SDKs, tracking SDKs, account systems, or crash-reporting services.

## Permissions

AirLyrics asks for the following Android permissions only when they are needed for app features:

| Permission / access     | Purpose                                                                              |
|-------------------------|--------------------------------------------------------------------------------------|
| Display over other apps | Shows the floating lyrics window above other apps                                    |
| Notification access     | Detects current media playback information from local notifications / media sessions |
| Notifications           | Keeps the foreground floating lyrics service visible to Android                      |
| Usage access            | Checks whether apps selected in Display scope are visible (Android 10+)               |
| Internet access         | Synchronizes the configured WebDAV catalog; performs manual online lyrics search                       |
| File picker             | Lets the user import local lyrics files                                              |

Notification access is used locally on the device to detect media playback metadata such as the playing app, title, artist, album, playback state, and available media controls. AirLyrics does not upload notification content.

Usage access is optional and used locally only for Display scope. AirLyrics does not read app content or upload usage data.

## Local data

AirLyrics may store app settings, selected media source information, Display scope app selections, local lyrics records, cached lyrics, and recent lyrics download records on the device.

This data is used only for app functionality and remains on the user's device unless the user exports, shares, backs up, or otherwise transfers it using system features or third-party tools.

## Online lyrics search

When the user searches lyrics online, AirLyrics sends the search request required by the lyrics provider. This may include song-related metadata such as title, artist, album, and duration.

AirLyrics does not add personal identifiers, accounts, analytics identifiers, or advertising identifiers to these requests.

## Third-party services

The inherited online-search feature uses lyrics providers; this fork additionally contacts the user-configured WebDAV server. Those providers may receive the search request needed to return lyrics. Their own privacy practices are controlled by those providers.

## Children

AirLyrics is not designed to collect personal data from anyone, including children.

## Changes

This privacy policy may be updated when AirLyrics changes how permissions, local storage, or online lyrics search work.

## Contact

For questions or issues, please use GitHub Issues:

[GitHub Issues](https://github.com/Sodium2He/AirLyrics-LyricDB/issues)
