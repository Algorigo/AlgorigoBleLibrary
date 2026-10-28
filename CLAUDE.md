# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android BLE library built on RxJava3 (`com.algorigo.rx:algorigoble2`), published to Maven Central. Two Gradle modules:
- `algorigoble2/` — the library (minSdk 24, compile/target 35, Java/JVM 11, Kotlin 2.0)
- `app/` — sample/test app that depends on `project(':algorigoble2')`; use it to exercise library changes on a real device

## Commands

```bash
./gradlew build                                   # what CI runs (assemble + unit tests + lint)
./gradlew :algorigoble2:assembleRelease           # build the AAR
./gradlew :app:installDebug                       # install the sample app on a connected device
./gradlew :algorigoble2:testDebugUnitTest         # JVM unit tests
./gradlew :algorigoble2:testDebugUnitTest --tests "com.algorigo.algorigoble2.ExampleUnitTest"   # single test
./gradlew :algorigoble2:connectedDebugAndroidTest # instrumented tests (device required)
./gradlew lint
```

Only placeholder example tests exist; BLE behavior is effectively verified through the `app` module on hardware (or via virtual devices, see below).

### Build configuration gotchas
- The library version is set in `algorigoble2/build.gradle` (`versionStr`). On any branch whose name doesn't contain `master`, the version becomes `<versionStr>-<user.name>-SNAPSHOT` and the publishing block references `NEXUS_SNAPSHOT_REPOSITORY_URL` / `nexusUsername` / `nexusPassword`. These come from `~/.gradle/gradle.properties` (CI downloads it); without them Gradle configuration fails on non-master branches.
- `publish` uploads the pre-built `build/outputs/aar/algorigoble2-release.aar`, so assemble must run first. On master it publishes to `build/staging-deploy`, which CI zips and uploads to the Sonatype Central API.
- When adding a library dependency that consumers need, check whether it must also be declared in the POM (there's history of adding deps to the pom manually).

### Branching / CI (CircleCI, `.circleci/config.yml`)
Git-flow with `v2/` prefixes: `v2/develop` (integration), `v2/master` (release, PR base), `v2/release/*`, `v2/hotfix/*`, `v2/feature/*`. `build` runs on all of these; `release/*` and `hotfix/*` publish SNAPSHOTs to Nexus; `v2/master` publishes to Maven Central. Commit messages are typically written in Korean.

## Architecture

Public API / engine split: user-facing classes delegate everything to `internal` abstract engines, so the platform implementation and the virtual (fake) implementation are interchangeable.

- `BleManager` → `BleManagerEngine` (impl: `impl/BleManagerEngineImpl`). Handles scanning, device lookup/caching, bonded/connected device lists, and a global connection-state stream. The `Engine` enum is a vestige of a removed RxAndroidBle backend; only `ALGORIGO_BLE` exists.
- `BleDevice` → `BleDeviceEngine` (impl: `impl/BleDeviceEngineImpl`, wraps `BluetoothGatt`). `BleDevice` is `open` and meant to be subclassed by consumers for device-specific protocols; the engine is injected after construction via `initEngine()`, so subclasses need a no-arg constructor and must not touch `deviceId`/`engine` in their constructor.
- `BleManager.BleDeviceDelegate.createBleDevice()` decides which `BleDevice` subclass to instantiate for a scanned device (return `null` to ignore it). `getDevice(mac, Class)` instead instantiates via `clazz.newInstance()`. `BleManagerEngineImpl.deviceMap` caches one `BleDevice` per `BluetoothDevice`.
- `InitializableBleDevice` adds a post-connect `initializeCompletable()` step; until it completes, its connection state reports `CONNECTING` (status "INITIALING") even though GATT is connected, and a failed init disconnects.

### BleDeviceEngineImpl internals
- Connection state lives in a `BehaviorRelay<State>`; `State.CONNECTED` carries the `BluetoothGatt`. `CONNECTED` is only emitted after `onServicesDiscovered`, not on the raw GATT connect.
- `BluetoothGattCallback` results are pushed into `replyRelay` (read/write characteristic & descriptor replies, tagged by `Type` + UUID) and `notificationRelay`. GATT operations go through a per-device queue (`enqueue`), so only one is in flight; each is guarded by a disconnect check and `operationTimeoutMillis`. A GATT call that is rejected (returns false / non-SUCCESS) raises `BleManager.GattRequestRejectedException`, retried 5× at 500 ms.
- `setupNotification` returns `Observable<Observable<ByteArray>>`: the outer emission means the CCCD descriptor write succeeded; disposing it disables the notification.
- Services are read from the current `BluetoothGatt` on every lookup (no cache), so reconnects and `onServiceChanged` rediscovery are picked up.
- SPP (RFCOMM) lives in `impl/SppConnection` with its own state; the engine merges it into the single public state (`SPP_CONNECTING`/`SPP_CONNECTED` only while GATT is `DISCONNECTED`). GATT and SPP on the same device are mutually exclusive, checked under the shared `stateLock`.
- Bluetooth-off is detected by a broadcast receiver in `BleManagerEngineImpl`, which calls `onBluetoothDisabled()` on every cached device engine.

### Virtual devices
`virtual/VirtualDevice` + `VirtualDeviceEngine` let you simulate a peripheral without hardware. Pass `Pair(VirtualDevice, BleDevice)` into the `BleManager` constructor; the virtual devices are merged into scan results, `getDevice`, and bonded/connected lists. See `app/.../VirtualBleDevice.kt` for an example.

### Exceptions
Every failure the library raises is a `BleException` (`BleException.kt`, abstract on purpose so new subtypes don't break callers' `when`). Concrete types live in `BleManager` (`DisconnectedException(status)` carrying the disconnect reason from `onConnectionStateChange`, `BluetoothDisabledException`, `OperationTimeoutException(operation)`, `GattOperationFailedException(operation, status)`, `CharacteristicNotFoundException`, `PermissionDeniedException`, ...) plus `exception/SystemServiceException` for scan prerequisites. Engine `State.DISCONNECTED(cause)` carries the error that in-flight operations/notifications should fail with. Caller mistakes (bad arguments) stay as standard `IllegalArgumentException`; SPP socket I/O stays `IOException`.

### Callback threading
Public results (`BleDevice` / `BleManager.getConnectionStateObservable()` Observables, Singles, Completables) are handed off with `deliverToCallback()` (`CallbackScheduler.kt`, `Schedulers.io()`, `delayError` for streams), so user code never runs on the GATT binder thread or inside the engine's state lock. Library-internal code must subscribe to the raw streams instead (`BleDeviceEngine.getConnectionStateObservable()`, `BleDevice.connectionStateObservableInternal()`), otherwise every cached device would pin an io thread. Scan results stay on the main thread (Android default). Tests control delivery via `CallbackScheduler.override`.

### Logging
Uses `com.algorigo.library:algorigo-logger` (`L.debug/info/error(tag, ...)`) with the tag hierarchy in `logging/Tags.kt` (`Ble`, `Ble.Engine.Default`, `Ble.Device.Default`, ...). Prefer these over `android.util.Log`.
