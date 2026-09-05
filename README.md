# RomMDroid

A lightweight Android client for [RomM](https://github.com/rommapp/romm) focused on downloading
games to your device's ROM collection. Think [Grout](https://grout.romm.app/) but for Android —
browse platforms, find games, and get them into your emulator's folders. That's it.

## Features

- Browse platforms and ROM library
- Search your collection
- Download ROMs directly to per-platform folders (via Android Storage Access Framework)
- Offline browse from local cache (Room/SQLite)
- Incremental sync — only fetches what changed since last sync
- Background download queue with progress notifications (WorkManager)
- Firmware / BIOS download
- Client API Token auth (no password stored after setup)

## Non-goals

- Launching games / emulator integration
- Full RomM frontend (collections, metadata editing, scraping)
- Save sync (future)

## Development

### Prerequisites (NixOS)

```bash
# Enter the dev shell (installs JDK 21, Android SDK, Gradle, openapi-generator-cli, adb)
nix develop

# Or with Android Studio:
nix develop .#withStudio
android-studio
```

The shell hook sets `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and `JAVA_HOME` automatically.
Copy `local.properties.example` to `local.properties` — Android Studio will pick up the SDK path
from the environment variables set by the flake.

### Building

```bash
# Debug APK
gradle assembleDebug

# Install to connected device
gradle installDebug

# Or via Nix (produces rommdroid-debug.apk in result/)
nix build
```

### Project structure

```
app/src/main/java/app/rommdroid/
├── data/
│   ├── api/          # Retrofit interface + models + interceptors
│   ├── db/           # Room entities, DAOs, AppDatabase
│   ├── download/     # DownloadWorker (WorkManager)
│   └── repository/   # RomRepository, CredentialRepository
├── di/               # Hilt modules (NetworkModule, DatabaseModule)
├── ui/
│   ├── navigation/   # Route definitions
│   ├── screens/      # One file per screen + its ViewModel
│   └── theme/        # MaterialTheme wrapper
└── util/             # formatSize, etc.
```

### Renaming the app

1. Change `app_name` in `app/src/main/res/values/strings.xml`
2. Change `applicationId` in `app/build.gradle.kts`
3. Rename the package directory `app/rommdroid` → your new package

The display name is intentionally separated from build identifiers so it's easy to change.

### App icon

`design/logo/rommdroid-icon.svg` is the master asset — a ROM cartridge with a download arrow.
Everything else is generated from it:

```bash
python3 design/logo/generate-icons.py
```

That writes the adaptive-icon layers (`drawable/ic_launcher_{background,foreground,monochrome}.xml`),
the `mipmap-anydpi-v26` descriptors, the legacy PNGs at all five densities, and a 512 px
Play Store icon. The master's 512-unit canvas is mapped onto the adaptive icon's 72 dp safe zone,
so the art survives every launcher mask. Needs `resvg` for the PNGs (in the dev shell); the vector
drawables generate without it.

## API

RomMDroid uses the RomM REST API (`/api/*`). The live OpenAPI spec is at
`{your-romm-instance}/openapi.json`. Auth uses **Client API Tokens** (`rmm_…`) stored in
Android Keystore-backed EncryptedSharedPreferences — no password is retained after first setup.

## License

MIT
