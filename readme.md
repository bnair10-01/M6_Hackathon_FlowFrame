# Worn Run

Second Place Winner at the Worn Hackathon in NYC. Powered on MicronLumin M6 Glasses.

A hands-free running HUD for the **M6 smart glasses** (640×480 display, Android 9 / API 28, temple touchpad), driven by an **iPhone companion app** that supplies GPS and routes over Bluetooth LE.

You start a run, pocket the phone, and the glasses show pace, distance, time, elevation, splits, the next turn and an off-route warning. Everything on the glasses is controlled from the temple touchpad.

> Black pixels are see-through on this display, so the HUD is bright text on black.

**Contents:** [Demo](#demo) · [Target hardware](#target-hardware) · [How it works](#how-it-works) · [Repository layout](#repository-layout) · [Using it](#using-it) · [BLE protocol](#ble-protocol) · [Building](#building) · [Testing without a run](#testing-without-a-run)

---

## Demo



https://github.com/user-attachments/assets/e7f4463d-02ef-4032-8b05-7138b3db3efd



---

## Target hardware

Worn Run runs on the **M6 smart glasses** (device reports as `XNM-Glass`). Specs read from the device:

| | |
|---|---|
| SoC | Unisoc UWS6152, 4 cores, arm64-v8a |
| CPU | 3× Cortex-A55 + 1× Cortex-A75, all cores top out at 1.482 GHz |
| GPU | PowerVR Rogue GE8300, OpenGL ES 3.2 |
| RAM | ~1.85 GB total (2 GB class), about 1 GB free at idle |
| Storage | ~54 GB `/data` + 3.3 GB system, likely 64 GB eMMC |
| Display | 640×480, 160 dpi |
| OS | Android 9 (API 28), security patch 2020-11-05, build `ML_M6_V10_20261022_1636` |
| Kernel | Linux 4.14.98 aarch64 |
| Sensors | Accelerometer and gyroscope (SH5001), magnetometer (QMC6308), light (ACS5121), pressure, proximity, step detector and step counter, off-body detection, capacitive touch, thermometer. Also Spreadtrum watch-gesture sensors (wrist scroll, run, climb, swim). |

### Hardware the app uses

**On the glasses**

| Hardware | Used for | Where |
|---|---|---|
| Display (640×480) | Custom-drawn HUD: pace, distance, time, turns, alerts. Kept awake during a run and dimmed in low light. | `HudView.kt`, `HudActivity.kt` |
| Temple touchpad (DPAD keys and touch events) | Wake/sleep, pause/resume, start a free run, toggle video, finish. | `HudActivity.kt` |
| Bluetooth LE radio | Acts as a GATT server and advertises. Receives GPS fixes, routes and commands from the phone, and notifies status about once a second. | `BleLink.kt` |
| Barometer (pressure) | Elevation gain and loss. The pressure is converted to altitude and smoothed. | `BodySensors.kt` |
| Step counter | Cadence. | `BodySensors.kt` |
| Off-body detection | Auto-pause when the glasses are taken off. | `BodySensors.kt` |
| Light sensor (ACS5121) | Dims the HUD below about 15 lux. | `BodySensors.kt`, `HudView.kt` |
| Thermometer (Spreadtrum) | Skin temperature. Readings outside −30 to 50 °C are ignored. | `BodySensors.kt` |
| Outward camera | Optional video recording, H.264 MP4 at up to 720p and 30 fps, saved to `DCIM/WornRun`. No audio is recorded. | `GlassesVideoRecorder.kt` |
| Internal storage | Saves recorded video, run preferences and settings, and the GPX file for desk replay. | `GlassesVideoRecorder.kt`, `RunService.kt` |
| CPU and RAM | The `RunEngine` runs in a foreground service that starts at boot. The HUD is custom-drawn to stay light. | `RunService.kt` |

**On the iPhone**

| Hardware | Used for |
|---|---|
| GPS / location | 1 Hz location fixes for pace, distance and route matching. Background location keeps it running with the screen off. |
| Bluetooth LE radio | Central role: finds and reconnects to the glasses, including in the background. |
| Network (Wi-Fi or cellular) | MapKit walking directions and a current air-temperature lookup (Open-Meteo, using your location rounded to 0.1°). |

**Not used by Worn Run:** the glasses' accelerometer, gyroscope, magnetometer, proximity sensor, watch-gesture sensors, microphone, speaker, Wi-Fi and GPU features. The glasses have no GPS, which is why the phone supplies location. Heart rate currently comes only from the demo simulator.

### What this means for the app

- **API 28 ceiling:** `minSdk` and `targetSdk` are set for Android 9, and only the legacy Bluetooth permissions are needed.
- **Small memory budget:** about 2 GB of RAM is shared with the vendor system apps, so the HUD is custom-drawn and lightweight.
- **Fixed 640×480 screen:** the HUD is laid out for that size and uses large, high-contrast text.

The iPhone app needs iOS 17 or later.

---

## How it works

```
 ┌──────────────────────┐   BLE (GATT)    ┌───────────────────────────────┐
 │ iPhone: WornRun      │ ──────────────► │ Glasses: Worn Run             │
 │  • GPS (1 Hz, works  │   FIX  route    │  • BLE GATT server (BleLink)  │
 │    with screen off)  │   CONTROL       │  • RunEngine: pace, distance, │
 │  • MapKit walking    │ ◄────────────── │    splits, route matching     │
 │    directions        │   STATUS (1 Hz) │  • Body sensors               │
 │  • Weather, HR,      │                 │  • HudView / HudActivity      │
 │    demo simulator    │                 │  • Video recorder             │
 └──────────────────────┘                 └───────────────────────────────┘
```

The glasses are the BLE **peripheral** (GATT server) and the iPhone is the **central**. The phone sends location fixes, an optional route and JSON commands. The glasses compute everything and send a JSON status back about once a second. Because the phone only forwards location, a run started from the glasses' touchpad still gets GPS as long as the phone app is connected.

---

## Repository layout

```
M6_Hackathon_FlowFrame/
├── glasses-app/                      Android (Gradle) project, single module
│   ├── settings.gradle.kts           includes only :run
│   ├── build.gradle.kts              AGP 8.7.3, Kotlin 2.0.21
│   ├── gradle/, gradlew, gradlew.bat Gradle 8.11 wrapper
│   └── run/
│       ├── build.gradle.kts
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── res/font/         Noto Sans (regular, semibold) for the HUD
│           │   └── kotlin/com/stevencastro/wornrun/
│           └── test/kotlin/…         JUnit tests
└── ios/
    └── WornRun/                      SwiftUI app (iOS 17+), XcodeGen project
        ├── project.yml
        ├── sync-to-mldemo.sh
        ├── WornRun/                  App sources
        └── WornRunTests/
```

### Glasses source files (`glasses-app/run/src/main/kotlin/com/stevencastro/wornrun/`)

| File | Role |
|---|---|
| `RunService.kt` | Foreground service that starts at boot. Hosts the BLE link, reads sensors, ticks `RunEngine` once a second, handles phone commands, launches the HUD when a run starts, and supports GPX desk replay. |
| `RunEngine.kt` | The brain: run state (idle/running/paused/finished), elapsed and moving time, distance, speed, average pace, cadence, elevation gain/loss, auto-pause, splits, heart rate, route progress and off-route detection. Produces a `Snapshot` each tick. |
| `RunProtocol.kt` | BLE UUIDs and binary encode/decode for fixes and routes, plus `RouteAssembler` for chunked route writes. |
| `BleLink.kt` | GATT server and advertising. Only devices that use the Worn Run service count as "the phone", so other BLE centrals (vendor app, ring) are ignored. |
| `RouteMatcher.kt` | Places the runner on the route (segment, distance along, cross-track error). Searches a window ahead of the last match so loops and out-and-backs match the right pass. |
| `TurnDetector.kt` | Finds turns from route geometry when the phone didn't send any. |
| `Geo.kt`, `Units.kt` | Distance/bearing math and imperial/metric formatting. |
| `Gpx.kt` | GPX parsing, used by desk replay. |
| `BodySensors.kt` | Barometer (elevation), step counter (cadence), off-body detection (auto-pause), thermometer, ambient light (HUD dimming). |
| `GlassesVideoRecorder.kt` | Records the outward camera to `DCIM/WornRun` without opening the vendor camera UI. |
| `HudActivity.kt` | Fullscreen HUD and touchpad input; also contains `BootReceiver`. |
| `HudView.kt` | Custom-drawn HUD. |
| `RunHub.kt` | In-process state (`HudModel`) shared between the service and the HUD. |

### iPhone source files (`ios/WornRun/WornRun/`)

| File | Role |
|---|---|
| `WornRunApp.swift` | App entry; creates `RunLink` and `RunSession`. |
| `SetupView.swift` | The single screen: pick Free run or Navigate, search a destination, adjust settings, start/stop. Also holds the GPS diagnostics view. |
| `RunSession.swift` | Run orchestration: sends settings and route, streams 1 Hz location and air temperature, persists settings, follows runs started or finished from the glasses. Background location keeps it working with the screen off. |
| `RunLink.swift` | CoreBluetooth central: finds the glasses by service UUID, remembers them, auto-reconnects (also in background), sends fixes, routes and commands, and decodes the status. |
| `RunProtocol.swift` | Swift mirror of the glasses' BLE protocol. |
| `RouteBuilder.swift` | Builds a route for the glasses: MapKit walking directions with shortened turn labels, Douglas–Peucker simplification to fit the protocol, turn detection. GPX parsing exists but its UI is hidden. |
| `DemoSimulator.swift` | Fakes a runner (location with jitter, heart rate) at a chosen pace so the whole system can be demoed indoors. "Skip ahead" and "off route" trigger turns and the warning on cue. |

---

## Using it

### On the glasses (temple touchpad)

| Gesture | Action |
|---|---|
| Tap | Wake the display, or put it to sleep if awake |
| Long-press | Pause / resume (or start a free run when idle) |
| Swipe back | Toggle glasses video recording |
| Swipe up | Finish the run (while paused) |

The display sleeps a few seconds after the last touch (default 3 s, configurable on the phone; 0 = always on). While asleep, only alerts and turns within 50 m are drawn.

### On the phone

1. Open **Worn Run**; grant Bluetooth and Location (Always).
2. Wait for the app to show that it is connected to your glasses.
3. Choose **Free run** (pace, distance, time) or **Navigate** (search a destination to get walking directions with turns).
4. Adjust settings if needed: units (mi/km), auto-pause, display sleep or always-on, show GPS coordinates on the HUD.
5. Press **Start** and pocket the phone.

Settings are remembered and also apply mid-run.

---

## BLE protocol

Service `7f1d0000-5e2a-4c8e-9b1a-3c6f8e2d4a10`. All integers are little-endian.

| Characteristic | UUID suffix | Direction | Purpose |
|---|---|---|---|
| `FIX` | `7f1d0001-…` | phone → glasses, write without response | One 20-byte location fix |
| `ROUTE` | `7f1d0002-…` | phone → glasses, write | Route, in chunks |
| `CONTROL` | `7f1d0003-…` | phone → glasses, write | UTF-8 JSON command |
| `STATUS` | `7f1d0004-…` | glasses → phone, notify | UTF-8 JSON status, about 1 Hz |

**FIX (20 bytes):** lat i32 and lon i32 (1e-7°), altitude i16 (dm), speed u16 (cm/s, `0xFFFF` = unknown), course u16 (0.01°, `0xFFFF` = unknown), horizontal accuracy u16 (dm), phone time u32 (unix s). The glasses stamp fixes with their own receive time, so clocks don't need to agree.

**ROUTE body** (before chunking): magic `0x5257` ("WR"), version u8, point count u16, turn count u16, then points (lat i32, lon i32), then turns (point index u16, angle i16 where + is right, label length u8, UTF-8 label). Chunks carry `seq u16, total u16` plus payload. Chunk 0 starts a new route; a gap or repeat resets.

**CONTROL commands:** `settings` (units, autoPause, displaySleepSec, showGps), `start`, `pause`, `resume`, `stop`, `video` (recording), `weather` (airTempC), `hr` (bpm), `clearRoute`.

**STATUS** includes run state, auto-pause flag, elapsed time, distance, video-recording flag, and the last GPS fix with its age.

> `RunProtocol.kt` and `RunProtocol.swift` must stay in sync. Tests on both sides check the same byte layout.

---

## Building

### Prerequisites

- **Android:** JDK 17, Android SDK 34 (set `sdk.dir` in `glasses-app/local.properties`), `adb` access to the glasses
- **iOS:** Xcode, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`), a physical iPhone (BLE and GPS don't work in the simulator), and your own signing team (`DEVELOPMENT_TEAM` in `ios/WornRun/project.yml`)

### Glasses

```sh
cd glasses-app
./gradlew :run:installDebug          # build and install on connected glasses
./gradlew :run:testDebugUnitTest     # unit tests
```

Config: `minSdk 28`, `compileSdk/targetSdk 34`, Java/Kotlin JVM target 17, coroutines 1.8.1. The service starts at boot via `BootReceiver`; launch the app once after installing, since Android doesn't deliver boot broadcasts to apps that have never been opened.

### iPhone

```sh
cd ios/WornRun
xcodegen
open WornRun.xcodeproj
```

Select your iPhone and run. The app declares background modes for location and Bluetooth-central so runs continue with the phone locked.

---

## Testing without a run

### GPX desk replay on the glasses (no phone needed)

```sh
adb push route.gpx /sdcard/Android/data/com.stevencastro.wornrun/files/route.gpx
adb shell am startservice -n com.stevencastro.wornrun/.RunService \
  -a com.stevencastro.wornrun.REPLAY \
  --es gpx /sdcard/Android/data/com.stevencastro.wornrun/files/route.gpx \
  --ef speed 3.0 --ef rate 5 --ef detour 400
```

`speed` is in m/s, `rate` is a time multiplier, and `detour` is the metres along the route where a 60 m detour begins (omit for none).

### Phone-side demo mode

Turn on the simulator in the app's Demo section. It feeds simulated GPS and heart rate over the real BLE link at a pace you choose, so the glasses behave exactly as in a real run.

### Unit tests

| Suite | Covers |
|---|---|
| `RunEngineTest` | Run state, distance, auto-pause, splits, route progress, off-route |
| `RunProtocolTest` | Fix and route encoding, chunk reassembly |
| `UnitsAndTurnsTest` | Unit conversion and turn detection |
| `WornRunTests/RunProtocolTests` (iOS) | Fix layout, route header and chunks, turn detection parity with the glasses, GPX parsing, instruction shortening |

---

## Optional: MLDemo sync

`ios/WornRun/sync-to-mldemo.sh` copies the shared Swift sources (`RunProtocol`, `RouteBuilder`, `RunLink`, `RunSession`, `DemoSimulator`, `SetupView`) into the vendor's MLDemo app under `sdk/Microlumin_Ship/04_Demo_Apps/MLDemo/`. That SDK is not part of this repo, so this only applies if you have it locally. Always edit the originals here, never the copies.

## Notes

- `glasses-app/local.properties` is machine-specific; don't share it.
- Build output (`build/`, `.gradle/`) is regenerable and can be deleted.
