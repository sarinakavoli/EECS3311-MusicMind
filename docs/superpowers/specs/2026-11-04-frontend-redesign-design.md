# Frontend redesign — design

**Date:** Nov 4, 2026. **Branch:** `stage2-gui` (PR #4). **Source of requirements:** `docs/GUI_TEST_FINDINGS.md`
(Sarina's GUI test, Nov 2) and her choices in conversation.

## Why

Sarina tested the GUI and could not understand most screens without explanation. The app also looks like a
default grey desktop form, and the demo videos and report will show every screen. Success means someone who
has never seen MusicMind can open each screen and know what it does, what to try and what happened, and the app
looks like a modern music app.

## Goals

- **Dark Spotify-style look** with a **menu on the left** (layout A, chosen Nov 3). Black and dark-grey
  surfaces, white text, green (#1DB954-like) for primary buttons, rounded controls, clear spacing.
- **Menu names** (my proposal, Sarina may change them): Your Taste (Profile), Trends, Find Songs (Search),
  Discover, Playlists, Your Rules (Preferences), History.
- **Every screen** has a large title, a one-line description of what it does, and where useful an example to try.
  Sarina's Discover wording is the base: "Start listening to something new. Artists and songs you haven't
  heard yet, picked from your taste."
- **Plain wording everywhere**; no internal terms (familiar bias, 0.4, constraint, explicit tracks, track id,
  "--refresh").
- **All 25 findings handled** (screen items 1, 2, 4, 6-15, 17-24; backend items 3, 5, 16, 25 were done or are
  recorded as limits).

## Screen by screen

- **Top bar:** MusicMind name; "Logged in as <Spotify display name>" (`currentUserName()`); login button.
  Footer keeps the Spotify and MusicBrainz attribution.
- **Your Taste (F1):** time range shown as **Past month / Past 6 months / Past year** (Spotify's only periods;
  Past week and Past 3 months do not exist and are not offered). Top artists and tracks show names when
  available, never the CLI hint "--refresh"; when names are not available, offer a "Refresh" button.
- **Trends (F2):** same wording for periods; plain explanation of "added" and "dropped".
- **Find Songs (F3):** description says it finds songs by meaning or by name; rows show **title, artist names,
  length**; "+ Add" adds to the next playlist; the status line shows the running total ("Added X — 3 songs
  waiting for your next playlist"); the waiting list shows names and artists, with Remove and Clear all.
- **Discover (F4):** description as above; the mix control reads "Mix: 60% new artists, 40% artists you know",
  shows the **saved** value (`savedFamiliarBias()`), and "Use my usual mix" hides the slider instead of greying it
  at a wrong 50%. Rows show title, artists, a plain label ("New artist for you" / "Artist you know") instead of
  "score 0.60, familiar 0%", small ♥ / ⊘ icons (like / not for me, highlighted once chosen, F9) and "+ Add".
  One "Why these songs?" for the list; per row "Why this song?" with one plain sentence.
- **Playlists (F5-F7, F10-F12):** each playlist row has Open and **Delete** (confirmation that names the
  playlist; says a saved version is kept). Track rows: title, artists, length, small ♥ / ⊘ icons, "Block" in a
  menu. Validation badge wording in plain English ("Meets your rules").
- **Create dialog (F5, F11):** length as **hours and minutes**, blank or 0 means "from the goal"; explicit as
  "Include songs with explicit lyrics"; mix control hidden while "Let the AI choose the mix from my goal" is ticked;
  lists the waiting candidates with "Start with these N songs" (uses `AgentRequest.useCandidates`); full
  "Why these songs?" text readable (scrolls, never cut off).
- **Your Rules (F8):** description: rules MusicMind follows for every playlist and recommendation. Explicit as an
  allow / block choice; mix as the same slider wording; blocked artists chosen by **searching a name and picking
  from the matches** (`findArtists`, `blockArtist`); blocked songs chosen by searching (`findTracks`,
  `blockTrack`); lists show names; one Save per rule stays only if needed; plain errors.
- **History (F12):** two clear parts: an activity timeline (playlist changes first; routine profile views hidden
  behind a filter) and saved versions **grouped by playlist**, each showing what changed ("27 → 22 songs") and a
  preview before Restore.

## Constraints

- JavaFX 21, Java 17; no new UI framework. One stylesheet (`src/main/resources/.../musicmind.css`) holds the look;
  views use style classes, not inline styles.
- Views hold no business logic and call only `AppController` (Stage 1 MVC rule); new display helpers go in
  `ViewFormat` or small view classes and are unit tested.
- All wording in `musicmind.properties` through `Config` (no hard-coded text).
- No album art (the app does not read Spotify image links; adding them is out of scope).
- Spotify policy: names shown on screen are fine; nothing new is sent to OpenAI.
- Stage 1 names stay: `ProfileView`, `DiscoveryView`, `PlaylistView`, `GuiApp.start()`; record the visible renames
  and the redesign in `docs/STAGE2_PLAN.md`.

## Approaches

1. **Restyle in place (chosen):** keep the existing views and `GuiContext` worker, replace `TabPane` with a left
   menu and content area, add one stylesheet, and rework each view's layout and wording. Lowest risk; keeps the
   tested worker and lock code; enough for the look Sarina picked.
2. Rewrite the GUI with FXML and Scene Builder. Cleaner separation of layout, but rewrites tested code days before
   the deadline.
3. Third-party theme library (for example AtlantaFX). Fast dark look, but adds a dependency and still needs every
   wording and control change.

## Non-goals

- No album art, no animations beyond hover states, no new features beyond what the findings ask for.
- No change to backend behaviour.

## Left to the builder

Exact spacing, colours within the dark palette, icon glyphs, and how History draws the timeline, as long as it
reads clearly in a screenshot at normal size.
