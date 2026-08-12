# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

Offline-first GPS track recorder for hiking, Android only, Kotlin. Three priorities in strict order: **battery, speed, privacy.** Accuracy is explicitly deprioritised — hiking tolerance is metres.

No account, no cloud, no network access. The absence of `INTERNET` permission is the app's central privacy property.

## Build Commands

```bash
./gradlew :domain:test          # Fast JVM tests — run constantly
./gradlew check                 # Lint + all tests
./gradlew spotlessApply         # Format code (required before commit)
./gradlew :app:assembleDebug    # Build debug APK
./gradlew :app:installDebug     # Install to connected device
```

## Architecture

```
:domain   pure Kotlin JVM — models, FilterChain, elevation, GPX export
:data     Android — Room/SQLite, file I/O, DEM tile loading, preferences
:app      Android — foreground service, Compose UI, MapLibre
```

Dependencies flow one way: `:app` → `:data` → `:domain`. `:domain` depends on nothing.

**Critical constraint:** `:domain` must contain zero Android imports. No `android.*`, `androidx.*`, `Context`, or `Log`. The build fails if this is violated. This enables the replay test harness to run as plain JVM tests.

### Key Components

| Concern | Location |
|---|---|
| Fix filtering, anchor logic | `domain/.../FilterChain.kt` |
| DEM lookup/interpolation | `domain/.../elevation/HgtReader.kt` |
| GPX export | `domain/.../export/GpxWriter.kt` |
| Room schema | `data/.../db/` |
| Foreground service | `app/.../recording/RecordingService.kt` |
| Replay fixtures | `domain/src/test/resources/fixtures/` |

### Runtime Model

`RecordingService` (foreground service, type=location) is the source of truth. It owns recording state via `StateFlow<RecordingState>`. The UI observes and never writes recording state. The service must function with no UI process alive.

## Hard Constraints

Do not violate these. If a task appears to require one, stop and ask.

### Never add these permissions
- `INTERNET` — the privacy property
- `ACCESS_BACKGROUND_LOCATION` — not needed with foreground service
- `WAKE_LOCK` — adds baseline drain at 3s location interval

### Never add these dependencies
- Google Play Services (any form, including FusedLocationProviderClient)
- Analytics/telemetry/crash-reporting SDKs
- Networking libraries

### Never do these
- Delete/overwrite raw sensor data (`alt_gnss_m`, `pressure_hpa`)
- Discard trackpoints during pause — points are stored in every state
- Add position smoothing (Kalman, moving average) — rounds off switchbacks
- Add adaptive sampling — cuts corners on switchbacks

### Battery rules
- Destroy MapLibre GL surface whenever map is not visible
- No `PARTIAL_WAKE_LOCK`
- Never render/animate/poll while recording in background
- Magnetometer registered on resume, unregistered on pause
- No polling loops — everything event-driven

## Testing

Changes to `FilterChain`, elevation code, or state machine require replay tests. Fixtures are JSONL of raw fixes from real hikes in `domain/src/test/resources/fixtures/`.

Do not change filter constants (`maxAccuracyM`, `maxSpeedMps`, `minDisplacementM`) without showing effect on all fixtures. Existing fixture expectations are ground truth.

## Code Style

Kotlin official style via ktfmt. Run `./gradlew spotlessApply`. Conventional commits (`feat:`, `fix:`, `perf:`, etc.).

Coordinates: `Int` at 1e-7 degrees at storage layer, `Double` degrees in domain layer. Convert at boundary only.

## Branching and Releases

- **Always work on a feature branch** — never commit directly to `main`. Use `feature/`, `fix/`, or `refactor/` prefixes.
- **PR merges to `main` trigger CI** — builds release APK and creates a GitHub Release.
- **Update version before merging** — edit `version.properties` (patch/minor/major per semver).
- **Update CHANGELOG.md** — add entry under `[Unreleased]` following Keep a Changelog format.
