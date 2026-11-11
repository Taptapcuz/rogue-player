# MYTV — Android Auto projection test (Desktop Head Unit)

MYTV registers a `CarAppService` in the **CATEGORY_PROJECTION + NAVIGATION** bucket
and, on channel select, projects `ProjectionActivity` (the ExoPlayer surface)
into the car task. This is the Fermata-style route: Android Auto's nav category
grants an unrestricted projection surface that Google's template rulebook denies
the media category.

## Prereqs (verified 2026-11-10 on this machine)
- Android SDK with `extras/google/auto/desktop-head-unit.exe` (DHU 2.1, windows-x64)
  → installed by unzipping `desktop-head-unit-windows-x64_r02.1.zip` (dl.google.com
  Android repository) into `SDK/extras/google/auto`.
- A **debug** MYTV build: `./gradlew :app:assembleDebug` (use Android Studio's JBR JDK 21).
- The phone must run the **real Android Auto app with its head-unit server**.
  ⚠ An emulator image ships AA as a *stub* (`...-stub`) with no head-unit server:
  it needs Google Play sign-in (to update AA to the real build) or a real device.

## Run
```bash
# 1. install MYTV on the target (phone or emulator)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. on the phone: enable Android Auto developer mode & start head-unit server
#    (Settings → Android Auto → tap "Version" ~10× → ⋮ → Developer settings →
#     ⋮ → "Start head unit server")

# 3. forward the AA link port
adb forward tcp:5277 tcp:5277

# 4. launch the DHU
cd "$LOCALAPPDATA/Android/Sdk/extras/google/auto"
./desktop-head-unit.exe --adb=localhost:5277
```

## What to check in the DHU
- MYTV appears on the launcher grid.
- Tap it → channel list (PlaceListNavigationTemplate) renders.
- Selecting a channel → `PlaybackService` starts the stream + `ProjectionActivity`
  draws fullscreen on the car surface. Verify video frames appear and audio is
  routed to the host.

## DHU console commands
`day` / `night`, `restrict` / `unrestrict` (toggle driving → parked), `mic begin`,
`keycode back`. Parked-only actions (channel select) need `unrestrict`? No — use
parked. See DHU help for the full set.

## Known limitations
- Sideloaded CarAppLibrary apps may be filtered by Play services server-side
  validation on newer AA builds even with developer mode+unknown sources on;
  the DHU is the sanctioned local check. Google's own docs note reliability varies.
- `PlaceListNavigationTemplate` is deprecated in the current car-app library (the
  whole nav-category projection trick is legacy); fine for a Fermata-style sideload.
