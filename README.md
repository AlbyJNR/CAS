# CAS

A quieter way to watch.

CAS is a minimalist Android TV and Fire TV app for your Stremio account. Cinematic artwork, charcoal surfaces, clean typography and straightforward remote controls keep the focus on what you want to watch.

## Features

- Choose a film or programme and press Play.
- Automatic source selection and fallback.
- Separate profiles with individual watch history.
- Continue Watching, voice search and intro skipping when available.
- A simple home screen with Home, TV, Movies and Library.

## Install the preview

On a Fire TV, open the **Downloader** app and enter this address exactly:

```
https://github.com/AlbyJNR/CAS/releases/download/cas-preview/CAS.apk
```

Choose **Go**, then **Install**, then open CAS. The address never changes: every push to the build branches replaces CAS.apk with the newest tested build, so entering it again updates CAS.

On Android TV or Google TV, download the same file with any sideloading tool, or open this repository's **Releases** page and pick **CAS.apk** under **CAS preview**.

CAS needs Android 8.0 or newer. Open CAS and sign in to Stremio. Configure your account and debrid-based add-ons in Stremio first.

This is a test build: compilation and the unit tests pass, but TV layout, navigation and playback still need hardware testing. Previews are release builds signed with the CAS key, so each one installs over the last as an update and keeps your profiles.

## Development

Open the project in Android Studio with JDK 17 and Android SDK 36, or run:

```sh
bash gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. The installed application ID is `uk.albyjnr.cas`.

For production builds, configure a stable private release signing key using `keystore.properties`, then run `assembleRelease`. Keep keys and passwords out of Git.

See [design notes](docs/CAS.md) for the hardware review checklist. Regenerate the CAS launcher artwork with `python tools/cas_brand.py` (Pillow required).

## Open-source notices

CAS is distributed under [GPL-3.0](LICENSE). Copyright, project acknowledgements and third-party notices are in [NOTICE.md](NOTICE.md).

## Releasing

Previews publish themselves on every push. For a numbered release, set `versionName` and `versionCode` in `app/build.gradle.kts`, move the changelog entries under a matching `## x.y.z` heading, then push a tag:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

CI builds, signs and publishes `CAS.apk` as release `v1.0.0`. Installed TVs are offered it by CAS's own updater. Signing uses the repository secrets `CAS_KEYSTORE_BASE64`, `CAS_KEYSTORE_PASSWORD`, `CAS_KEY_ALIAS` and `CAS_KEY_PASSWORD`; without them a build falls back to a debug key.
