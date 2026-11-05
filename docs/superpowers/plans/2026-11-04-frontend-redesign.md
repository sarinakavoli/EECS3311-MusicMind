# Frontend Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every MusicMind screen understandable without explanation and give the app a dark, Spotify-style look with a left menu.

**Architecture:** Restyle in place. `GuiApp` swaps the `TabPane` for a left menu plus content area; one stylesheet holds the look. Each view keeps calling only `AppController` through `GuiContext`. Wording and display logic live in `ViewFormat` and `musicmind.properties`, where they are unit tested.

**Tech Stack:** Java 17, JavaFX 21.0.13, JUnit 5, Gradle 9.1.

**Spec:** `docs/superpowers/specs/2026-11-04-frontend-redesign-design.md` (requirements: `docs/GUI_TEST_FINDINGS.md`).

## Global Constraints

- Branch `stage2-gui`. Never push, never switch branch, stage files by name. Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Views call only `AppController` (through `GuiContext.run`); no business logic in views. No backend behaviour changes.
- All visible text in `src/main/resources/musicmind.properties` under `text.gui*`, read through `ViewFormat.w(...)`. No hard-coded wording or colours in Java.
- Look comes from one stylesheet, `src/main/resources/com/musicmind/app/musicmind.css`, through style classes; no new `setStyle(...)` calls; remove existing inline styles as views are touched.
- Palette: background `#121212`, panels `#181818`, cards `#202020`, hover `#2a2a2a`, text `#ffffff`, muted `#b3b3b3`, accent `#1db954`, lines `#2e2e2e`.
- Menu names: Your Taste, Trends, Find Songs, Discover, Playlists, Your Rules, History. Stage 1 class names (`ProfileView`, `DiscoveryView`, `PlaylistView`, `GuiApp.start()`) stay.
- Time ranges shown as Past month (`short`), Past 6 months (`medium`), Past year (`long`) only.
- Spotify and MusicBrainz attribution stays visible. No album art. Nothing new is sent to OpenAI.
- Never read `.env` or `.spotify-tokens.json`; tests do not touch a real account.
- TDD for every `ViewFormat` helper. Layout code is checked by the live screenshot pass in Task 9.

## Review Focus

- A song with no artist names (tracks read back from a saved version) shows the title alone, with no stray separator. Test in Task 2.
- A very long title or artist list does not push the icons off the row (ellipsis). Checked in the Task 9 screenshot pass with a long-title search.
- Lengths: 0 hours 0 minutes means "from the goal"; 1 hour 75 minutes is refused with a message; letters are refused. Test in Task 6.
- Screens before login, and with the database down, show the description and a clear "log in" or "not saved" message, never an empty dark page. Checked in Task 9 and covered by the existing login gate.
- The saved mix is absent (no preference): Discover shows the default mix wording, not "NaN%" or "0%". Test in Task 5.

---

### Task 1: Dark shell with left menu, page headers and the stylesheet

**Files:**
- Create: `src/main/resources/com/musicmind/app/musicmind.css`
- Modify: `src/main/java/com/musicmind/app/GuiApp.java`, `app/view/TabView.java`, `app/view/UiParts.java`, `app/view/StatusLine.java`, `app/view/LoginGate.java`, `musicmind.properties`
- Test: `src/test/java/com/musicmind/app/view/ViewFormatTest.java`

**Interfaces:**
- Produces: `TabView.description(): String` and `TabView.example(): String` (default empty). `UiParts.page(String title, String description, String example, Node... children): VBox`. Style classes `mm-menu`, `mm-menu-item`, `mm-menu-item-selected`, `mm-page-title`, `mm-page-desc`, `mm-example`, `mm-card`, `mm-row`, `mm-muted`, `mm-primary`, `mm-icon-button`, `mm-icon-on`, `mm-banner`, `mm-footer`, used by every later task.

- [ ] **Step 1: Failing test** `everyScreenHasMenuNameAndDescription`: for each key `guiMenu.profile|trends|search|discover|playlists|preferences|history`, `format.w("Menu." + key)` and `format.w("Desc." + key)` are non-blank, and the menu names equal the Global Constraints names.
- [ ] **Step 2: Run** `./gradlew test --tests com.musicmind.app.view.ViewFormatTest` and see it fail.
- [ ] **Step 3: Implement.** A left `VBox` menu of buttons (one per view, the selected one marked) replaces the `TabPane`. The content area shows the selected view's node in a `ScrollPane`. The header shows `controller.currentUserName()` instead of the user id, falling back to the id. Banners and footer use style classes. Load the stylesheet on the `Scene`. Minimum window size 1100x720. Descriptions: use Sarina's Discover text; write the others in the same plain style, with an example where a screen takes input.
- [ ] **Step 4: Run the test and the full suite** (`./gradlew test`) and see them pass.
- [ ] **Step 5: Commit** "GUI: dark theme, left menu, page titles and descriptions".

