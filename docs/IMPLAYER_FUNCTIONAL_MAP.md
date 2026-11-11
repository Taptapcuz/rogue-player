# MYTV — IMPlayer functional incorporation map

Captured live from the real IMPlayer app (com.myiptvonline.implayerm v2.x) on
the Samsung Fold6, 2026-11-10. Source of truth for what MYTV should replicate
(blue-on-black palette, no red unless noted).

## Global chrome (every tab)
- Header: app logo · provider/library **dropdown** (e.g. "bbl") · **search** 🔍 ·
  **A–Z sort** ↕. Context icons (heart-favourite, ⋮ menu, cast, logo) appear on
  deeper screens.
- **5-tab bottom nav** (floating dark pill): Home · Live TV · Movies · TV Shows ·
  Sports. Active tab = blue icon + blue label, highlighted pill.
- Category filter **chips** row directly under the header (active = white bg /
  dark text; inactive = dark-grey bg / white text).

## Tab: Home
- Header + chips: All Sections / V.O.D / Live TV / Sports.
- **Hero carousel**: full-width banner, "Movie/Series" tag, big title overlay,
  pagination dots, dismiss "−" button. (TMDB-sourced.)
- Sections (each with a **blue underline** on the title):
  - **Top 10 Watched Channels** — horizontal cards: time badge (e.g. `01:50:17`,
    blue) top-left · channel logo · `US| Name FHD` · show title · thin blue
    progress bar at card bottom.
  - **Continue Watching Movies / TV Shows** — count in title `(N)`, empty state:
    "Watch your first V.O.D content to populate this category".
- Floating quick-nav: "Go to Movies" / "Go to TV Shows".

## Tab: Live TV
- Header: provider dropdown + search + A–Z sort.
- Category chips are **provider groups**: `US/CA | News` (active=white/red),
  `Live Sport | Formula 1`, `US | Kids`, …
- **Channel card** (the core list item):
  - rounded dark card, logo left
  - **bold white** `US| C-Span 1 FHD` (region `US|`/`CA|` prefix + quality)
  - one line: `09:00 PM   C-SPAN Primetime` (time → program, grey)
  - **blue progress bar** spans the card bottom edge.
  - tap → opens the player (same activity, no new screen).

## Tab: Movies / TV Shows
- Chips: Favorites / Watched History / All (Movies|Shows) / genre…
- Hero carousel (TMDB banner + dismiss −) + pagination dots.
- Sections: **Title + count** (e.g. "All Movies 5,985") + pink **"See All"** on right.
  - **Horizontal poster rows**; card = **blue rating badge top-left** (6.0/7.0),
    poster art, title under poster.
- Tap card → detail/play flow.

## Tab: Sports
- Chips: sport (Basketball ↑active / Baseball / Ice Hockey …).
- **Date row**: `Sat 07` … `Thu 12`, active = blue text + blue underline.
- **Accordion category list**: leagues then countries (NBA, NBA G League,
  EuroLeague, EuroCup, Champions League, All-Star Games, Germany, England,
  Italy, Spain, France, USA) — each an expandable row with chevron.

## Search + voice + results
- **Search overlay** (header magnifier): "Search a Channel, Programme or V.O.D"
  field + **Search History** list (recent queries, e.g. "Atlanta", "freaknik")
  with a **"Delete All"** action.
- **Search Results** page: **playlist tabs** (All Playlists / bbl / bbl2 — the
  user's configured playlists) + grouped result sections:
  - "Live TV Results (11)" — channel cards (logo, name, "No Information")
  - "Movies Results (3)" — poster cards with rating badges.
- **Voice assistant** (header mic icon): voice-prompt dialog with "Didn't catch
  that. Try speaking again." / "Try again" + **language selector**
  ("English (United States)").

## App menu (bottom-sheet, header ⋮)
- **Activate Premium** (status "Free").
- **Manage** section:
  - Manage Playlists
  - Sync and Data
  - Management Code
  - Recordings, Reminders and Downloads
  - Trakt integration
- **Preferences & help** section:
  - App Configuration
  - Playlists Modifications
  - Support and Feedback (Discord)
  - Walk-Though Guide
- Links: About / Privacy / Terms; **Device ID** row with copy button.

## Player (opened by tapping a channel / VOD)
Rendered as a layer on the same activity:
- Video area controls: **mute** (top-left), **PiP**, **fullscreen** (top-right),
  **center pause/play**.
- Below video — **channel/program info card**:
  - logo · `US| C-Span 1 FHD` · program title · **"47 minutes left"**
  - **blue progress bar**
  - description paragraph
  - **date-tab schedule selector** (Mon 09 / Tue 10 ✓blue / Wed 11 / Thu 12)
  - **schedule list** (time + program); the **currently-airing row is highlighted
    red with a live dot icon**.
  - blue **maximize FAB** (bottom-right).

## My key design decisions for the MYTV port
- Keep **blue-on-black** everywhere; the *actions* and screen structure are cloned
  verbatim, but "active chip" = blue (IMPlayer uses red for its logo/active — we
  are not porting red).
- Android Auto (separate surface): Live TV list renders via
  `PlaceListNavigationTemplate` (nav category); selecting a channel starts
  ExoPlayer + Fermata-style `ProjectionActivity`. The bottom-nav/tabs chassis is a
  *phone-only* surface; AA uses its own template system.
