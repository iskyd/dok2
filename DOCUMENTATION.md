# Documentation

Technical reference for the app. This is the living document — it describes how the app works, and it should be updated when behaviour changes.

Two planning artifacts sit alongside it and are frozen: the design doc (why each choice was made, with rejected alternatives) and the implementation plan (build order). This file is the one that has to stay true.

---

## Contents

1. [Overview](#overview)
2. [Priorities](#priorities)
3. [Architecture](#architecture)
4. [Location acquisition](#location-acquisition)
5. [Filter chain](#filter-chain)
6. [Recording state machine](#recording-state-machine)
7. [Elevation](#elevation)
8. [Data model](#data-model)
9. [Map and elevation data](#map-and-elevation-data)
10. [Permissions](#permissions)
11. [Battery](#battery)
12. [Export](#export)
13. [Testing](#testing)
14. [Known device problems](#known-device-problems)
15. [Glossary](#glossary)

---

## Overview

An Android app that records GPS tracks while hiking and displays them on an offline vector map. Everything stays on the device. There is no account, no server, and no network permission.

Minimum SDK 26 (Android 8.0). Kotlin throughout.

---

## Priorities

In strict order:

1. **Battery.** A full day of recording costs a small fraction of the battery; a multi-day trek on a power bank is viable.
2. **Speed.** Cold start to recording in under a second. No spinners anywhere.
3. **Privacy.** No account, no analytics, no third-party SDKs, no network permission.
4. **Accuracy.** Deliberately last. Hiking tolerance is metres.

Point 4 is load-bearing. It is why there is no Kalman filter, no sensor fusion with accelerometry, and no high-rate sampling. Most complexity in fitness trackers exists to serve runners who care about pace to the second.

---

## Architecture

```
:domain     pure Kotlin JVM — no Android imports
:data       Android — persistence and file I/O
:app        Android — service, UI, map
```

Dependencies flow one way: `:app` → `:data` → `:domain`. `:domain` depends on nothing.

The build enforces the no-Android rule in `:domain`. This is what allows the filter chain, state machine and elevation logic to be tested as plain JVM unit tests against recorded fixtures, which in turn is what makes them tunable without walking up a hill.

### Runtime shape

```
┌─ RecordingService (foreground, type=location) ───┐
│   LocationManager ─► FilterChain ─► Repository   │  source of truth
│   SensorManager (pressure) ─┘                    │
└──────────────────────────────────────────────────┘
                      │ StateFlow<RecordingState>
┌─ UI process (disposable) ────────────────────────┐
│   Library · Live · Map · Settings                │
└──────────────────────────────────────────────────┘
```

The service must function with no UI process alive. The UI observes and never writes recording state.

---

## Location acquisition

**`LocationManager` with `GPS_PROVIDER` only.** No Google Play Services, no `FusedLocationProviderClient`, no network-derived positions.

Consequences:

- Works on de-Googled ROMs; eligible for F-Droid.
- The app never touches the network to obtain a position — this is a property of the code, not a policy.
- Cold fix takes 20–45 s without Google's A-GPS (carrier SUPL still applies). Warm fix is 2–5 s. Recording starts immediately; the first fix arrives when it arrives.

**Interval: 3 seconds, fixed.**

Why not longer: a GNSS receiver in continuous tracking mode does not power down between fixes unless the gap exceeds roughly 30 s. Sampling every 5 s costs approximately the same RF power as every 1 s. Longer intervals save application-processor wakeups and database writes, not radio power. 3 s gives good switchback fidelity at walking pace, and storage is a non-issue: at 40 bytes per point, an 8-hour hike is under 400 KB.

Why not adaptive: it cuts corners on switchbacks, which is exactly where hikers want fidelity.

`minTimeMs` is a hint. Fixes arrive faster and slower than 3 s. **Always timestamp from the fix, never from the clock at callback time.**

---

## Filter chain

Three gates, applied in order, in `domain/.../FilterChain.kt`.

### Gate 1 — accuracy

Reject any fix reporting horizontal accuracy worse than **30 m**.

Tighter gates cause dropouts under canopy and produce gappy tracks. 30 m is loose by fitness-app standards and correct here.

### Gate 2 — implausible speed

Reject any fix implying more than **10 m/s** from the last accepted fix. Catches the "teleport across the valley" glitch and, usefully, chairlifts and bus rides.

### Gate 3 — minimum displacement, anchor-based

This is the important one and the easiest to get subtly wrong.

The chain holds an **anchor**: the last point that advanced the odometer. A new fix is compared against the anchor, not against the previous fix.

```
threshold = clamp(max(4 m, accuracy × 1.0), .., 30 m)

if distance(anchor, fix) >= threshold:
    accumulate distance, move anchor to fix    → Accepted
else:
    store the point, do not accumulate         → Stationary
```

**Why the anchor.** Comparing fix-to-fix, a slow ascent at 2 km/h moves you 1.7 m per 3-second interval — below any sane threshold — so every fix is discarded and the odometer reads zero for the whole climb. With an anchor, slow movement accumulates correctly while standing still accumulates nothing.

This single mechanism is the difference between a tracker that reports phantom kilometres during a lunch stop and one that doesn't.

The threshold scales with reported accuracy (a fix claiming 12 m accuracy must move 12 m) and is clamped at 30 m so sustained bad reception cannot freeze the odometer permanently.

### No position smoothing

None. No Kalman, no moving average. Raw positions with the three gates are correct for hiking. Smoothing rounds off switchbacks.

The *displayed* speed value is smoothed over a 30-second window so the number doesn't flicker. That happens in the UI layer and never touches stored data.

### Constants

`maxAccuracyM`, `maxSpeedMps`, `minDisplacementM` and `displacementAccuracyFactor` are exposed on the debug settings screen. They were tuned against real fixtures — see [Testing](#testing) before changing them.

---

## Recording state machine

```
             startCalibration       beginRecording
        IDLE ──────────────► CALIBRATING ────────► RECORDING ◄─────────┐
                                                         │  ▲             │
        no displacement 60 s                            │  │ displacement│ resume
                                                         ▼  │             │
                                                   AUTO_PAUSED            │
                                                                            │
        RECORDING ──user pause──► MANUAL_PAUSED ───────────────────────────┘
        AUTO_PAUSED ──user pause──► MANUAL_PAUSED

        any state ──stop──► IDLE (track finalised)
```

### Rules

- **A fresh recording enters `CALIBRATING` first.** For up to 60 s the barometer solves its baseline against GNSS altitudes; no trackpoints are written and no clock runs. When the phase ends the UI reports the outcome — baseline solved, or the 60 s window elapsed with the standard baseline in use — and the user taps *Start recording*. The start-anyway check runs a few seconds after the window so the baseline-solving fix can land first. Barometer-less devices skip the phase; resuming an interrupted track starts recording immediately.
- **Auto-pause** triggers after 60 s with no accepted displacement, and clears automatically on the next accepted displacement.
- **Manual pause** is entered and left only by the user. **Auto-pause logic is suspended while manually paused** — movement does not silently resume the track. This is the point of manual pause: you are being driven to a trailhead, or wandering around camp.
- **Distance, elevation gain and moving time accumulate only in `RECORDING`.** Elapsed time accumulates in all non-idle states except `CALIBRATING`.
- **Trackpoints are written in every state**, tagged with `state`. Pausing affects accumulation, not storage. Never discard raw data.
- Every transition is written to `pause_events`, so the track is exactly reconstructable from the database.

### Manual pause as a battery feature

After 2 minutes in `MANUAL_PAUSED`, the GNSS request interval drops from 3 s to 30 s. You are at camp; three-second fidelity of your own feet is not required.

GNSS is **not** switched off. The slow trickle keeps ephemeris data fresh, making resume a 1–2 s warm reacquisition rather than a 20–45 s cold one. The power saved by switching off entirely is small; the UX cost is large.

The 3 s interval is restored immediately on resume.

### Notification actions

The persistent notification carries **Pause/Resume**, **Waypoint** and **Stop**. During the calibration phase the actions are **Start recording** and **Cancel** instead. The waypoint action matters: marking a spring or a junction must never require unlocking the phone.

---

## Elevation

GNSS vertical error is 2–3× horizontal. Naively summing GNSS altitude deltas over a flat 10 km walk reports several hundred metres of climb. Elevation gain is the number hikers care most about, so it gets a hybrid treatment.

### Live — barometer

`Sensor.TYPE_PRESSURE` at ~5 Hz (SENSOR_DELAY_UI). Costs under a milliwatt.

```
altitude = 44330 × (1 − (p / p₀)^(1/5.255))
```

**Calibration.** Happens during the `CALIBRATING` phase, before recording starts: for up to 60 s, collect GNSS altitudes with accuracy better than 15 m, take the median, and solve for `p₀` so the barometric altitude matches. If no usable GNSS altitude arrives, fall back to 1013.25 hPa and set `tracks.calibrated = 0` — recording then starts anyway, and the UI reports whether the baseline was solved or the window elapsed.

**Drift correction.** Weather shifts the baseline by 50–100 m over a day. Every 5 minutes:

```
error = median(recent good GNSS altitudes) − current barometric altitude
p₀ += clamp(error × K_DRIFT, −0.5, +0.5)     // ≈30 min time constant
```

The clamp is what makes this safe: one bad GNSS altitude cannot yank the baseline, but a genuine weather front is tracked over half an hour.

**Smoothing.** Instantaneous barometer samples are noisy: a footstep, a gust of wind across the sensor port, or the phone bouncing in a pocket produces pressure spikes of 1–2 hPa (≈8–16 m) that last a fraction of a second. The altitude is computed from the median of the last 10 samples (~2 s at the 5 Hz rate), never from a single sample. A spike occupies at most a few window slots and cannot move the median; the raw samples are still what gets stored in `trackpoints.pressure_hpa`. Without this, spikes regularly exceed the 5 m hysteresis below and the accumulator records hundreds of metres of phantom gain on a flat walk.

**Hysteresis.** Gain and loss accumulate only after a sustained 5 m move in one direction:

```
if alt − ref >  5: gain += alt − ref; ref = alt
if ref − alt >  5: loss += ref − alt; ref = alt
```

Without this, sensor noise alone produces hundreds of metres of phantom gain. The trade-off is under-reporting gentle rolling terrain; that is intentional.

### Final — DEM, computed on save

When a track is finalised: resample to one point every ~15 m, look up the elevation of each from the on-device DEM, run the same hysteresis accumulator, and write the result to `gain_dem_m` / `loss_dem_m`. Barometric figures stay in `gain_baro_m` / `loss_baro_m`.

The DEM figures are what the UI displays when available. This costs zero battery — it runs after the hike.

If the relevant DEM tile is missing, fall back to the barometric figures and flag the track.

### DEM format

**Raw SRTM `.hgt` files.** One file per 1° cell, big-endian `int16`, 3601×3601 at 1 arc-second (~25 MB, ~30 m resolution) or 1201×1201 at 3 arc-seconds (~2.8 MB).

Chosen over terrain-RGB raster tilesets because it is about 40 lines of code with no dependencies: memory-map, index by row and column, bilinear interpolate. Voids are encoded as `−32768` and must be skipped.

```
row = (1 − (lat − floor(lat))) × (size − 1)
col =      (lon − floor(lon))  × (size − 1)
```

### Datum

GNSS reports height above the WGS84 ellipsoid; humans expect height above mean sea level (EGM2008 geoid). The app stores WGS84 and converts on export. Divergence is up to ±100 m depending on location, so this matters for absolute altitude but not for gain.

---

## Data model

SQLite via Room, WAL mode. Inserts batched in a transaction every 10 points (30 s), so a process kill costs at most 30 seconds.

```sql
CREATE TABLE tracks (
  id              INTEGER PRIMARY KEY,
  name            TEXT,
  activity_type   TEXT    NOT NULL DEFAULT 'hike',
  started_at      INTEGER NOT NULL,
  ended_at        INTEGER,
  distance_m      REAL    NOT NULL DEFAULT 0,
  moving_time_s   INTEGER NOT NULL DEFAULT 0,
  elapsed_time_s  INTEGER NOT NULL DEFAULT 0,
  gain_baro_m     REAL,
  loss_baro_m     REAL,
  gain_dem_m      REAL,
  loss_dem_m      REAL,
  sea_level_hpa   REAL,
  calibrated      INTEGER NOT NULL DEFAULT 0,
  thumbnail_path  TEXT,
  notes           TEXT
);

CREATE TABLE trackpoints (
  track_id      INTEGER NOT NULL,
  seq           INTEGER NOT NULL,
  t_ms          INTEGER NOT NULL,
  lat_e7        INTEGER NOT NULL,
  lon_e7        INTEGER NOT NULL,
  accuracy_m    REAL    NOT NULL,
  alt_gnss_m    REAL,
  alt_dem_m     REAL,
  pressure_hpa  REAL,
  speed_mps     REAL,
  bearing_deg   REAL,
  state         INTEGER NOT NULL,  -- 0 recording, 1 auto-paused, 2 manual-paused
  accumulated   INTEGER NOT NULL,  -- 1 if this point advanced the anchor
  PRIMARY KEY (track_id, seq)
) WITHOUT ROWID;

CREATE TABLE pause_events (
  track_id  INTEGER NOT NULL,
  t_ms      INTEGER NOT NULL,
  new_state INTEGER NOT NULL,
  PRIMARY KEY (track_id, t_ms)
);

CREATE TABLE waypoints (
  id         INTEGER PRIMARY KEY,
  track_id   INTEGER,
  t_ms       INTEGER NOT NULL,
  lat_e7     INTEGER NOT NULL,
  lon_e7     INTEGER NOT NULL,
  type       TEXT,
  label      TEXT,
  photo_path TEXT
);
```

### Notes

- **Coordinates are `INTEGER` at 1e-7 degrees** — roughly 1 cm resolution, half the bytes of a double, no floating-point comparison bugs. Converted to `Double` degrees at the `:data`/`:domain` boundary and nowhere else.
- **`WITHOUT ROWID` on `trackpoints`** removes the redundant rowid index. Meaningful when this table is 99% of the rows. It also means no `AUTOINCREMENT` — `seq` is generated by the service.
- **Raw columns are never overwritten.** `alt_gnss_m` and `pressure_hpa` exist so every derived figure can be recomputed when the algorithm improves.
- **Migrations are additive only.**

### Encryption

Platform file-based encryption only. SQLCipher costs CPU on every read and write for marginal benefit in this threat model. An optional biometric app lock covers shoulder-surfing.

---

## Map and elevation data

MapLibre Native rendering vector tiles from a **PMTiles** file. One file per region — trivially copyable, deletable and inspectable. The app has no network permission and cannot download anything; data is supplied by the user.

### Preparing a region

1. Download a `.osm.pbf` extract from [Geofabrik](https://download.geofabrik.de).
2. Build a `.pmtiles` file with [Tilemaker](https://tilemaker.org) using the project's profile in `tilemaker/` (`config.json` + `process.lua`). The profile keeps paths, tracks, contours, water, peaks, huts and land cover, and drops building footprints and road classification detail. Fewer layers means faster tile decode, less GPU work and smaller files.
3. Optional: generate contours with `gdal_contour` over SRTM `.hgt` cells and merge them via a shapefile source — Tilemaker has no DEM reader (details in the header of `tilemaker/process.lua`). A build without elevation data simply omits the contour layer.
4. Copy the `.pmtiles` file onto the device.

### Installing a region

- **Settings → Map data → Choose region file** picks the `.pmtiles` from any storage provider. The file is copied into app-private storage (`filesDir/maps/`) under a sanitized name, so the picker URI is not kept and the region survives the source file being moved or deleted.
- The header is validated against the PMTiles v3 spec before anything is written; an invalid or truncated file is rejected without side effects.
- Picking another file replaces the region — the previous file is deleted only after the new copy is complete. Remove clears it. The Map tab binds to the active region and re-renders when it changes.
- The `maps/` directory is excluded from cloud backup and device transfer (`res/xml/data_extraction_rules.xml`): these are 150–400 MB re-obtainable files that must not go to the cloud.

### Sizing

- A country-sized extract at full zoom: 1–3 GB. Dropping max zoom from 15 to 14 roughly halves it.
- A 100 × 100 km hiking region at z15 with contours: 150–400 MB.
- SRTM 1-arcsecond: ~25 MB per 1° cell.

The Settings Map data section shows the active region's name and size.

### MapLibre notes

- **The style JSON must declare a `sprite` URL even though the app uses no sprites.** MapLibre Native on Android crashes on initialisation without it. Undocumented; costs people hours.
- The basemap loads from a local file: `VectorSource` with `pmtiles://file://` + the absolute path. No remote tiles, no remote style.
- The seven basemap layers render exactly the source-layer names in `tilemaker/process.lua` (landuse, water, contour, path, track, peak, hut) — renaming one silently blanks the map.
- The GL surface is destroyed whenever the map is not visible. See [Battery](#battery).

---

## Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
```

`ACCESS_COARSE_LOCATION` exists only because Android 12+ (targetSdk 31+) requires an app that requests
FINE location to also declare COARSE, or the user never gets the coarse-only grant option. FINE
implies COARSE, so it adds no capability. MapLibre's AAR declares it anyway; the app's manifest
simply stops stripping it at merge time.

`ACCESS_NETWORK_STATE` is declared despite the offline design. MapLibre's `ConnectivityReceiver`
registers for `CONNECTIVITY_CHANGE` on every `MapView` creation and calls
`ConnectivityManager.getActiveNetworkInfo()` unconditionally; without the permission the handler
throws `SecurityException` on the main thread, crashing the map screen on any network state
change. It is a normal (non-runtime) permission that only reads connectivity state — it enables
no data transfer, and the missing `INTERNET` permission remains the app's exfiltration-proof
property.

Deliberately absent, and to stay absent:

| Permission | Why not |
|---|---|
| `INTERNET` | The absence is the app's central privacy property — visible in the system permission list, so "cannot exfiltrate your location" is verifiable rather than promised |
| `ACCESS_BACKGROUND_LOCATION` | Unnecessary. A foreground service started while an activity is visible retains location access when backgrounded. Also avoids Android's "allow all the time" dialog |
| `WAKE_LOCK` | Not needed at a 3 s interval; adds baseline drain |
| `ACTIVITY_RECOGNITION` | Only required for the hardware step counter, which is not scheduled |

---

## Battery

### Where the power actually goes

| Component | Approximate draw |
|---|---|
| Display, 6", ~50% brightness | 400–700 mW |
| Map rendering on GPU (on top of display) | 150–400 mW |
| Cellular radio searching with no coverage | 100–500 mW |
| Cellular radio idle with coverage | 5–15 mW |
| Application processor in doze | 30–80 mW |
| **GNSS receiver tracking** | **25–50 mW** |
| Barometer | < 1 mW |

**The GPS is not the problem.** The screen costs roughly ten times the GNSS chip. A cellular radio hunting for a tower in a valley can cost more than both. This drives the whole design:

- The app is built so the user never needs to look at the screen. Audio cues at milestones, notification actions for waypoints and pause.
- Onboarding prompts for **airplane mode** at the trailhead. This is probably the single largest available win and it costs one line of UI.

### Rules enforced in code

- The MapLibre GL surface is destroyed whenever the map is not visible — never kept alive behind another screen, in the back stack, or in the background.
- No `PARTIAL_WAKE_LOCK`. Location callbacks wake the processor anyway at 3 s.
- The magnetometer is registered on resume and unregistered on pause.
- Nothing polls. Everything is driven by location callbacks, sensor callbacks, or the watchdog alarm.
- The track list renders pre-generated PNG thumbnails and never instantiates a map view.

### Targets

| Mode | Target |
|---|---|
| Recording, screen off, airplane mode | 2–4% / hour |
| Recording, screen off, cellular on | 3–6% / hour |
| Recording, map on screen | 15–25% / hour |

These are hypotheses until measured. Measure with `adb shell dumpsys batterystats` plus Battery Historian for attribution, and Perfetto for wakeup counts. The only test that counts is a real six-hour hike with the screen off.

---

## Export

GPX 1.1, written by `domain/.../export/GpxWriter.kt`. Single track or bulk, to a user-chosen folder via the Storage Access Framework.

- Elevation is exported as EGM2008 geoid height (converted from stored WGS84).
- Paused segments are exported as separate `<trkseg>` elements.
- **Privacy-zone trimming:** the user may define a radius around home; exported tracks are trimmed at both ends. Off by default, but the one privacy feature people actually need.
- Photo EXIF GPS is stripped on export by default; the user may opt to keep it.

There is no upload. Move the files with Syncthing, a cable, or anything else.

---

## Testing

### Replay harness

The core logic is pure Kotlin, so it is tested as plain JVM unit tests replaying recorded fixes.

```kotlin
@Test fun `lunch stop does not accumulate phantom distance`() {
    val fixes = loadFixture("valley-lunch-stop.jsonl")
    val result = FilterChain().replay(fixes)
    assertThat(result.distanceM).isWithin(50.0).of(8_420.0)
}
```

Fixtures live in `domain/src/test/resources/fixtures/` as JSONL of raw fixes, captured by the app's **debug recording mode**, which logs every fix with its verdict and rejection reason.

### Fixture corpus

Every real hike should become a fixture. Cases to cover:

- dense forest canopy
- deep valley with sky blocked on both sides
- open ridge (the control case)
- a 45-minute lunch stop
- a tunnel or long rock overhang
- a chairlift or bus ride — must be rejected by the speed gate
- a hike where recording was left running for two hours afterwards

### Changing constants

Filter and elevation constants were tuned against this corpus. **A change that improves one fixture often ruins another.** Show the effect across all fixtures before changing any of them. Existing expectations are ground truth; if your change makes one fail, the change is probably wrong.

### Commands

```bash
./gradlew :domain:test          # seconds; run constantly
./gradlew check                 # lint + all tests
./gradlew :app:assembleDebug
```

---

## Known device problems

### OEM background-service killers

Xiaomi/MIUI, Huawei/EMUI, Samsung, OnePlus and others kill foreground services regardless of what the documentation says. This is the leading cause of lost hikes for every tracker in this category. Mitigations, all shipped:

1. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested during onboarding.
2. OEM detected at onboarding, with device-specific instructions (see dontkillmyapp.com) and an acknowledgement checkbox.
3. An `AlarmManager` watchdog every 3 minutes that restarts the service into the open track.
4. Crash-safe persistence — a kill costs at most 30 seconds.
5. On launch, any track with `ended_at IS NULL` prompts to resume or finalise.
6. A recording-health indicator that warns when the gap between fixes greatly exceeds the expected interval.

### Sensor quirks

- Some devices report a **constant or fabricated accuracy value**. The debug screen shows the raw accuracy distribution; check it on any new device.
- The **barometer responds to wind and to being sealed in a zipped pocket.** Taking the phone out produces a pressure step. The 10-sample median window absorbs the instantaneous spikes, the 5 m hysteresis the slower steps, the drift clamp the weather.
- **Cold fix without A-GPS** is 20–45 s. Show satellite count; never block recording on the first fix.

---

## Glossary

| Term | Meaning |
|---|---|
| **Anchor** | The last trackpoint that advanced the odometer. New fixes are compared against it, not against the previous fix |
| **Accepted / Stationary / Rejected** | The three verdicts of `FilterChain`. Rejected points are discarded; Stationary points are stored but do not accumulate distance |
| **Hysteresis** | The 5 m threshold before an elevation change is counted, preventing noise-driven phantom gain |
| **Median window** | The last 10 pressure samples whose median replaces the raw sample in altitude computations, killing instantaneous spikes |
| **Trickle** | The 30 s GNSS interval used after 2 minutes of manual pause, keeping ephemeris fresh for a warm resume |
| **PMTiles** | Single-file vector tile archive format; one file per map region |
| **HGT** | Raw SRTM elevation grid; one big-endian int16 file per 1° cell |
| **FBE** | Android file-based encryption, the platform's at-rest protection |
