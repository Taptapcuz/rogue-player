# Rogue Player

**The actively-built, ad-free, original-designed IPTV player for Android Auto and phone.**

Rogue Player plays *your own* IPTV subscription (M3U or Xtream Codes) on your
phone and, uniquely, in **Android Auto** — a surface the big IPTV players
(TiviMate, IMPlayer, IBO Player) don't support at all. It's not a content app:
it ships no channels, playlists, or streams. You bring your own (legal) source.

---

## Why it exists

The TV-first IPTV players own the living room and ignore the car. The
car-capable players (FermataX, Xtream Player) are free side-projects — one is a
GPL fork that literally warns users they got scammed if they paid for it, the
other funds itself with ads. **Nobody offers a clean, maintained, ad-free,
buyable IPTV player for the car.** That's the gap Rogue Player fills.

---

## What it does

- **Live TV** — M3U + Xtream Codes playlists, multiple sources, category chips.
- **Movies & Series** — poster grids, episode/season browsing, metadata.
- **EPG guide** — full programme guide with a karaoke-style progress line: the
  programme text fills with the accent colour as it airs, and a thin underline
  keeps advancing to the end of the show.
- **Sports tab** — auto-detects sport categories (NBA, NFL, ESPN, UFC, F1, …)
  and lands you on the groups, not a raw channel dump.
- **Category management** — hide/show categories, and reorder them (order is
  saved per playlist and drives the chips across every tab).
- **Recording** — one-tap recording of the live channel you're watching. HLS
  segments are copied byte-for-byte to `Movies/Recordings/` — no re-encode, no
  CPU cost, picture untouched. Partial files are kept.
- **Web browsing + YouTube** — a built-in browser for the whole web and for
  YouTube, straight from the app (phone and car).
- **Android Auto** — native car landing + projection surface. This is the point.
- **Original design** — pills, karaoke progress, and a native Rogue boot screen.
  Not a reskin of someone else's app.

---

## How it compares

### vs the TV-first paywalled players
They don't run in Android Auto — at all. If you want IPTV in the car, this is a
category they can't compete in.

| | Rogue Player | TiviMate / IMPlayer / IBO |
|---|---|---|
| Android Auto | ✅ | ❌ none |
| Multiple playlists | ✅ free | premium |
| EPG guide | ✅ free | premium-ish |
| Recording (one-tap live) | ✅ free | premium |
| Scheduled / timer recording | — | premium |
| Catch-up / timeshift | — | premium |
| Multiview / PiP | — | premium |
| Original UI (pills, karaoke) | ✅ | ❌ |

### vs the car-capable players (the real competition)

| | **Rogue Player** | FermataX | Xtream Player (Devcoder) |
|---|---|---|---|
| Android Auto | ✅ | ✅ | ✅ |
| Ad-free | ✅ | ✅ | ❌ ads on free tier |
| Buyable / paid tier | ✅ (planned) | ❌ GPL — can't cleanly sell | ads → unlock only |
| IPTV + VOD + Series | ✅ | ✅ + Stalker portals | ✅ |
| In-app web + YouTube browser | ✅ | ✅ | ✅ |
| Plex / internet radio | — | ✅ Stremio/web extras | ✅ Plex + radio |
| Original UI + recording | ✅ | varies | ❌ recording |

**The honest position:** FermataX is broader (Stalker/Ministra portals, Stremio
add-ons) and Xtream Player is a hybrid hub (Plex, internet radio) — on *those
extras* they do more. But breadth isn't the ask for someone who wants IPTV in the
car, and Rogue Player already covers the basics they share (IPTV, VOD, web +
YouTube browsing). It's the **focused, ad-free, original, actively-owned** option,
and — unlike FermataX (GPL) and Xtream Player (ads-only) — it's the one that can
exist as a clean paid product.

---

## Editions

Built from one codebase with Gradle flavors:

- **Basic** — free: core playback, EPG, guide, Sports.
- **Locked** — premium: unlocks the paid-tier features.

---

## Building

Requires Android Studio (JDK 17+ bundled) and the Android SDK.

```bash
# from a cmd prompt or Android Studio (the gradlew wrapper is fussy under git-bash)
./gradlew assembleBasicDebug     # free edition APK
./gradlew assembleLockedDebug    # premium edition APK
```

APKs land in `app/build/outputs/apk/<flavor>/debug/`.

---

## Scope & disclaimer

Rogue Player is a **player only**. It does not provide, host, sell, or
distribute any channels, playlists, streams, or copyrighted media. You must add
your own source and have the legal right to access it. Android Auto video/IPTV
is not officially supported by Google — running this in a car requires
sideloading and is done at your own risk, and only when parked.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

In plain terms: use it, change it, ship it — but if you distribute a modified
version, its source has to be available under the same license. The app itself
ships no content; what you play through it is your responsibility and your
subscription.
