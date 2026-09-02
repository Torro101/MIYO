# Contributing to Miyo

Thank you for your interest in improving Miyo. This document covers the practical ground rules for issues, pull requests, and local development.

## Issues

+ If you want to **fix bugs** or **implement new features** that already have an [issue card](https://github.com/Torro101/MIYO/issues), please assign the issue to yourself and/or comment on it so work is not duplicated.
+ If you want to **implement a new feature**, open an issue or discussion first to make sure it will be accepted.
+ Good bug reports include: device model, Android version, app version, the source or plugin involved (if any), and steps to reproduce from a clean state.
+ Do not open public issues containing tokens, private URLs, account data, or signing material.

## Pull requests

+ Keep changes focused — one topic per pull request.
+ Follow the existing architecture and code style.
+ Include tests or clear verification notes when possible.
+ **Translations** are managed using the [Weblate](https://hosted.weblate.org/engage/miyo/) platform.
+ In case you want to **add a new manga source**, refer to the [parsers repository](https://github.com/YakaTeam/kotatsu-parsers) instead.

**Refactoring** or developer-facing improvements might also be accepted. However, please stick to the following principles:

+ **Performance matters.** When choosing between source code beauty and performance, performance wins.
+ **Avoid adding new dependencies** unless required. APK size is important.

## Development setup

1. Install JDK 21 and the Android SDK (API 36) with NDK and CMake.
2. Clone the repository and open it in Android Studio, or build from the command line:

   ```bash
   ./gradlew assembleDebug
   ```

3. Release signing should be configured through private local files or CI secrets. Never commit signing material, keystores, passwords, or tokens.
