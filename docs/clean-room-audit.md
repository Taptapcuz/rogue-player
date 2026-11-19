# MYTV — clean-room / cloning audit

Status: **private / personal app — no scrub required.** Revisit only if the
repo is ever made **public** (see Risk profile).

Audit date: 2026-11-17. Source: read of the live UI code + layouts in
`app/src/main`, cross-referenced against the IMPlayer functional map in
`docs/IMPLAYER_FUNCTIONAL_MAP.md`.

## Risk profile (read this first)

- **Personal / sideloaded installs only: effectively zero legal risk.** No
  distribution, no commercial gain → copyright / trade-dress claims are never
  pursued in practice.
- **Public GitHub repo: real, takedown-able risk — even at $0.** Precedent:
  FermataX was cloned, its source got locked, and paid copies sold under other
  names. A discoverable 1:1 replica of a proprietary paid app invites a
  takedown; stores and form-holders side with the original.
- **Action rule:** do not scrub while private. The two RED items below are the
  *condition* for ever going public, nothing more.

## The app's own admission — remove before any public push

The code self-describes as a clone and that intent is discoverable:

- `app/src/main/java/com/mytv/app/ui/MainActivity.kt:37` — *"MYTV phone UI —
  blue-on-black IMPlayer-style clone."*
- `app/src/main/res/layout/activity_guide.xml:112` — *"Preview window
  (TiViMate style)…"*

These tell a reviewer "intent to copy." A clean visual diff alone won't bury
that. Rename/`#d` commentary before any public push (harmless now).

## RED — too close to IMPlayer; change if/when going public

### 1. Main chassis (MainActivity)
Header `[logo · provider dropdown · search · A–Z]` + **chips row directly under
it** + **floating 5-tab bottom pill** (Home · Live TV · Movies · TV Shows ·
5th), active tab = blue. Mirrors IMPlayer functional map lines 8–14
(header, 5-tab floating dark pill, chips under header). Recognizable in under a
second by anyone who has used IMPlayer.

Fix (concept stays, form changes): standard Material `BottomNavigationView`
docked to the bottom + full-width app-bar tabs (Plex/Stremio style), or a side
rail on phones. Break the pill + chips-in-same-slot copy.

### 2. The channel card (Live TV)
Per IMPlayer map lines 32–38: bold white `US| C-Span 1 FHD` (region-prefix +
quality), one grey line `09:00 PM · program`, **blue progress bar along the
card's bottom edge**. Almost verbatim IMPlayer card.

Fix: change the silhouette — left-side time rail instead of bottom accent,
channel-number badge top-left, or move the progress bar under the program line.

## AMBER — not enough alone, but compounds the RED case

- **Accent color.** Hardcoded `#3D7BFF` blue on `#0B0B10` near-black across
  layouts is IMPlayer's whole palette. Keep "dark + one accent" (shared genre)
  but pick an original accent (teal / amber / magenta) and define it in
  `res/values/colors.xml` rather than hardcoding blue. (Currently colors.xml
  only defines the background `#090B0F`; all accent greys/blues are inline.)
- **Guide "PREVIEW" floating side-card** (activity_guide.xml:112+; self-tagged
  "TiViMate style"). Floating video preview overlay over the grid is a
  TiviMate/IMPlayer signature. Fix: render the preview as a full-width bottom
  panel instead of a right-side overlay card.

## GREEN — shared genre, no one owns it; keep as-is

Favorites, EPG grid, hero carousel, poster rows, search history,
detail/metadata screens, provider dropdown as a concept, M3U/Xtream handling,
the Android-Auto surface. Every IPTV player has these.

## Lock-in checklist (only when a public push is planned)
1. Do the side-by-side screenshot check on the two RED screens. If a stranger
   can't tell your main view from IMPlayer's in one glance, redesign further.
2. Remove the self-incriminating comments (above).
3. README: "inspired by the IPTV-player genre (IMPlayer, TiviMate)", original
   visuals, player-only / no-content disclaimer, GPL-3 — the FermataX posture.
4. Decide honestly: is this a personal app or a published one? Personal → stop
   here forever.