### Task 2: Shared song row with artist names and like / not-for-me icons

**Files:**
- Create: `app/view/TrackRow.java`
- Modify: `app/view/FeedbackBar.java` (or replace it), `app/view/ViewFormat.java`, `musicmind.properties`
- Test: `ViewFormatTest`

**Interfaces:**
- Consumes: `Track.artistNames()`.
- Produces: `ViewFormat.artists(Track): String` (names joined by ", ", empty when none). `ViewFormat.trackTitleLine(Track): String`. `TrackRow.of(GuiContext ctx, StatusLine status, Track t, TrackRow.Options o): Node`, where `Options` says whether to show the like / not-for-me icons, "+ Add", "Block" and "Remove". The row has a title, muted artist line, length and icons. The chosen icon stays highlighted; the tooltips say what each icon does.

- [ ] **Step 1: Failing tests** `artists_joinsNames`, `artists_noNames_isEmpty`, `trackTitleLine_noArtists_hasNoSeparator`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** Icons call the existing `recordFeedback` through `ctx.run`. The title and artist labels use ellipsis.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: shared song row with artists and like icons".

### Task 3: Your Taste and Trends

**Files:** modify `app/view/ProfileView.java`, `app/view/TrendsView.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest`.

**Interfaces:** Produces `ViewFormat.rangeLabel(String range): String` (`short` to Past month, `medium` to Past 6 months, `long` to Past year, otherwise the raw value).

- [ ] **Step 1: Failing tests** `rangeLabel_mapsSpotifyRanges`, `rangeLabel_unknownStaysRaw`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** The time range choice shows labels and passes the raw value to the controller. Top artists and tracks show names when the profile has them. When it has only counts, show a plain line, "Press Refresh to load names", never "--refresh". Trends explains Added and Dropped in plain words, using the range labels.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: Your Taste and Trends in plain words".

### Task 4: Find Songs

**Files:** modify `app/view/SearchView.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest`.

**Interfaces:** Consumes `TrackRow`. Produces `ViewFormat.candidateAdded(String title, int total): String`.

- [ ] **Step 1: Failing test** `candidateAdded_includesRunningTotal` (for example "Added All the Old Things. 2 songs are waiting for your next playlist.", singular for 1).
- [ ] **Step 2: Run and see it fail.**
- [ ] **Step 3: Implement.** Results and the waiting list use `TrackRow`. The waiting list has Remove and Clear all. The description says it finds songs by meaning or by name. The artists that the last search named stay shown.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: Find Songs with artists and a running candidate count".

### Task 5: Discover

**Files:** modify `app/view/DiscoveryView.java`, `app/view/WhyExpander.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest`.

**Interfaces:** Consumes `AppController.savedFamiliarBias()` and `TrackRow`. Produces `ViewFormat.mixLabel(double familiarShare): String` ("Mix: 60% new artists, 40% artists you know") and `ViewFormat.familiarityLabel(double familiarity): String` ("Artist you know" when 1.0 or more, otherwise "New artist for you").

- [ ] **Step 1: Failing tests** `mixLabel_roundsAndSums100`, `mixLabel_extremes`, `familiarityLabel_knownAndNew`, `mixLabel_defaultWhenNoSavedValue` (uses `discover.defaultFamiliarRatio`).
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** "Use my usual mix" hides the slider and shows `mixLabel(saved or default)`. Unticked, the slider starts at the saved value. Rows show `familiarityLabel` instead of the score. Use one "Why these songs?" for the list and "Why this song?" per row, as plain one-sentence text in a scrollable label.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: Discover with plain mix wording and song labels".

### Task 6: Playlists and the Create dialog

**Files:** modify `app/view/PlaylistView.java`, `app/view/CreatePlaylistDialog.java`, `app/view/NegotiationDialog.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest`.

