# dok2

An offline GPS track recorder for hiking. Android only.
It records where you walked, shows you where you are on a map that works with no signal, and keeps everything on the device. No account, no cloud, no network permission.

## What it is not

No social feed, no live location sharing, no turn-by-turn navigation, no training plans, no segments, no leaderboards, no route discovery.

## Status

Pre-alpha. Nothing works yet. See [DOCUMENTATION.md](DOCUMENTATION.md) for the design and the build order.

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

The app has no network permission, so it cannot download anything. You supply the data:

1. Build a `.pmtiles` file for your region with [Tilemaker](https://tilemaker.org) from a [Geofabrik](https://download.geofabrik.de) extract.
2. Download the SRTM `.hgt` tiles covering the same area.
3. Copy both into a folder on the phone.
4. Point the app at that folder on first launch.

Details in [DOCUMENTATION.md](DOCUMENTATION.md#map-and-elevation-data).

## Exporting

Tracks export as GPX to a folder of your choosing. Move them to a server with Syncthing, a cable, or whatever you like — the app deliberately has no idea your server exists.

## Licence

TBD. AGPL-3.0 or Apache-2.0.
