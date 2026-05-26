---
name: edge-impulse-data-collection
description: Add a new sensor source (phone sensor, BLE peripheral, audio,
  image, location, etc.) to the Edge Impulse Data Collector Android app and
  wire it through to Edge Impulse ingestion. Use this skill whenever the user
  asks to "add a <sensor> source", "capture <signal> and upload to Edge
  Impulse", "wire up a new BLE characteristic", or similar in this codebase.
metadata:
  version: "1.0"
  author: edgeimpulse
---

# Adding a new data source to the EI Android Data Collector

This codebase already implements phone IMU/PPG, GPS, microphone, camera image,
Wear OS relay, and Zephyr BLE central. New sources follow the same pattern.

## Architecture (read this first)

- `SensorCollector.kt` — wraps the Android `SensorManager` and emits
  `SensorData` on a `MutableSharedFlow`. Other collectors (`LocationCollector`,
  `AudioFileRecorder`, `ZephyrBLEClient`, `WearOSClient`) follow the same
  collector-emits-flow shape.
- `SensorViewModel.kt` — single ViewModel that owns every collector, exposes
  `collectSourceOptions: List<String>` for the UI dropdown, and routes the
  selected option in `startSensorForDuration(sensorType, durationMs)`.
- `DataRepository.kt` — the **only** place that talks to the EI ingestion API.
  Offline samples are written as CSV via `saveSensorData` and later flushed
  with `uploadStoredCsvFiles`. Images use `uploadImage`, audio uses
  `uploadAudio`. Do not add a new HTTP client.
- `MainActivity.kt` — Compose UI. The Collect screen reads
  `viewModel.collectSourceOptions` for its dropdown and calls
  `viewModel.startSensorForDuration(...)`. Runtime permissions are declared in
  `optionalPermissions()`.

## Step-by-step

1. **Build the collector.** Either add a sensor type to `SensorCollector.kt`
   (preferred for anything exposed by `SensorManager` — barometer,
   magnetometer, gyroscope, etc.) or create a sibling class
   (`MySourceCollector.kt`) that exposes `data: SharedFlow<SensorData>` and
   `start()`/`stop()` methods. Match the shape of the existing collectors.

2. **Expose it in the UI.** Add a human-readable label to
   `SensorViewModel.collectSourceOptions` so it appears in the Collect-screen
   "Sensor" dropdown. Keep labels short — they're shown in a small chip.

3. **Route it.** In `SensorViewModel.startSensorForDuration(sensorType, …)`,
   add a branch for the new option that starts the collector, sets the active
   label, and stops after `durationMs`. Use the same offline-vs-online split
   as the existing branches (write CSV via `DataRepository.saveSensorData`
   when offline, or stream via the existing ingestion path).

4. **Upload.** Reuse `DataRepository`:
   - Numeric samples → `saveSensorData(...)` + later `uploadStoredCsvFiles()`.
   - Images → `dataRepository.uploadImage(bytes, label)` (suspend, returns
     `Boolean`).
   - Audio → `dataRepository.uploadAudio(wavBytes, label)`.
   Do **not** introduce a new `OkHttpClient` or hand-rolled multipart code —
   the existing helpers already handle `x-api-key`, `x-label`, retries, and
   the ingestion-vs-remote-management split.

5. **Permissions.** Declare any new runtime permission in
   `app/src/main/AndroidManifest.xml` and add it to
   `MainActivity.optionalPermissions()` so it gets requested at launch. BLE
   permissions vary by Android version — copy the existing
   `Build.VERSION.SDK_INT` gating from the `BLUETOOTH_*` block.

6. **Test.** Add a unit test next to the existing ones, e.g.
   `app/src/test/java/com/edgeimpulse/gattsensors/MySourceCollectorTest.kt`.
   Follow `SensorDataTest.kt` / `voice/VoiceCommandParserTest.kt` as the
   pattern (JUnit 4, no Robolectric unless absolutely needed).

7. **Wear OS / GATT relay (optional).** If the source also runs on the watch
   or the EI-Monitor firmware, mirror the addition in
   `wearosdatalogger/WearSensorBus.kt` (watch) or
   `ZephyrBLEClient.kt` + `GattProfile.kt` (BLE). The phone aggregates both
   via `GattServerManager` — you usually do **not** need to touch that.

## Verification

After your change, this must still hold:
- `./gradlew :app:compileDebugKotlin` passes.
- `./gradlew :app:testDebugUnitTest` passes.
- The new source appears in the Collect-screen "Sensor" dropdown and, when
  selected, produces either a new CSV under the app's offline directory
  (visible from the Datasets screen) or a successful ingestion upload
  (status row turns green with "Uploaded N samples").

## Pitfalls

- Do not call ingestion HTTP from the ViewModel or UI — always go through
  `DataRepository`.
- Do not block the main thread in a collector — emit on a background
  dispatcher and let `SensorViewModel` collect on `viewModelScope`.
- `SensorData.values` is a `FloatArray`; preserve channel order across all
  samples in a session or Studio will reject the upload.
- For new BLE characteristics, add the UUID to `GattProfile.kt` so the
  Zephyr firmware repo (`ei-zephyr-ble-gatt-client/`) can stay in sync.
