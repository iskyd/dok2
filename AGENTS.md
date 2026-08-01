# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before making any change.

## What this project is

An offline-first GPS track recorder for hiking, Android only, Kotlin. Three priorities, in this order: **battery, speed, privacy.** Accuracy is explicitly deprioritised — hiking tolerance is metres, not centimetres. When a change trades accuracy against the first three, accuracy loses.

Full technical reference: [DOCUMENTATION.md](DOCUMENTATION.md).

---

## Hard constraints

These are product decisions, not preferences. Do not violate them, and do not "improve" the code by adding any of them. If a task appears to require one, **stop and ask** rather than proceeding.

### Never add these permissions

- `android.permission.INTERNET` — the absence of this permission is the app's central privacy property. It is visible in the system permission list and on F-Droid, which makes "this app cannot exfiltrate your location" verifiable rather than a promise. Nothing is worth trading it for.
- `ACCESS_BACKGROUND_LOCATION` — not needed. The foreground service is started while an activity is visible, which preserves location access when backgrounded.
- `WAKE_LOCK` — see the battery rules below.
- `ACTIVITY_RECOGNITION` — only if the step counter is explicitly scheduled, which it is not.

The complete permission set is in `app/src/main/AndroidManifest.xml`. Adding to it requires a decision recorded in DOCUMENTATION.md.

### Never add these dependencies

- **Google Play Services**, in any form. This includes `FusedLocationProviderClient`, the Activity Recognition API, Google Maps, ML Kit, and Firebase. The app must build and run on a de-Googled ROM.
- **Any analytics, telemetry or crash-reporting SDK.** No Firebase Crashlytics, Sentry, Bugsnag, Amplitude, or equivalent. Crashes write to a local file the user may choose to share.
- **Any advertising or attribution SDK.**
- **Any networking library** — Retrofit, OkHttp, Ktor client. There is no network.

### Never do these things to the data

- **Never delete or overwrite raw sensor data.** `alt_gnss_m` and `pressure_hpa` are stored per trackpoint so algorithms can be re-run later. Derived values go in new columns.
- **Never discard trackpoints because the recording is paused.** Points are written in every state and flagged with `state`. Pausing affects accumulation, not storage.
- **Never migrate a table in a way that loses columns.** Additive migrations only.

### Never add these to the algorithms

- **Position smoothing of any kind** — no Kalman filter, no moving average, no spline fitting. Raw positions with the three gates in `FilterChain` are correct for this use case. Smoothing rounds off switchbacks, which is actively harmful for hiking. Only the *displayed* speed value is smoothed, and that happens in the UI layer.
- **Adaptive or speed-dependent sampling.** The interval is fixed at 3 s. This was tried by other trackers and removed because it cuts corners on switchbacks. The only permitted variation is the 30 s trickle while manually paused.

---

## Architecture rules

### Module boundaries

```
:domain   pure Kotlin JVM — models, FilterChain, RecordingStateMachine,
          statistics, elevation, GPX writer, DEM interpolation
:data     Android — Room/SQLite, file I/O, DEM tile loading, preferences
:app      Android — service, UI, MapLibre, DI wiring
```

- **`:domain` must contain zero Android imports.** No `android.*`, no `androidx.*`, no `Context`, no `Log`. The build fails if this is violated; do not suppress that check. This is what makes the replay harness a plain JVM test.
- `:domain` depends on nothing. `:data` depends on `:domain`. `:app` depends on both.
- Do not create new modules without being asked.

### The service is the source of truth

`RecordingService` owns recording state and exposes a `StateFlow<RecordingState>`. The UI observes it. **The UI must never write recording state, and the service must work correctly with no UI process alive.** Any change that makes the service depend on an Activity or Composable being alive is wrong.

### Battery rules

These have concrete power costs. Do not relax them for convenience.

- **Destroy the MapLibre GL surface whenever the map is not visible.** Never keep a map view alive behind another screen, in a back-stack entry, or in the background. This is the difference between 3–6% and 15–25% battery per hour.
- **No `PARTIAL_WAKE_LOCK`.** At a 3 s location interval the application processor wakes anyway; a wakelock adds baseline drain for nothing.
- **Never render a map, animate, or poll while recording in the background.**
- **The magnetometer is registered on resume and unregistered on pause.** Never left running.
- **No polling loops.** Everything is event-driven off location callbacks, sensor callbacks, or the `AlarmManager` watchdog.
- Before merging anything that touches the service or the map lifecycle, state in the PR description what you expect the power impact to be.

