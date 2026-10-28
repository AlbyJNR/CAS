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

Open this repository's **Releases** page, select **CAS preview**, then download **CAS.apk** under **Assets**.

Install the APK on a compatible Android TV or Fire TV device running Android 8.0 or newer. Open CAS and sign in to Stremio. Configure your account and debrid-based add-ons in Stremio first.

This is a test build: compilation and the existing unit tests pass, but TV layout, navigation and playback still need hardware testing. CI debug signing keys can change between builds; a later preview may require reinstalling.

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