**Interfaces:** Consumes `AppController.deletePlaylist(String)`, `getCandidates()`, `AgentRequest(rawText, kind, constraints, useCandidates)`, `TrackRow`. Produces `ViewFormat.parseHoursMinutes(String hours, String minutes): int`. It returns 0 when both are blank or 0, and throws `IllegalArgumentException` with a wording message for letters, a negative number, minutes over 59, or a total outside the planner's min and max.

- [ ] **Step 1: Failing tests** `parseHoursMinutes_blankIsFromGoal`, `parseHoursMinutes_oneHourThirty_is90`, `parseHoursMinutes_minutesOver59_refused`, `parseHoursMinutes_letters_refused`, `parseHoursMinutes_outOfRange_refused`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
  - **Playlist rows:** Open and Delete. Delete asks for confirmation, naming the playlist and saying a saved version is kept, then refreshes the list.
  - **Track rows:** `TrackRow` with icons and Block.
  - **Badge wording:** plain, for example "Meets your rules".
  - **Create dialog:**
    - Length in hours and minutes fields.
    - "Include songs with explicit lyrics".
    - "Let the AI choose the mix from my goal" hides the slider.
    - A "Start with these N songs" checkbox lists the waiting songs and sets `useCandidates`; it is hidden when there are none.
    - The result "Why these songs?" text scrolls and is never cut off.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: Playlists with delete; Create with hours, minutes and candidate choice".

### Task 7: Your Rules

**Files:** modify `app/view/PreferencesView.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest` or the controller tests as needed.

**Interfaces:** Consumes `findArtists(String)`, `blockArtist(String)`, `findTracks(String)`, `blockTrack(String)`, `savedFamiliarBias()`, `displayPreference`, `setPreference`, `parsePreference`, and `ViewFormat.mixLabel`.

- [ ] **Step 1: Failing test** `preferenceError_isPlain`: a refused value gives a message without "22-character" or "id". Change the properties wording to suit.
- [ ] **Step 2: Run and see it fail.**
- [ ] **Step 3: Implement.**
  - **Description:** rules MusicMind follows for every playlist and recommendation.
  - **Explicit:** Allow or Block radio buttons.
  - **Mix:** a slider with `mixLabel`.
  - **Blocked artists:** type a name, Find, pick one of the matches by name, Block. The list shows names, each with a Remove.
  - **Blocked songs:** search, then pick from `TrackRow` results. The list shows titles where known.
  - **Preferred styles:** a text field.
  - **Errors:** `IllegalStateException` messages from `blockArtist` and `blockTrack` are shown in plain words.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: Your Rules with pickers instead of ids".

### Task 8: History

**Files:** modify `app/view/HistoryView.java`, `ViewFormat`, `musicmind.properties`; test `ViewFormatTest`.

**Interfaces:** Produces `ViewFormat.versionChange(int fromCount, int toCount): String` ("27 → 22 songs", "27 songs (no change)") and `ViewFormat.isRoutine(AgentSession): boolean` (true for profile and trend views).

- [ ] **Step 1: Failing tests** `versionChange_counts`, `versionChange_same`, `isRoutine_profileViewTrue_createFalse`.
- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
  - **Activity timeline:** newest first, with a "Show routine views" toggle that is off by default.
  - **Saved versions:** grouped under each playlist name, each line showing the date and `versionChange` against the previous version.
  - **Restore:** opens a preview of the version's songs first, then asks for confirmation.
- [ ] **Step 4: Run tests.**
- [ ] **Step 5: Commit** "GUI: History as a timeline with versions grouped by playlist".

### Task 9: Live check, docs and screenshots

**Files:** modify `docs/STAGE2_PLAN.md`, `docs/GUI_TEST_FINDINGS.md`, `docs/DAILY_TEST_NOTES.md`, `docs/AI_COLLABORATION_LOG.md`.

- [ ] **Step 1:** `./gradlew build --rerun-tasks --no-build-cache`, with 0 failures.
- [ ] **Step 2:** Launch the GUI read-only with a throwaway JavaFX driver in the scratchpad (as on Nov 2). Capture each screen and both dialogs, including a long-title search, before login, and the database down. Look at each capture. Do no create, modify, undo, restore, delete, block or feedback on the real account.
- [ ] **Step 3:** Mark each finding in `GUI_TEST_FINDINGS.md` as done, or say why not. Add the STAGE2_PLAN entry (Original → Change → Reason → Impact) for the visible renames and the redesign. Add today's notes and log row.
- [ ] **Step 4: Commit** "Record the frontend redesign and its live check".
