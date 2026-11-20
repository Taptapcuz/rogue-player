# Rogue Player — Project State (shared source of truth)

Audited 2026-11-20. This file is the handoff record: read it before touching Rogue
Player. It captures the current identity, what is committed vs in-flight, the
editions plan, and every open decision that determines the right next move.

## What the app is right now

An Android **IPTV + VOD player** for phone and **Android Auto**, Kotlin + ExoPlayer
(Media3) + M3U/Xtream. Phone UI cloned from the IMPlayer look (blue-on-black);
fully renamed to **Rogue Player** with an original native boot screen.

- Package / namespace: `com.rogueplayer` (was `com.mytv.app` — fully migrated).
- `app_name`: **"Rogue Player"** (main `strings.xml`).
- Native boot splash: `ui/SplashActivity.kt` + `ui/SplashView.kt` (pure Canvas:
  Saira Condensed italic "ROGUE PLAYER" glyphs fly in + fire/smoke + slanted
  loader). Bundled fonts in `res/font/`: `sairacond_extrabold.ttf`,
  `saira_medium.ttf`. Vanilla "D:\ Rogue Player.html" idea ported to native.
- Themeable boot color: `ui/SettingsActivity.kt` + `ui/UiSettings.kt`
  (`UiSettings.bootColor()`); flame/glow/ice derived from one color.

## Build / flavors

`app/build.gradle.kts` defines **two editions** (flavor dimension `edition`),
both branded Rogue Player, distinct applicationIds so both install side-by-side:

| Flavor | applicationId | versionName | Label |
|--------|---------------|-------------|-------|
| `basic` | `com.rogueplayer.basic` | `1.0-basic` | Rogue Player |
| `locked` | `com.rogueplayer.locked` | `1.0-locked` | Rogue Player |

`defaultConfig.applicationId = com.rogueplayer` (matches the namespace).
Per-edition overrides live in `app/src/<flavor>/...` (only `src/locked/` has a
label override today); shared code is in `src/main`.
Build-verified 2026-11-20: `assembleBasicDebug` and `assembleLockedDebug` both
BUILD SUCCESSFUL; confirmed via `aapt2 dump badging` that basic→com.rogueplayer.basic,
locked→com.rogueplayer.locked, both labelled Rogue Player. The feature split
(Basic free/locked premium) comes AFTER Rogue Player is finished whole, using
these flavor gates. APK outputs are git-ignored (`build/`, `*.apk`).

## Git state (branch: master)

Committed history (25 commits) is safe. Rename-to-Rogue happened in
`25742b6 "Rename to Rogue Player and add Settings + themeable boot screen"`.

**Uncommitted / in-flight (on disk only, NOT committed) — verified compiling:**

- `M  app/src/main/AndroidManifest.xml` — adds `RECORD_AUDIO` permission
  (in-car web voice getUserMedia, CarStream-style).
- `M  .../car/CarWebScreen.kt` — runtime mic-permission request from the car
  session; grant WebView `onPermissionRequest`; single-tap BACK action in the
  header strip.
- `M  .../car/ProjectionSession.kt` — car home is now a tile launcher
  (CarLandingScreen); RootScreen/VideoScreen entries; per-tile navigation;
  Live-list header action APP_ICON → BACK.
- `M  .../ui/MainActivity.kt` — session-restore on recreated task (fresh-launch
  one-shot marker + save/restore current tab); `EXTRA_FRESH_LAUNCH`,
  `FRESH_PENDING`, `SESSION_PREFS`, `SESSION_TAB` constants.
- `M  .../ui/SplashActivity.kt` — sets the fresh marker + fresh-launch extra.
- `M  .../ui/SplashView.kt` — height-cap word size so it fits a car head-unit
  landscape (was width-only, clipped "ROGUE" on landscape).
- `?? .../car/CarBrowseScreens.kt` — new (CarMovieScreen, CarMovieListScreen,
  CarShowScreen, CarSeriesListScreen, CarEpisodesScreen).
- `?? .../car/CarLandingScreen.kt` — new (tile home: Live/Movies/Shows/YouTube/Web).
- `?? branding/` — `rogue-player-splash.html` art reference (copied from `D:\`).
- `?? nul` — 0-byte Windows artifact, safe to delete, not tracked.

All referenced car classes exist (RootScreen, VideoScreen, CarLandingScreen,
Car*Screen). Build green. This in-flight work is presumed to be the default
D:\ `rogueplayer`-profile session's car-UI refactor — treat it as real work, not
clutter. It is uncommitted; the recommended safe action is to snapshot it to a
WIP branch before any further edits so it can be rolled back if needed.

## Editions plan (confirmed direction)

Goal: **keep both editions, one codebase, feature-gated**. Finish Rogue Player
as the full-featured app first; the feature split happens later:
- **Basic** edition — free, core playback + EPG.
- **Locked** edition — paid/premium with the advanced features gated.

The split will reuse the existing `edition` flavor machinery. Do NOT convert now;
build Rogue Player whole, split afterward.

## Video recording (feature on the roadmap)

Plan agreed: **local in-app recording** (option #1) — captures the active stream
to storage from `PlaybackService`/ExoPlayer. No multi-line account needed; the
known limitation is single-connection = record-what-you-watch only. Buildable
today with zero provider dependency. Server-side DVR (option #2) depends on the
provider panel, don't build that first. Position as "record this now" capture,
not a TiviMate-style scheduler.

## Useful environment facts (from past work)

- `gradlew` breaks under git-bash (MSYS path mangling). Invoke wrapper with
  native java: `"$JBRJAVA" -cp "<abs path>/gradle/wrapper/gradle-wrapper.jar"
  org.gradle.wrapper.GradleWrapperMain <task> -Dorg.gradle.java.home="C:/Program
  Files/Android/Android Studio/jbr"`. JBR is the Java (21) bundled with Android
  Studio (not on PATH in bash).
- Android Auto UI can be verified on the DHU emulator (see `android-auto-dhu`
  skill and `docs/ANDROID_AUTO_TEST.md`).
- ffmpeg at `/c/ytdl/ffmpeg`; Chrome at `C:/Program Files/Google/Chrome/...`.
- `docs/clean-room-audit.md` holds the IMPlayer-cloning risk audit (RED screens:
  main chassis, channel card; AMBER: accent, guide preview). Private use is fine;
  public publish needs those RED items softened.

## Open items / decisions still pending

1. ~~Flavor identity reconciliation~~ **DONE 2026-11-20** — flavors are now
   `basic` / `locked`, both `com.rogueplayer.*`, both labelled Rogue Player,
   build-verified. `src/clean` removed.
2. **Name collision** — "Rogue Player" vs existing `Rogplay` (IPTV-VOD player).
   Fine for personal use; riskier for Play publishing (decided to keep it).
3. **Boot voice** — user wants a deep transformer-style "Rogue Player" bark on
   boot (NOT literally "Optimus Prime" / Hasbro). Not built yet; needs a voice
   source (TTS/deep-voice) + playback wiring.
4. **Recording** — roadmap as above, not started.
5. **Uncommitted car/splash work** — snapshot to WIP branch when convenient.
