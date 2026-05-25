# Code review — Android data collector + Zephyr BLE flow

End-to-end review of the data acquisition app and its companion firmware. Critical items
that change runtime behaviour have already been applied in this commit; the remainder are
left as recommendations.

## ✅ Fixed in this PR

| # | Severity | Area | Issue | File |
|---|----------|------|-------|------|
| 1 | **P0** | Data integrity | Inference upload fabricated a single-sample payload from `result.confidence` when no real sensor data was buffered. This silently poisoned datasets. Now skipped with a warning. | `DataRepository.kt::saveZephyrInferenceResult` |
| 2 | **P0** | BLE relay | Firmware discovered the sensor characteristic but never subscribed to its CCC, so raw IMU windows from `EI-Golioth` were never relayed to Android. Discovery now walks both CCCs and `notify_callback` handles both `value_handle`s. | `ei-zephyr-ble-gatt-client/src/ble/gatt_client.cpp` |
| 3 | **P1** | Reliability | `onDescriptorWrite` ignored `status`, leaving the sensor subscription permanently blocked if the first CCCD write failed. Now logs the error and resets the pending flag. | `ZephyrBLEClient.kt` |
| 4 | **P1** | Battery / leak | `SensorViewModel.onCleared()` did not stop `SensorCollector` or the GATT server, leaving sensor listeners registered after the host Activity was destroyed. | `SensorViewModel.kt` |
| 5 | **P2** | Data loss | CSV writer was never flushed between samples; a crash before `stopOfflineLogging()` lost everything buffered in memory. Now flushes every 20 writes. | `DataRepository.kt::saveSensorData` |
| 6 | **P2** | Race condition | `_scannedDevices` read-modify-write was unsynchronised across BLE callback threads. Now guarded by a lock. | `ZephyrBLEClient.kt` |

## ⚠️ Outstanding recommendations

### P1 — Reliability / data loss

1. **No retry / backoff on HTTPS uploads** (`DataRepository.kt::uploadFile`, `uploadCollectedRemoteSample`, `saveZephyrInferenceResult`, `uploadImage`).
   Any transient `IOException`, timeout, or 5xx response causes silent permanent data loss. Wrap in an exponential-backoff helper (e.g. 3–5 retries: 1s, 2s, 4s).
2. **Auto-connect to first `EI-Monitor` in scan results** (`ZephyrBLEClient.kt::onScanResult`). When more than one device is present the user has no choice. Either remove auto-connect or only auto-connect when the scan list contains exactly one match.
3. **Manual `CoroutineScope(Dispatchers.IO)` in `ZephyrBLEClient`** is never cancelled. If `ZephyrBLEClient` is held by the ViewModel, prefer `viewModelScope` or add an explicit `cleanup()` invoked from `onCleared()`.

### P2 — Polish

4. **CSV format** (`DataRepository.kt::startOfflineLogging`). Values are not quoted; a sensor reading containing a comma (unlikely for IMU floats but possible in future schemas) would produce malformed CSV. Consider Apache Commons CSV or proper escaping.
5. **Build-time API key in `BuildConfig`** (`app/build.gradle.kts`). If a developer commits a populated `gradle.properties`, the APK ships with the plaintext key. The README + docs already recommend `~/.gradle/gradle.properties`; consider adding a `.gitignore` check or build-time warning.
6. **MTU exchange** (`ei-zephyr-ble-gatt-client/prj.conf`). `BT_L2CAP_TX_MTU=247` is configured but never explicitly negotiated. The 52-byte `inference_result_t` fits trivially; if the payload grows, add `bt_gatt_exchange_mtu(conn)` on connect.
7. **`pendingZephyrSensorData` is `MutableList`** — accessed from BLE callback threads and the uploader. Wrap in a thread-safe collection or guard with a lock.

### P3 — Architectural

8. **HMAC signature is `"none"`** for all ingestion requests. Acceptable over HTTPS + API-key auth; document the trade-off if you intend to keep it.
9. **Sensor notifications dropped if Android hasn't subscribed** (`ei-zephyr-ble-gatt-client/src/ble/gatt_server.cpp::gatt_server_notify_sensor_data`). Gate the call on the CCC subscription state instead of letting `bt_gatt_notify` silently drop.
10. **Per-board firmware mode is implicit** (board `.conf` toggles `CONFIG_EI_SENSOR_LOCAL`). The repo README and docs now mention this, but a `west build` without an explicit `-b` leaves users guessing which mode they get.

## BLE flow — verified correct

- UUIDs (service + 3 characteristics) match between `GattProfile.kt` and `gatt_client.h` / `gatt_server.cpp`.
- `inference_result_t` binary layout matches byte-for-byte (offsets 0/32/36/40/44, little-endian, 52 bytes total — no padding on Cortex-M because `uint64_t timestamp` is 4-byte aligned by the preceding `uint32_t`s).
- Android permission set is complete for both API < 12 (`ACCESS_FINE_LOCATION`) and API ≥ 12 (`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`).
- Disconnect cleans up `BluetoothGatt` on the Android side and `bt_conn_unref` + advertising restart on the Zephyr side.