### Performance rules

- Cold start to interactive must stay under 400 ms. No splash screen.
- **The track list must never instantiate a map view.** It renders pre-generated PNG thumbnails written at track-save time.
- No reflection-based dependency injection. Compile-time DI or manual constructor injection.
- No I/O on the main thread, including the first database read.
- R8 full mode stays on. Do not add `-keep` rules without explaining why.

---

## Working practice

### Build and test

```bash
./gradlew :domain:test          # fast, run this constantly
./gradlew check                 # lint + all tests
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

`:domain:test` is a plain JVM test suite and takes seconds. There is no excuse for not running it.

### Testing requirements

- **Any change to `FilterChain`, `RecordingStateMachine`, or the elevation code requires a replay test.** Fixtures live in `domain/src/test/resources/fixtures/` as JSONL of raw fixes captured from real hikes. Add a fixture if none of the existing ones exercises your change.
- Do not change filter constants (`maxAccuracyM`, `maxSpeedMps`, `minDisplacementM`, hysteresis) without showing the effect on every fixture. These were tuned against real data; a change that improves one hike often ruins another.
- Existing fixture expectations are ground truth. If your change makes one fail, the change is probably wrong. Do not update the expected value to match your output without saying so explicitly and explaining why.

### Code style

- Kotlin official style. Formatting is enforced; run `./gradlew spotlessApply`.
- No wildcard imports.
- Public API in `:domain` gets KDoc. Internal code gets comments only where the *why* is non-obvious — the anchor-based displacement logic and the barometric drift clamp are the two places that genuinely need them.
- Prefer `sealed interface` + `when` over nullable returns for anything with more than two outcomes.
- Coordinates are `Int` at 1e-7 degrees at the storage layer and `Double` degrees in the domain layer. Convert at the boundary, never in the middle.

### Dependencies

Adding a dependency requires justification in the PR. The bar is high: this app should build with Kotlin stdlib, coroutines, AndroidX core, Room, and MapLibre, and very little else. Every dependency is APK size, cold-start time, and supply-chain surface.

### Commits

Conventional commits (`feat:`, `fix:`, `perf:`, `refactor:`, `test:`, `docs:`). One logical change per commit. `perf:` commits must state the measured effect, not the intended one.

---

## Where things are

| Concern | Location |
|---|---|
| Fix filtering, anchor logic | `domain/.../FilterChain.kt` |
| Pause/resume state machine | `domain/.../RecordingStateMachine.kt` |
| Barometric calibration and drift | `domain/.../elevation/Barometer*.kt` |
| DEM lookup and interpolation | `domain/.../elevation/HgtReader.kt` |
| GPX writer | `domain/.../export/GpxWriter.kt` |
| Schema and migrations | `data/.../db/` |
| Foreground service | `app/.../recording/RecordingService.kt` |
| Map screen and GL lifecycle | `app/.../map/` |
| Replay fixtures | `domain/src/test/resources/fixtures/` |

---

## Things that look like bugs but are not

Do not "fix" these:

- **`FilterChain` compares against a held anchor, not the previous fix.** This is deliberate. Fix-to-fix comparison would discard every point during a slow ascent (1.7 m per interval at 2 km/h) and the odometer would read zero for the entire climb.
- **Auto-pause is suspended while manually paused.** Movement does not resume a manually paused track. The user's intent overrides the heuristic.
- **GNSS drops to a 30 s interval after 2 minutes of manual pause but is never switched off.** The trickle keeps ephemeris fresh so resuming is a 1–2 s warm reacquisition instead of a 20–45 s cold one.
- **The style JSON declares a `sprite` URL despite the app using no sprites.** MapLibre Native on Android crashes on initialisation without it.
- **Elevation gain uses 5 m hysteresis and will under-report gentle rolling terrain.** Without it, sensor noise alone produces hundreds of metres of phantom gain on flat ground. This trade is intentional.
- **Trackpoints are stored while paused.** See the data rules above.

---

## When to stop and ask

- The task seems to require a forbidden permission or dependency.
- The task requires changing a filter constant or a documented algorithm.
- The task requires `:domain` to know about Android.
- A fixture test fails and you believe the fixture is wrong.
- The change affects the recording service lifecycle or the map GL lifecycle.

In all of these, describe the conflict and wait. Do not work around a constraint by finding a technically-different route to the same outcome.
