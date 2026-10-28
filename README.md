# CAS

A quieter way to watch.

CAS is a minimalist Android TV and Fire TV app for your Stremio account. Cinematic artwork, charcoal surfaces, clean typography and straightforward remote controls keep the focus on what you want to watch.

## Features

- Choose a film or programme and press Play.
- Automatic source selection and fallback.
- Separate profiles with individual watch history.
- Continue Watching, voice search and intro skipping when available.
- A simple home screen with Home, Shows, Movies and Library.

## Install the preview

On a Fire TV, open the **Downloader** app and enter this address exactly:

```
https://github.com/AlbyJNR/CAS/releases/download/cas-preview/CAS.apk
```

Choose **Go**, then **Install**, then open CAS. The address never changes: every push to the build branches replaces CAS.apk with the newest tested build, so entering it again updates CAS.

On Android TV or Google TV, download the same file with any sideloading tool, or open this repository's **Releases** page and pick **CAS.apk** under **CAS preview**.

CAS needs Android 8.0 or newer. Open CAS and sign in to Stremio. Configure your account and debrid-based add-ons in Stremio first.

This is a test build: compilation and the unit tests pass, but TV layout, navigation and playback still need hardware testing. Preview builds use a CI debug signing key, so a later preview may ask you to uninstall the earlier one.

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
