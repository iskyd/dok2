# dok2

An offline GPS track recorder for hiking. Android only.
It records where you walked, shows you where you are on a map that works with no signal, and keeps everything on the device. No account, no cloud, no network access.

## What it is not

No social feed, no live location sharing, no turn-by-turn navigation, no training plans, no segments, no leaderboards, no route discovery.

## Status

Pre-alpha. Nothing works yet. See [DOCUMENTATION.md](DOCUMENTATION.md) for the design and the build order.

## Install

### Obtainium (recommended)

[![Get it on Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](obtainium://add/https://github.com/iskyd/dok2)

Or add manually: `https://github.com/iskyd/dok2` or `https://codeberg.org/iskyd/dok2`

### Manual download

Download the latest APK from [GitHub Releases](https://github.com/iskyd/dok2/releases) or [Codeberg Releases](https://codeberg.org/iskyd/dok2/releases).

## Requirements

- Android 8.0 (API 26) or later
- A barometer, for useful elevation figures. Works without one, less well.
- Offline map and elevation files, copied to the device by hand (see below)

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Getting maps onto the device

The app has no network access, so it cannot download anything. You supply the data:

1. Build a `.pmtiles` file for your region with [Tilemaker](https://tilemaker.org) from a [Geofabrik](https://download.geofabrik.de) extract, using the profile in `tilemaker/`.
2. Copy the file onto the phone.
3. In the app: Settings → Map data → Choose region file, and pick it.

Details in [DOCUMENTATION.md](DOCUMENTATION.md#map-and-elevation-data).

## Exporting

Tracks export as GPX to a folder of your choosing. Move them to a server with Syncthing, a cable, or whatever you like — the app deliberately has no idea your server exists.

## Licence

TBD. AGPL-3.0 or Apache-2.0.
