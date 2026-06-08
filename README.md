<div align="center">
  <img src="./.github/assets/icon-round.png" alt="Miyo app icon" width="160">

  # Miyo

  **A fast, extensible, open-source comic and manga reader for Android.**

  [![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3ddc84?logo=android&logoColor=white)](#)
  [![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![GitHub Releases](https://img.shields.io/github/v/release/Torro101/MIYO?include_prereleases&label=release)](https://github.com/Torro101/MIYO/releases)
  [![GitHub Actions](https://img.shields.io/github/actions/workflow/status/Torro101/MIYO/ci.yml?branch=main&label=CI)](https://github.com/Torro101/MIYO/actions)

  [Project Website](https://torro101.github.io/koharu-miyo/)
</div>

## Overview

Miyo is an Android comic reader built for large libraries, online sources, and reliable offline reading. It focuses on a clean Material interface, strong source/plugin support, stable background downloads, and practical performance on both modern and low-end devices.

Miyo is a rebranded fork and continuation of Usagi, with additional changes, fixes, and native performance work. Usagi itself is derived from Kotatsu, so this project keeps visible credit for both upstream projects and continues under the same open-source license obligations.

The app does not ship copyrighted catalogs or hosted content. Sources, plugins, and local files are controlled by the user, and users are responsible for following the rules of the services and files they access.

## Highlights

- Browse, search, filter, and organize manga/comics from supported sources and local archives.
- Read in paged, continuous, and webtoon-friendly layouts with gestures, bookmarks, history, and reader customization.
- Save chapters for offline reading as directories or CBZ archives.
- Manage large libraries with categories, reading status, favorites, updates, and local manga indexing.
- Install and manage source plugins, including external GitHub-hosted plugins.
- Track reading progress with supported tracking services.
- Sync app data when a compatible sync backend/account is configured.
- Protect the app with password or biometric lock options.
- Run on older Android devices while still using modern Material styling where available.

## In-App Screenshots

## Screenshots

| Library | Explore | Feed |
|----------|----------|----------|
| <img src="./.github/assets/library-tab.png" width="220"> | <img src="./.github/assets/explore-tab.png" width="220"> | <img src="./.github/assets/feed-tab.png" width="220"> |

| History | Book Details |
|----------|----------|
| <img src="./.github/assets/history-tab.png" width="220"> | <img src="./.github/assets/book-modal.png" width="220"> |

## Download Reliability

Miyo includes a smarter download pipeline designed for slow and unreliable sources:

- Adaptive parallelism chooses safer defaults for low-end devices and stronger concurrency on capable devices.
- Smart queue orchestration prioritizes active work and spreads downloads across sources to reduce source-specific stalls.
- Download doctor messages surface likely source stalls, retry waits, and rate-limit slowdowns in download UI and notifications.
- Image proxy and mirror handling give providers more recovery paths when direct image requests fail.
- Native image probing rejects empty, corrupt, or non-image responses before they are written into the local library.
- Background notifications keep pause and cancel actions tied to the active worker state.
- Optional page refinement uses the bundled `general-x4v3` refinement profile in the APK, with originals kept as fallback.

## Performance

Miyo uses a mix of Kotlin and native C++ helpers where native work provides practical value:

- Native image metadata probing for fast MIME, width, height, and corruption checks.
- Native CBZ/ZIP writing for lower overhead archive generation during downloads.
- Reader memory governance that estimates decoded page size and reduces prefetch pressure on very large images.
- Adaptive preload limits that respect power-save mode, available RAM, and recent page size.
- Cover caching and reader task cleanup to reduce repeated decoding and stale work.

These optimizations are intentionally conservative: if native helpers are unavailable on a device, the app falls back to the existing Kotlin/JVM path.

## Reader Features

- Multiple page layouts for standard manga, webtoon-style chapters, and local archives.
- Page trimming, preview loading, cache reuse, and image request headers tuned for manga sources.
- Downloaded and cached pages can be read offline.
- Bookmarks, reading history, incognito reading, and per-title reading state.
- Share and save page actions from the reader.

## Library And Local Files

- Favorites and custom categories.
- Local manga directories and CBZ archive parsing.
- Backup and restore support.
- Update checks for new chapters.
- Source-specific settings where supported.

## Plugins

Miyo supports source plugins so the core app can stay separate from source-specific logic. External plugin support lets users install compatible plugins from GitHub releases or configured repositories.

Plugin compatibility depends on the plugin API used by the app version. When a plugin fails, update both Miyo and the plugin before reporting an issue.

## Releases

Official public builds are distributed through [GitHub Releases](https://github.com/Torro101/MIYO/releases). Android will only install an update over an existing installation when the package name and signing certificate match the installed app.

For public distribution:

- Keep the same application ID for updates to the same app line.
- Keep the same release signing key for every future release.
- CI publishes a `miyo-release-apk-with-general-x4v3` artifact on main/tag builds and verifies that the bundled `general-x4v3` model is inside the release APK.
- Publish release notes with APK checksums and relevant compatibility notes.
- Do not publish private keys, keystores, passwords, tokens, or CI secrets.

## Building

This project is an Android/Kotlin application with native C++ sources. A normal Android development environment with the Android Gradle Plugin, Android SDK/NDK, CMake, and a JDK is required to build it locally.

Release signing should be configured through private local files or encrypted CI secrets. Never commit signing material to the repository.

## Contributing

Issues and pull requests are welcome when they are specific, reproducible, and scoped. Good reports include:

- Device model and Android version.
- App version and commit, if known.
- Source/plugin name, if the issue depends on a source.
- Screenshots or logs when they help explain the failure.
- Steps to reproduce from a clean state.

For code contributions, keep changes focused, follow existing architecture, and include tests or clear verification notes when possible.

## Security And Content Policy

Miyo is a reader application. It does not host, sell, or own third-party content. The maintainers are not affiliated with external content providers or plugins unless explicitly stated.

Report security-sensitive issues privately when possible. Do not open public issues containing tokens, private URLs, account data, or signing material.

## Credits

Miyo is derived from Usagi and carries forward architecture, reader behavior, parser ecosystem ideas, and GPL-licensed work from Kotatsu. This fork adds Miyo-specific branding, UI work, download reliability improvements, native performance helpers, and release infrastructure. Thanks to the Usagi and Kotatsu maintainers and to everyone who contributes code, translations, testing, reports, and design feedback.

## License

Miyo is licensed under the [GNU General Public License v3.0](LICENSE). You may use, study, modify, and redistribute the software under the terms of that license.
