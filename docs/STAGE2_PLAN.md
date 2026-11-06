# Stage 2: Plan, Status and Changes from Stage 1

**Window:** Oct 20 to Nov 10, 2026. **Due:** Nov 10, 2026. **Last updated:** Nov 5, 2026.

This file records two things: where Stage 2 stands, and every difference between the
Stage 1 design and the code, each as **Original -> Change -> Reason -> Impact**. Feature by
feature status is in `docs/STAGE1_CONFORMANCE.md`; the binding plan and the rulings
R1 to R20 are in `docs/STAGE2_IMPLEMENTATION_PLAN.md`.

## Status on Nov 5

- All twelve Stage 1 features (F1 to F12) are implemented behind the shared `AppController`
  and reachable from the CLI and the GUI. `./gradlew build` passes with 1020 tests and no
  database server or keys (the count after the Last.fm-everywhere work).
- What was tested against the real Spotify and OpenAI accounts, and what was not, is listed in
  `docs/DAILY_TEST_NOTES.md`. Nothing in this file claims more than that file says.
- The GUI is merged into `main` with pull request #4: the dark Spotify-like redesign with a
  left-hand menu, one shared song row, a confirm dialog, and every screen restyled. Your Taste
  also has a Past week range, and Create only keeps familiar songs that fit the goal and uses
  Last.fm song tags (optional `LASTFM_API_KEY`). The Nov 5 code review of the Last.fm work
  applied five fixes and rejected one suggestion (see section 4).
- Genres and tags now all come from Last.fm; MusicBrainz is removed (see the Nov 5 Last.fm entry in section 3). That change has not been run against the real Spotify, Last.fm and OpenAI accounts yet; only the unit tests (1020) have run.
- Not done yet: the Stage 2 Implementation Report (PDF or Word, named `stage2-report`), the
  updated UML diagrams (`docs/diagrams/uxf` still holds the Stage 1 versions), and the
  feature demonstration videos.

## Commit cadence (at least 5 meaningful code commits per period, Toronto time)

A code commit here means a commit that changes files under `src/` or the build files.
Counts come from `git log` with `TZ=America/Toronto`; Period 3 was counted on Nov 5.

| Period | Dates | Required | Code commits | Status |
|---|---|---|---|---|
| 1 | Oct 20-26 | 5 | 0 | Missed. The single 5-point deduction already applies. |
| 2 | Oct 27-Nov 2 | 5 | 147 (Oct 30: 23, Oct 31: 28, Nov 1: 55, Nov 2: 41) | Met. |
| 3 | Nov 3-9 | 5 | 51 so far (Nov 3: 2, Nov 4: 42, Nov 5: 7) | Met. Documentation-only commits do not count. |

Commits that start Period 2 (links for the report): see "Weekly commit summary" at the end of
this file.

## Phases

- [x] **Phase 0, scaffold (Oct 30).** Gradle and JavaFX build, domain model, core interfaces,
  `PlaylistValidator` with tests, the Strategy classes, CLI skeleton.
- [x] **Phase 1, spine and first slice (Oct 30 to 31).** `SpotifyAdapter` (PKCE),
  `RetryingMusicService`, `OpenAiProvider`, PostgreSQL storage and repositories,
  `MemoryManager`, `AppController` and `MusicAgentFacade`, F1 in the CLI. F1 ran live on Oct 31.
- [x] **Phase 2, agent features (Oct 31 to Nov 1).** F2, F3, F4, F8, F9, then F5 with F11, F6, F7,
  F10 and F12 in the CLI. Run live on Oct 31 and Nov 1 as far as `docs/DAILY_TEST_NOTES.md` says.
- [x] **Phase 3, Stage 1 gaps and GUI (Nov 1 to 2).** Playlist listing, session detail, all three
  time ranges in trends, blocked tracks, artist names, candidates (Task 25); the seven GUI tabs
  (Task 13).
- [x] **Backend test fixes (Nov 2).** Search merge, artist names on tracks, goal review, scoring
  floor, optional candidates, blocking by choice, delete a playlist.
- [x] **Documentation and conformance (Nov 3).** This file, `STAGE1_CONFORMANCE.md`, README,
  AI log.
- [x] **GUI redesign, Past week, goal fit and Last.fm tags (Nov 4 to 5).** Dark theme and left menu,
  working through `docs/GUI_TEST_FINDINGS.md`; merged as pull request #4. Screenshots and videos
  of the new GUI still to be made.
- [ ] **Goal-fit proof.** Create several playlists (workout, study, road trip) on the real account
  to see whether the goal review improves them. Not yet run since the fixes.
- [ ] **Report, UML and videos (Nov 8 to 9).** `stage2-report` with the seven required sections,
  updated UML, a demonstration video per feature.
- [ ] **Submit (Nov 10).** Check the repository is public, the build runs, the links open, and no
  secrets are committed.

## How to read the entries

Each entry says what Stage 1 specified (**Original**), what the code does instead
(**Change**), why (**Reason**), and what else it touches (**Impact**). "Task N" names the task in
`docs/STAGE2_IMPLEMENTATION_PLAN.md` where the change was made. "R1" to "R20" are the rulings in
that plan. Supporting classes added under R20 are marked "(R20)". Spotify content (titles, artist
names, album names) is never stored; entries say "ids only" where that matters.

Superseded on Nov 5: older entries that name MusicBrainz (the R18 genre source, the Task 21 fixes, the
goal-review wording) record what was built then. Genres and tags now come from Last.fm; see the
entry "Last.fm replaces MusicBrainz" in section 4.

## 1. Stack, build and configuration

- **PostgreSQL through JDBC, tables created on start** -
  Original: Stage 1 names PostgreSQL behind `MemoryManager` and `Repository<T>`, with no detail on
  setup or tests. Change: `memory/Database` connects with the `DATABASE_URL`, `DATABASE_USER` and
  `DATABASE_PASSWORD` settings and creates six tables if they are missing (`user_preference`,
  `feedback`, `agent_session`, `playlist_snapshot`, `candidate_track`, `artist_genre`). Tests run
  against PostgreSQL started from embedded binaries (`io.zonky.test:embedded-postgres`), so no
  server is needed to build. When the database is down the app keeps working with safe defaults
  and says so (F8 alternative case). Reason: a reviewer must be able to run `./gradlew build` with
  nothing installed, and Stage 1 requires the database error cases. Impact: `PostgresTestBase` in
  the tests; the README explains the one-time `CREATE ROLE` and `CREATE DATABASE`.
- **Gradle 8.14.3 -> 9.1.0 with the foojay toolchain resolver** -
  Original: Gradle wrapper 8.14.3. Change: wrapper 9.1.0 plus `org.gradle.toolchains.foojay-resolver-convention`
  in `settings.gradle`. Reason: Gradle 8.14.3 cannot run on JDK 25, and a machine with only JDK 25
  has no Java 17 toolchain. Impact: `./gradlew` works on JDK 17 to 25 with no `JAVA_HOME` set-up; the
  project still compiles as Java 17; the first build downloads a JDK 17 if none is found
  (needs network).
- **JavaFX 17.0.6 -> 21.0.13** -
  Original: JavaFX 17.0.6. Change: 21.0.13, an LTS line that supports Java 17. Reason: on macOS 26
  the 17.0.6 native window code crashed on launch (`NSTrackingRectTag` exception in `libglass`).
  Impact: the GUI launches; no GUI code changed for this.
- **Run scripts (R15)** -
  Original: Stage 1 writes `musicmind <command>`. Change: `./gradlew installDist` makes
  `build/install/musicmind/bin/musicmind` (the CLI) and `bin/musicmind-gui` (the GUI, with JavaFX
  on the module path); `./gradlew run` starts the GUI and `./gradlew runCli --args="..."` the CLI.
  Reason: Gradle needs two start scripts and a named executable is closer to the Stage 1 text.
  Impact: `build.gradle` (`applicationName`, `guiStartScripts`, `runCli`). See also the CLI entry
  in section 7.
- **Settings in `musicmind.properties` read through `Config` (Constraint 10)** -
  Original: Stage 1 names no configuration. Change: every tunable (retry counts, tolerances, score
  weights, page sizes, caps), the OpenAI URL and default model, and every user-facing sentence are
  keys in `src/main/resources/musicmind.properties`, read through `Config` (loaded as UTF-8).
  Secrets and per-machine values (`SPOTIFY_CLIENT_ID`, `SPOTIFY_REDIRECT_URI`, `LLM_API_KEY`,
  `LLM_MODEL`, `DATABASE_*`) come from `.env` or the environment. The mood-to-genre map is
  `mood-genres.json`. Reason: Stage 2 says hard-coded demonstration responses do not count as
  functionality, and numbers and wording should be changed without editing code. Impact: no key,
  id or canned result is in `src/main`; `ConfigTest` covers placeholders and the default model.
- **Authorization Code with PKCE (public client)** -
  Original: Stage 1 names PKCE and a git-ignored `.env`. Change: only `SPOTIFY_CLIENT_ID` is needed
  (no client secret); the redirect URI is `http://127.0.0.1:8080/callback`; the refresh token is
  kept in `.spotify-tokens.json` (git-ignored). Reason: there is no server to hold a secret.
  Impact: `.env.example` lists the client id, redirect URI, LLM key and model, and the three
  database settings.
- **The LLM is OpenAI, behind `LLMProvider`** -
  Original: Stage 1 names `LLMProvider` and a mock. Change: `OpenAiProvider` (default model `gpt-4o`,
  chosen by `LLM_MODEL` or `llm.defaultModel`) is the only provider the app builds; `MockLLMProvider`
  exists only under `src/test` (R17). With no `LLM_API_KEY`, features that need the LLM say so, and
  features with a Stage 1 fallback (F1 summary, F2 narrative, F3 keyword search) use that fallback
  and label it. Reason: Stage 2 forbids canned demonstration answers. Impact: no mock is reachable
  from the running app. `Config.llmModel()` ignores a leftover placeholder or an old model name
  from an earlier `.env` (`LEGACY_MODEL_PREFIX` in `Config`).
- **Spotify February 2026 Development Mode shapes** -
  Original: Stage 1 lists the endpoints `POST /me/playlists` and `/playlists/{id}/items`. Change:
  `SpotifyAdapter` also reads a playlist entry's track from `item` and accepts the older `track`,
  sends the `DELETE` body key `items`, searches at most 10 results per call and pages with
  `offset`, and never calls `/recommendations`, audio features, related artists, popularity, batch
  artist reads, artist top tracks or `/users/{id}`. Reason: those calls are closed to this app.
  Impact: discovery is built from the user's own listening data (Stage 1 already says so);
  `SpotifyAdapterTest` runs the adapter against a local `HttpServer`.

## 2. Rulings R1 to R19: additive members

R17 (the test double) is in section 1, under the OpenAI entry. R20 (supporting classes) is in
`docs/STAGE1_CONFORMANCE.md`, section 6.

Stage 1 is silent or inconsistent in these places. Each ruling adds a member and changes no Stage 1
name or signature.

- **Top artists (R1)** - Original: `getTopItems(type, range): List<Track>` cannot return the
  artists that SD03 needs for genres. Change: `MusicService.getTopArtists(range): List<Artist>`;
  the tool `getTopItems` with argument `type=artists` routes to it. Reason: genres belong to
  artists. Impact: implemented by `SpotifyAdapter`, passed through the decorators.
- **`TopItems` (R2)** - Original: `ProfileBuilder.build(topItems, saved)` gives no type for
  `topItems`. Change: `domain/TopItems(List<Artist> artists, List<Track> tracks)`. Reason: one
  argument type for both lists. Impact: `ProfileBuilder.build` takes it.
- **Repositories know the current user (R3)** - Original: `Repository<T>.save(entity)` and
  `savePreference(p)` take no user, but entities have no owner. Change: repositories take a
  `Supplier<String>` current-user id in their constructor and stamp it on save;
  `findByUser(userId)` filters explicitly; ids are `String`. Reason: keeps the Stage 1 method
  signatures. Impact: `PreferenceRepository`, `FeedbackRepository`, `SessionRepository`,
  `PlaylistHistoryRepository` and `CandidateRepository` are built this way.
- **The profile is stored as derived data (R4)** - Original: "ListeningProfile is persisted", but
  `MemoryManager` has only `saveSession`, and Spotify's terms forbid storing content. Change: the
  profile goes into an `agent_session` row as JSON holding only the genre histogram, ids, summary
  and date (later also each top artist's genres and each top track's artist ids, see section 3).
  There is no profile repository. Reason: R4 and the Spotify terms. Impact: `profile` can show a
  stored profile with artist names replaced by a count.
- **Stored profile versus rebuild (R5)** - Original: `profile [--refresh]` has no stated meaning.
  Change: `profile` shows the latest stored profile if one exists, else builds one; `--refresh`
  always rebuilds. `AppController.analyzeTaste(user)` always rebuilds and
  `AppController.lastProfile(user)` returns the stored one. Reason: avoids a Spotify read per
  view. Impact: additive controller method; `--range` always rebuilds.
- **Search query parser (R6)** - Original: F3 lists `parseGoal()`, which returns a `PlaylistSpec`.
  Change: `Planner.parseSearchQuery(text): SearchQuery`; `parseGoal` stays the plan parser. Reason:
  different result types. Impact: additive method and the `SearchQuery` value class.
- **Profile binding for strategies (R7)** - Original: `selectCandidates(pools, spec)` has no
  profile, but the helpers `scoreFamiliarity` and `excludeKnown` need one. Change:
  `AbstractRecommendationStrategy.bindProfile(profile)`, called by `RecommendationEngine` before a
  strategy runs. Reason: keeps the Stage 1 signature. Impact: a strategy used without binding
  throws a clear `IllegalStateException`.
- **Constraints on the request (R8)** - Original: `AgentRequest` has only `rawText` and `kind`, but
  `create` has flags. Change: optional `PlaylistSpec constraints` (and later `useCandidates`);
  explicit values override what the LLM parsed. Reason: carry `--minutes`, `--familiar`,
  `--no-explicit`, `--name`. Impact: the 2- and 3-argument constructors stay.
- **Finished session and negotiation hook (R9)** - Original: `createPlaylist` returns a
  `Playlist`, yet F5 shows an explanation and progress and F11 needs a mid-run choice. Change:
  `AppController.lastSession()` exposes the finished `AgentSession`;
  `setNegotiationHandler(NegotiationHandler)` supplies the choice (CLI prompt or GUI dialog);
  `setProgressListener(Consumer<String>)` publishes the state names. Reason: keeps the return
  type and puts no interface code in the core. Impact: both interfaces use the same hooks.
- **Undo across CLI runs, `unfollowPlaylist`, reorder on undo (R10, R11, R12)** - Original: the
  `history` Deque of `PlaylistManager` is in memory, so undo would not work across CLI runs;
  `CreatePlaylistCommand.undo()` has no receiver method; `addItems` has no position, so undoing
  a removal could not restore the order. Change: `PlaylistManager` saves a snapshot before each
  change and `undo` falls back to the playlist's newest unused snapshot when the stack is empty
  (R10); `MusicService.unfollowPlaylist(playlistId)` calls `DELETE /me/library?uris=spotify:playlist:{id}`
  and the scope `user-library-modify` was added (R11); undoing a removal re-adds the track and
  then calls `reorderItems` to its old index (R12). Reason: each CLI command is a new process.
  Impact: see "Command pattern, PlaylistManager and snapshot undo" in section 5.
- **Resolving a playlist id to its session (R13)** - Original: `explain(sessionId)` in the
  controller but `explain <playlistId>` in the CLI. Change:
  `MemoryManager.findSessionByPlaylist(playlistId)`; `AppController.explain` accepts either id.
  Reason: users know playlist ids. Impact: `SessionRepository.findByPlaylist` returns the
  creating session only.
- **Snapshots hold ids only (R14)** - Original: `saveSnapshot(playlist)` stores a `Playlist`,
  but content may not be stored. Change: a snapshot keeps the playlist id, name and ordered track
  uris; `loadSnapshot` returns a `Playlist` of tracks with an id and a blank title. Reason:
  Spotify terms. Impact: restore re-reads titles from Spotify when it needs them.
- **Login (R16)** - Original: UC01 has no class method. Change: `AppController.login(): User`
  and `currentUser()`. Reason: the interfaces need a sign-in call. Impact: the CLI `login` command
  and the GUI "Log in to Spotify" button.
- **Artist genres come from MusicBrainz, not Spotify (R18)** (superseded on Nov 5: genres and tags now come from Last.fm, see the Last.fm entry in section 4) -
  Original: Stage 1 reads genres from Spotify artist metadata. Change: `GenreEnrichingMusicService`
  (a Decorator over `MusicService`) fills `Artist.genres` through `GenreService` and
  `MusicBrainzAdapter` (an Adapter), cached by artist id in `artist_genre`. Reason: live testing
  on Oct 31 showed Spotify returns no `genres` to a Development Mode app. Impact: F1, F2, F4 and
  F5 get a genre signal again; coverage can be partial, which `ProfileBuilder` reports, and the
  CLI credits MusicBrainz. Genres are derived data, so caching them respects the Spotify terms.
- **Search sends genre as a plain keyword (R19)** -
  Original: Stage 1 F3 and F4 pass genre to Spotify search as a `genre:` filter. Change:
  `SearchQuery.toSpotifyQuery()` appends the genre as a keyword (skipped if the keywords already
  contain it); `year` and `artist` filters stay, and LLM filters outside `search.allowedFilters`
  are dropped. An empty F3 result is retried once with the keywords alone before the no-results
  notice. Reason: live testing showed `upbeat indie running genre:indie` returned 1 track while
  `upbeat indie running` returned 20. Impact: F3 and F4 (`CandidateRetriever.byGenre`) return
  results; genre still ranks results.
The test double (R17) is recorded in the OpenAI entry in section 1. Supporting classes under R20
are listed one by one, with their reasons, in `docs/STAGE1_CONFORMANCE.md`, section "Classes
beyond Stage 1".

## 3. Data, discovery and search: changes found by live testing

- **Discovery excludes known tracks; artist-album page size (Task 20)**
  - **Original:** `discover` could return tracks from the user's library as "familiar" picks, and one `spotify.albumPageSize=50` served both album endpoints.
  - **Change:**
    - `RecommendationEngine.recommend(profile, spec, feedback, allowLibraryTracks)` (the Stage 1 3-arg form delegates with true; `MusicAgentFacade.discover` passes false per call, nothing is remembered; known track ids from the profile are excluded too) removes top, saved and recently played tracks from every pool, so the familiar pool becomes new tracks by artists the user knows.
    - F5 can allow library tracks again.
    - `spotify.artistAlbumPageSize=10` and `spotify.albumTrackPageSize=50` replace the shared key.
    - Equal scores keep source order.
    - Failed-read notices and the all-reads-failed error are short messages with a count, and the "library excluded" constraint is reported only when it applies.
  - **Reason:** Live test showed 13 of 20 results came from the user's history (Stage 1 F4 says exclude tracks the user already knows) and `/artists/{id}/albums` returns HTTP 400 above limit 10.
  - **Impact:**
    - F4 results are all new to the user.
    - F5 and Task 10 are unaffected.
- **Saved tracks are read up to `spotify.maxSavedTracks` (Task 21)**
  - **Original:** `getSavedTracks` shared the 500-item paging limit (`spotify.maxItems`), so a larger library silently lost tracks and discover could suggest them as new.
  - **Change:**
    - Saved tracks have their own cap (default 5000).
    - When a read stops at the cap with more left, the returned list is a `TrackList` (an `ArrayList<Track>` with `isTruncated()`), which passes unchanged through `RetryingMusicService`, `GenreEnrichingMusicService` and `SpotifyTool`, so no `MusicService` signature changes.
    - `MusicAgentFacade` adds the `text.savedTracksCapped` notice to `ListeningProfile.notices` and to the discover notices.
  - **Reason:** A silent cut made F4's "exclude known tracks" wrong without telling the user.
  - **Impact:** Additive class and config key; the profile is marked partial when the cap was hit.
- **Same-name MusicBrainz artists give no genres (Task 21)** (superseded Nov 5: MusicBrainz is removed)
  - **Original:** `MusicBrainzAdapter.findMatch` returned the first candidate whose name (or alias) matched and whose score reached `musicbrainz.minScore`.
  - **Change:** If more than one different candidate passes both checks, the lookup returns no genres, as a cached no-match (nothing is logged to the user).
  - **Reason:** Two artists sharing a name (for example two bands called "Nirvana") made the first hit a guess, and wrong genres are worse than none.
  - **Impact:**
    - Some artists show no genres.
    - `GenreEnrichingMusicService` caches the empty result like any other no-match.
- **MusicBrainz outage pause (Task 21)** (superseded Nov 5: the same pause now guards Last.fm)
  - **Original:** Every artist was looked up even while MusicBrainz was failing, so one run could wait on dozens of timeouts.
  - **Change:** After `musicbrainz.maxConsecutiveFailures` (3) failed lookups in a row (timeouts, connection failures, server errors; a rate-limit 503 never counts, and requests are spaced `musicbrainz.minIntervalMillis`=1100 apart), lookups are skipped for `musicbrainz.cooldownSeconds` (300); a skipped lookup is unknown, so it is not cached and counts as a failure in the existing partial-genres notice.
  - **Reason:** A run must finish quickly during an outage.
  - **Impact:** None when MusicBrainz works; one failed lookup after the pause ends starts the next pause.
- **Stored profile keeps artist genres and track artists; old profiles are rebuilt (Task 21)**
  - **Original:** `derived_json` kept artist and track ids only, so a stored profile scored differently from a fresh one.
  - **Change:** It also keeps each top artist's genres (MusicBrainz data) and each top track's artist ids; profiles stored before this load without them, and `AppController.discover` rebuilds a stored profile older than `profile.maxAgeHours` (24).
  - **Reason:** F4 scoring uses genre affinity and known artists.
  - **Impact:** Derived data only, no names or titles.
- **Review hardening of retries and sign-in (Task 21)**
  - **Original:** Stage 1 F5, F6 and UC01 say a failed call is retried with backoff and an expired login is renewed, without saying how. The first code retried a whole playlist change as one request, opened the browser after any token-endpoint failure, wrote the token file with default permissions, and trusted any callback.
  - **Change:**
    - Playlist adds and removals are split into `spotify.addBatchSize` batches. Each batch is retried on its own, so a retry never re-sends an earlier batch.
    - `OAuthTokenStore.accessToken` opens the browser only after a 400 or 401 from the token endpoint. Any other failure is rethrown with the refresh token kept.
    - `.spotify-tokens.json` is created and rewritten as owner-only where POSIX permissions exist.
    - The OAuth callback page HTML-escapes what it shows and ignores a callback with the wrong `state` instead of aborting the login.
    - F1, F2 and F3 show short messages from `musicmind.properties` instead of raw errors. `ToolResult` gained an optional `cause`.
  - **Reason:** Findings of the code review of Task 21. A transient network error should not send the user to the browser, and a retry must not apply a change twice.
  - **Impact:** No interface change except the optional `ToolResult.cause`. Covered by `OAuthTokenStoreTest`, `RetryingMusicServiceTest`, `SpotifyAdapterTest` and `ToolManagerTest`.
- **One Config for the retry decorator and the adapter (Task 12)**
  - **Original:** `RetryingMusicService` read its retry count, longest wait and batch size from `Config.defaults()`, while `SpotifyAdapter` used its own `Config`.
  - **Change:** `RetryingMusicService(MusicService, Config)` takes all three from the same `Config` the adapter gets, so the batch size cannot differ.
  - **Reason:** A retry batch larger than the adapter's batch would resend more than one request's worth.
  - **Impact:** Constructor change; the test seam takes a `Config` instead of a retry count.
- **`ListeningProfile.savedCapped` (Task 12)** - Original (Task 21): the saved-track cap was detected by comparing notice text in `MusicAgentFacade`. Change: the profile has `isSavedCapped()`, stored in the derived data as `savedCapped`; a profile stored before it existed loads as not cut short. Reason: matching wording breaks when wording changes. Impact: additive field and JSON key.
- **Trend genre coverage (Task 12)**
  - **Original:** Stage 1 F2 narrates added and dropped genres from the ranges' histograms.
  - **Change:**
    - `TrendReport.artistsWithGenres/artistCount/genreCoveragePartial/coverageSummary` count the artists with genre data per range.
    - Below `trends.minGenreCoverage` (0.5) in any range, the comparator adds a notice. `PromptBuilder.buildTrendPrompt` states the coverage as a fact and tells the model to say it is partial and not to claim a certain change.
    - The computed narrative says so, and lists genres as "found only in" a range instead of "newly prominent".
    - The CLI prints `genre data: X of Y artists` per range.
    - A review made `coverageSummary(rangeOrder)` follow the `profile.ranges` order, made `TrendComparator` build the report once, and appended the `trendCoveragePartial` notice when an LLM narrative does not say coverage is partial.
  - **Reason:** Live data showed many Persian artists have no MusicBrainz genres, so a "dropped genre" was often just missing data.
  - **Impact:** Derived from existing `TrendReport` data (no new component). Keys `trends.minGenreCoverage`, `text.trendCoveragePartial`, `text.trendCoverageLine`. Every remaining CLI output line is a `text.cli*` key.
- **Search ranking weights (Task 12)** - Original: `search.weight.titleMatch=0.7`, `search.weight.spotifyOrder=0.3`. Change: 0.1 and 0.9. Reason: live mood search put stock-music titles that repeat the query words above Spotify's best matches; Spotify's relevance order should lead, with title match a small boost (about two places in a page of 20). Impact: configuration only; `SearchRankerTest` and a facade test updated.
- **Mood words kept out of search text (Task 12 review)**
  - **Original:** Stage 1 F3 sends the LLM's keywords to Spotify.
  - **Change:**
    - `buildSearchPrompt` asks for artists, activities, places, eras and a genre, not mood words.
    - `Planner.parseSearchQuery` removes any word the curated `MoodGenreMap` knows as a mood (`upbeat` and `mellow` were added to `mood-genres.json`) and adds `search.moodGenres` (1) mapped genre.
    - The raw keyword fallback is unchanged.
  - **Reason:** Live search `upbeat indie songs for running` returned stock titles such as "Upbeat Corporate Inspiration" because Spotify matches title words.
  - **Impact:** The query text changes; results are never filtered by title.

- **F4 reasons are computed, not phrased by the LLM** -
  Original: Stage 1 F4 says the LLM is optional, "used only to phrase reasons". Change: `discover`
  never calls the LLM; each recommendation's reason is built from recorded facts (known or new
  artist, where the track was found, your feedback), and `AppControllerDiscoverTest.discoverNeverCallsTheLlm` checks
  it. Reason: computed reasons cannot state anything that is not true, and the optional call
  would add cost and a failure path to a feature that is otherwise deterministic. Impact: F4 works
  with no LLM key; F4 is deterministic in the conformance table.

- **Your Taste: Past week, counted from recent plays (F1; Nov 4)** — Original: Your Taste offered the three Spotify periods (`short`, `medium`, `long`) and `normalizeRange` refused anything else; Trends compares the same three. Change: a fourth range `week`. Spotify has no top items for a week, so `MusicAgentFacade.analyzePastWeek` reads the new `MusicService.getRecentPlays()` (a `Play` is a track and its `played_at`, parsed by `SpotifyAdapter`, never stored), keeps the plays of the last `profile.weekDays` (7) days, ranks tracks and artists by play count (at most `profile.weekTopLimit`), and fills genres through the new `MusicService.withGenres(artists)` (`GenreEnrichingMusicService`, the same MusicBrainz lookup and cache as the other ranges). `ProfileBuilder.build` gained an overload whose information notices do not make a profile partial; the screen says "Built from your last N plays (Spotify only keeps your 50 most recent)", or that there were no plays in the last 7 days. Only derived data is stored (range `week`, ids and genres), as for other ranges. `AppController.timeRanges()` is now week, short, medium, long (Your Taste, the CLI `--range`); the new `trendRanges()` keeps Trends and `trends` to Spotify's three. `ViewFormat.rangeLabel("week")` is Past week. Reason: the owner asked for a past week twice. Impact: additive; a week built from at most 50 plays is thin and is labelled so, and it is left out of Trends because comparing it with the other periods would mix a 50-play sample with Spotify's own rankings. Nothing new is sent to OpenAI (the summary prompt takes the same profile data).

## 4. F5 playlist creation, F10 explanation and the LLM's role

- **The LLM may remove retrieved tracks (difference from Global Constraint 2)** -
  Original: Stage 1 gives the LLM no part in choosing tracks, and Global Constraint 2 of the
  implementation plan says "the LLM never selects tracks and never receives bulk catalogue data".
  Change: since Nov 2 the LLM reviews real tracks that were already retrieved and may only return
  ids to **remove**. `GoalFitReviewer` does this in `RetrievingState` (before selection, up to
  `goal.reviewMaxTracks`=120 tracks, new ones first) and in `SelectingState` (the final picks, at
  most `goal.pickReviewRounds`=2 times per run). It never names or adds a track; ids it was not
  given are ignored; the final choice and order stay deterministic in `RecommendationEngine`. The
  review is best effort: with no LLM, a failure, a malformed reply, or a reply that would remove
  more than the `goal.minKeepShare` limit allows, nothing is removed and the notice
  `goalReviewSkipped` is shown. Reason: Sarina's live test on Nov 2 produced unsuitable songs for
  a "workout" playlist, and Spotify gives this app no tempo, energy or other audio features to
  judge fit by, so the model can only judge by genre. Impact: two statements of the constraint
  changed. The LLM now influences which tracks stay (by removal only). The first version of the
  review sent each track's id, title and artist names to OpenAI; Spotify's Developer Policy
  (section III, item 14) forbids ingesting Spotify Content into an AI model, so on Nov 3 it was
  changed: the prompt now carries only the track id and the genres known for it (from the search
  that found it, the user's profile, or the cached MusicBrainz genres; no new requests), tracks
  with no known genre are not sent and are kept, and a test checks that no `PromptBuilder` prompt
  contains a title or artist name. The constraint's "never receives bulk catalogue data" now holds
  in that form: ids and MusicBrainz genres, no Spotify titles or names. The review is less
  precise than before and cannot judge tracks without a genre. Whether it improves playlists on
  the real account is not yet proven (no playlist has been created since the change). It costs up
  to three extra LLM calls per create.
  The full entry is "Playlists suit the goal" below.
- **F5 create flow, state lifecycle and F11 negotiation (Task 10)**
  - **Original:** Stage 1 SD01 has `createPlaylist` call `Planner.plan`, load preferences, retrieve pools, `ConstraintChecker.checkFeasibility(spec, pool)`, `RecommendationEngine.recommend`, `PlaylistValidator.validate` (bounded loop), `PlaylistManager.executeAll([CreatePlaylistCommand, AddItemsCommand])`, `saveSession` and an explanation, with `AgentSession` moving through `SessionState`s. F11 asks the user to relax an infeasible request.
  - **Change:**
    - **Lifecycle.** `AgentSession.run()` drives `IdleState -> PlanningState -> RetrievingState -> SelectingState -> ValidatingState -> ExecutingState -> DoneState`. Any error goes to `FailedState` (status `failed`, or `cancelled` when the user declines F11), and `MusicAgentFacade.createPlaylist` rethrows the original exception. Phase logic is in the state classes; `RunContext` holds the run and `RunServices` its collaborators.
    - **Planning.** `Planner.plan(request[, preferences])` merges, per setting, explicit constraints (R8) over the goal over stored preferences. It rejects out-of-range flags and unclear goals with `ClarificationNeededException`, and ignores artist ids the LLM invents. The plan reply gained `confidence` and `mood` and no longer asks for artist ids.
    - **Seed genres.** The goal's mood (through `MoodGenreMap`) and the stored `preferredStyles` become seed genres. `RecommendationEngine.recommend(profile, spec, feedback, allowLibraryTracks, seedGenres)` searches them first, and their reasons say "genre you asked for".
    - **One Spotify read.** `RecommendationEngine` splits into `retrieve(...)` returning a `Retrieval` and `recommend(Retrieval, spec, feedback)`, so the feasibility check and every re-selection share one read. The Stage 1 three-argument `recommend` and the discover four-argument form behave as before.
    - **F11 options.** `ConstraintChecker` counts the minutes of eligible familiar and new tracks. It reports a too-short pool, or a familiar share one side cannot fill, with `Relaxation`s (shorten, change the share, allow explicit). `Planner.phraseOptions` has the LLM word them (used only if it returns one sentence per option). `Planner.regenerate` applies the chosen one.
    - **F11 outcomes.** `NegotiationHandler.choose` returning empty cancels with nothing written. No handler, no relaxation, or `plan.maxNegotiationRounds` used up ends the run with an explanation.
    - **Validation.** `ValidatingState` sends a result outside the length tolerance back to selection with a corrected length, up to `plan.maxValidationRetries` (3) times. A result still too short is a shortfall and is negotiated.
    - **Expired token.** Renewed once per run (`AppController` gets a reauthenticator, `OAuthTokenStore::login`) and the phase repeats.
    - `AddItemsCommand` also accepts a `Supplier<String>` playlist id, so it can follow `CreatePlaylistCommand` in one batch.
    - `ExplanationService.explain(session)` (the F10 core) produces the explanation: LLM text checked by `verifyExplanation`, else a labelled computed sentence, else a no-trace note.
  - **Reason:** Follows SD01 and UC06/UC12 and keeps the facade small. The mood map and preferred styles were otherwise unused.
  - **Impact:**
    - The session stores the final spec, seed genres and track ids in its derived data.
    - The CLI `create "<goal>"` is a thin dispatch with the F11 prompt (completed in the CLI entry in section 7).
  - **Members added:** `ClarificationNeededException`, `InfeasibleRequestException`, `NegotiationCancelledException`, `NegotiationHandler`, `Relaxation`, `CandidatePool`, `Retrieval`, `ExplanationService`, `FeasibilityReport.relaxations`, `AgentPlan.seedGenres`, `Planner.regenerate/phraseOptions`, `ResponseParser.parsePlanExtras`, `MoodGenreMap.moods`, `PreferenceSettings.preferredStyles`, `AgentSession.run/context/failure/notices/explanation`, `SessionState.isTerminal`, `MusicAgentFacade.createPlaylist/lastSession/profileFor/setNegotiationHandler/setProgressListener`, `AppController.createPlaylist/lastSession/setNegotiationHandler/setProgressListener`; config keys `plan.*` and wording `text.plan*`, `text.conflict*`, `text.relax*`, `text.negotiation*`, `text.explain*`.
- **Create-flow additions under R20 (Task 10 fixes)**
  - **Original:** Stage 1 `recommend(profile, spec, feedback)` takes a profile and reads Spotify on every call, and `PlaylistValidator.validate` is the only length rule.
  - **Change:**
    - F5 uses an overload `RecommendationEngine.recommend(Retrieval, spec, feedback)` (with `retrieve(...)` and the `Retrieval` value) so feasibility checking and each re-selection share one Spotify read.
    - `PlaylistValidator.durationStatus(tracks, spec)` is the single length rule, used by `validate` and by `ValidatingState`, which now sends any violation back to selection within `plan.maxValidationRetries` (UC06 7a).
    - `ExecutingState` keeps a playlist whose rollback failed (`RunContext.leftoverPlaylistId`) so a resume after re-login fills it instead of creating a second, and `FailedState` reports it in a notice.
  - **Reason:** Avoid duplicate Spotify reads and orphan playlists; match UC06 7a.
  - **Impact:** Additive members above; wording keys `executeLeftover`, `negotiationBadChoice`, `cliProblemPrefix`, `cliChoosePrompt`; no Stage 1 signature changes.
- **F5 familiar share means known artists; mood ranking; titles in the explanation (Task 22)**
  - **Original:** With library tracks allowed, only library tracks were familiar. A known artist's album track filled the 30% share, and a live run of Stage 1's own example ("90-minute study playlist, mostly familiar but with some new artists", `--familiar 0.7`) gave 22 tracks, all by known artists.
  - **Change:**
    - `RecommendationEngine.isFamiliar` and `Retrieval.isFamiliar` treat library tracks and any track by a known artist as familiar, so the rest must be by new artists. F4 is unchanged: its library is already excluded.
    - Within each pool, tracks matching the seed genres (found for them, or by a known artist with one) rank first.
    - New notices `createFewNewArtists` and `createFewGenreMatches` report a shortfall.
    - `ExplanationService.explain(session, titles)` and `ResponseParser.replaceTrackIds` show titles from the run (display only, never stored), and "one of the tracks" when none are known (`explain` from stored data). They never show ids. The prompt and `verifyExplanation` still use ids.
  - **Reason:** Stage 1 F5 asks for a familiar/new balance and a curated mood mapping, and `explain` printed raw 22-character ids.
  - **Impact:** F5 now yields about 30% new-artist tracks when candidates exist. `explain` output changes wording.
- **F5 widening reads genres only (Task 22 review)**
  - **Original:** Widening raised the familiar ratio for every run, so in F5 a few new-artist candidates were dropped for a familiar-only strategy.
  - **Change:**
    - With library tracks allowed, widening only reads more genres and keeps the ratio and strategy.
    - `createFewNewArtists` fires whenever the result holds fewer new-artist tracks than asked.
    - F4 widening is unchanged.
    - Titles containing double quotes are shown with single quotes.
  - **Reason:** Stage 1 lets only F4 lower novelty.
  - **Impact:** F5 keeps every available new-artist track.
- **Candidates are a choice; explanations stay honest (F3, F5, F10; backend test fixes, Task 3)**
  - **Original:** Every create run started with all stored candidates, and Stage 1 F10 forbids claims outside the recorded facts. In user testing, tracks waiting in the candidate list were forced into a "workout" playlist, and the explanation said they suited the mood.
  - **Change:**
    - `AgentRequest` gained `useCandidates`. The 2- and 3-argument constructors default to true, so the default is still Stage 1's: the next playlist starts with them.
    - With it false, `PlanningState` loads no candidate ids, so the run uses none and reports none as skipped, and `DoneState` removes none. The stored list is left alone. CLI `create ... --no-candidates` sets it.
    - `PromptBuilder.buildExplainPrompt` adds the rule `text.explainCandidateRule`: a track the user added may be described only as added by the user, and no track may be said to fit a mood, activity or goal unless its own reason says so.
    - `AppController.getCandidates()` is unchanged, so the GUI can list them. The Create dialog still has no candidate choice (section 7).
  - **Reason:** Candidates are a choice the user should be able to leave out, and an explanation may claim only recorded reasons.
  - **Impact:** Additive record component and overloads; one new wording key; the `create` usage line lists the new flag.
- **Playlists suit the goal: activity searches and a goal-fit review (F5; backend test fixes, Task 4)**
  - **Original:** New tracks came only from searching genres (guessed from the mood and stored styles), and Stage 1 gave the LLM no part in selection.
  - **Change:**
    - **Activity searches.** The plan prompt also asks for up to `goal.maxSearchTerms` short search phrases for the activity or setting (`AgentPlan.searchTerms`, `ResponseParser.PlanExtras.searchTerms`; the 3- and 4-argument `AgentPlan` constructors stay). `CandidateRetriever.byKeywords` searches each phrase next to the seed genres. Those tracks join the new-music pool with their own reason (`text.reasonSourceKeyword`, new `Source.KEYWORD`).
    - **What the review sends.** `GoalFitReviewer` (prompt kind `GOAL_REVIEW`) sends the goal and, per retrieved track, the Spotify id and genres only: no title, artist name or album. It covers up to `goal.reviewMaxTracks` (120) tracks, counted over the tracks that have a genre, new ones first.
    - **Where the genres come from.** MusicMind's own data, with no new request: the genre the track was searched under (`RecommendationEngine.Origin.genre`), the genres of the user's own artists in the listening profile, and the cached MusicBrainz genres of its artists (`artist_genre`, read only through `ArtistGenreCache`, filled by `GenreEnrichingMusicService`). They are read via `Retrieval.knownGenres` and `GoalFitReviewer.genresOf`. A track with no known genre is not sent and is kept.
    - **What the model may do.** It is told it sees only genres, to remove a track only when its genres clearly do not suit the goal, and to keep it when unsure. It replies with ids to remove; ids it was not given are ignored. `Retrieval.without` drops the removed ones from every pool.
    - **Where it runs.** `RetrievingState` applies it before reading candidates. Tracks the user picked are never sent or removed, and a candidate that is also in the pool is not sent.
    - **Best effort.** With no LLM, a failure, a malformed reply, no track to send, or a reply leaving less than `goal.minKeepShare` of the list (measured over the whole list passed in, so a large pool keeps its removals and a small pool stays protected), nothing is removed and the notice `goalReviewSkipped` is shown.
    - **Pick review.** After selecting, `SelectingState` sends the picks that are not the user's candidates to `GoalFitReviewer.reviewPicks` (same prompt and failure rules, no keep-share guard). Rejected ones are removed with `Retrieval.without` and selection runs again, at most `goal.pickReviewRounds` (2) reviews per run, counted in `RunContext.pickReviewRounds` because `ValidatingState` can re-enter selection. If the replacement would leave the playlist below its length tolerance, the previous picks and retrieval are kept with the notice `goalReviewPartial`. A failed pick review adds `goalReviewSkipped`. Each notice appears once.
    - **Stock titles, applied locally.** `RecommendationEngine.score` subtracts `discover.weight.keywordTitlePenalty` (0.3) from a goal-search result whose title holds a word of the search phrase (`Retrieval.searchTerms`). It is a nudge, never a filter. Titles are used locally and never sent.
    - In the final code review `goal.reviewMaxTracks` rose from 60 to 120, so keyword and seed-genre tracks are not crowded out by the library.
  - **History:** Until Nov 3 the prompt also carried titles and artist names; removed because of Spotify Developer Policy section III item 14.
  - **Reason:** Sarina's test result and the missing audio features (Spotify gives this app no tempo, energy or other audio data). The LLM names no track and chooses none. It only takes out real, already retrieved tracks that clearly do not suit the goal, and the final pick stays deterministic in `RecommendationEngine`. This changes Stage 1's statement that the LLM takes no part in selection.
  - **Impact:**
    - The review is less precise than a title-based one, because genres say less than titles. Tracks with no known genre (for example many keyword search results by artists outside the profile and cache) are not judged at all.
    - New `RunServices` component and `GoalFitReviewer` class; `RecommendationEngine.retrieve` overload with search terms; `Retrieval` carries the search terms.
    - `buildGoalReviewPrompt`, `review` and `reviewPicks` take a `TrackGenres`. `AppController` and `MusicAgentFacade.CreateSupport` take an optional `ArtistGenreCache` (the old constructors default to none).
    - Tunables and wording in `musicmind.properties`: `goal.*`, `discover.weight.keywordTitlePenalty`, `goalReviewSkipped`, `goalReviewPartial`.
    - A guard test (`PromptBuilderTest`) checks that no `PromptBuilder` prompt holds a track title or artist name.
    - F4 Discover and modify pass no search terms and are unchanged.
- **Scores tell tracks apart (F4, F5; backend test fixes, Task 5)**
  - **Original:** `RecommendationEngine.affinity` gave 1.0 to any track from a seed genre or found by an activity search, so every such track scored the same (0.60 in Sarina's test) whatever the user's taste.
  - **Change:** A track found for the goal (a seed genre, or a keyword source) has genre affinity `floor + (1 - floor) * bestTasteAffinity`, with `discover.goalAffinityFloor=0.7` (`Config.discoverGoalAffinityFloor`); other tracks keep their best taste affinity.
  - **Reason:**
    - Goal tracks must still rank above unrelated tracks even with no taste match (a plain additive bonus let them fall below ordinary tracks and undid the activity search), yet they should differ by how much the user likes the artist or genre.
    - Seed and keyword tracks use the same floor.
  - **Impact:** Scores of goal tracks fall slightly (at most 0.3 of the genre weight) and now vary; one tunable added; feedback boost and penalty are unchanged.

- **Create: library songs count as familiar only when they fit the goal (F5; Nov 4)** — Original: about 40% of a Create playlist came from the user's own library chosen by taste alone, so "slow dance" got a pop/dance song. Change: when a Create run has seed genres or search terms, `RecommendationEngine` drops every familiar-pool track (library, album track or genre result by an artist the user knows) from the candidates unless it is goal-fit: any genre known for its artists (profile top artists, or the cached MusicBrainz genres through the new `RecommendationEngine(tools, config, ArtistGenreCache)` constructor and `Retrieval.knownGenres`) is one of the seed genres (case ignored), or its id is in `Retrieval.goalSourceIds`, which holds the goal's keyword-search results today and is where the Last.fm tag source (Task 3) will add ids. When fewer goal-fit library songs exist than the familiar share needs, new goal tracks fill the shortfall and the notice `familiarShortForGoal` says so. Reason: the owner's test of Nov 4. Impact: Discover (F4: no seeds, no terms, or library excluded) is unchanged; selection stays deterministic and no LLM or OpenAI input changes. Tests: `RecommendationEngineTest` (`create_*`, `discover_unchanged_libraryRules`) and `CreatePlaylistFlowTest`.
- **Last.fm replaces MusicBrainz; tags in Find Songs, Discover, Modify (F1-F6, F10; Nov 5)**
  - **Original:** Two sources described music. Artist genres came from MusicBrainz (R18), which has genres for artists only, while Create used Last.fm tags (song tags and tag charts). Find Songs relied on Spotify text search, Discover on the user's own artists and genre searches, and Modify's edit prompt carried no song descriptions.
  - **Change:**
    - `LastFmAdapter` is the Adapter behind `GenreService` and the new `TagService`; `MusicBrainzAdapter`, its `musicbrainz.*` settings and its tests are removed. There is no fallback: an artist Last.fm does not know has no genres.
    - Artist tags come from `artist.gettoptags`. `TagFilter` drops junk (a blocklist such as "seen live" and "favorites", the artist's own name, bare years, tags under a minimum count) and keeps at most 10, for artist and song tags alike. Failures reuse the cooldown the MusicBrainz adapter had.
    - `artist_genre` has a `source` column; rows that are not `lastfm` are deleted at start-up, so no MusicBrainz genre is read again.
    - Find Songs: for mood or genre searches, `TagTrackMatcher` turns the top songs of the matching Last.fm tags into Spotify tracks (exact title and artist, session cache, stops at a rate limit or auth error) and puts them first; at most 10 extra Spotify searches. `GenreNames.canonical` treats spelling variants ("hip hop" and "hip-hop", "r&b" and "rnb") as one genre when comparing.
    - Discover: songs by artists Last.fm lists as similar to the user's top artists (`artist.getsimilar`, `CandidateRetriever.bySimilar`, source `SIMILAR`), at most 10 extra searches.
    - Modify: `SongTagReader` adds each song's Last.fm tags (the song's own, else its artists', else none) to the edit prompt next to the id, never a title or artist name; up to 30 lookups within 15 seconds.
    - One banner (GUI) or one line (CLI) says tags are off when `LASTFM_API_KEY` is missing. The footer credits Spotify and Last.fm only.
  - **Reason:** We chose Last.fm because it has tags for artists and songs, including moods and activities, which the features need, and one source keeps the vocabulary the same on every screen. MusicBrainz has genres for artists only. Spotify stays the source of the user's plays, playlists and songs; Last.fm cannot replace it.
  - **Impact:**
    - Trade-off (owner's decision): all Last.fm tags are used alike, as data. A mood tag such as "chill" can appear under "Top genres" and be searched like a genre. The cost is some noisier results for mood tags; the benefit is one vocabulary everywhere. No app wording changed except the data credit and the partial-coverage notice, which now names Last.fm.
    - Policy: artist names and song titles may go to Last.fm and are never stored; Last.fm tags are not Spotify content, so they may be cached and sent to OpenAI. No Spotify title, artist or album goes to OpenAI.
    - Without a key the app runs with no genres from Last.fm; some artists have none even with a key, which is acceptable for a test dataset.
    - Not tested live: these changes have only run in unit tests (1020, no network), not yet against the real accounts.
- **Create: Last.fm tag songs matched to Spotify (F5; Nov 4)** — Original: new Create songs came from Spotify genre searches and the goal's activity searches, and Spotify gives this app no tempo, energy or playlist-of-others data, so "slow dance" and "workout" still got songs that only matched by title words. The live finding behind the change: Spotify returns 403 for other users' playlists, so a playlist someone made for the goal cannot be read either. Change: the plan prompt also asks for up to `lastfm.maxTags` short Last.fm-style tags such as "slow dance" or "romantic" (`AgentPlan.tags`, `PlanExtras.tags`; the older `AgentPlan` constructors give an empty list; the prompt still forbids song and artist names). New `TagService` (`topTracks(tag, limit)`) with `LastFmAdapter` (Adapter over Last.fm `tag.gettoptracks`; a single-object `track` reply parses, a malformed element is skipped, an error never carries the key). `CandidateRetriever.byTag` takes each Last.fm song (at most `lastfm.maxTracks` Spotify lookups per run, across tags) and runs one Spotify search `track:"title" artist:"artist"` with limit 1; the result is kept only when the normalised title (`SearchRanker.normalise`, also with a trailing "(...)", "[...]" or " - Remastered" part removed) and one artist name both match, so a wrong-artist or loose match is dropped and never replaced. `RecommendationEngine` has `Source.TAG`; tag tracks join the new-music pool, their ids are added to `Retrieval.goalSourceIds` (so a library song that is also a tag song counts as goal-fit under the Task 1 rule), they get the goal affinity floor, a score bonus `discover.weight.tagBonus=0.15`, and rank ahead of other tracks inside their pool; blocked artists, blocked tracks and explicit rules apply to them as to any track. `AppController.create()` builds the `LastFmAdapter`; the `RecommendationEngine` and `AppController` constructors without a `TagService` use `TagService.NONE`. The key `LASTFM_API_KEY` is optional: without it `topTracks` returns nothing and makes no request, and Create adds one notice (`text.lastfmOff`) when the plan had tags; if Last.fm fails the failure is counted with the other read failures (`discoverReadFailed`) and Create carries on. Last.fm terms: "Song tags from Last.fm" (`text.lastfmAttribution`) is in the GUI footer next to the Spotify and MusicBrainz credits and in the CLI after a Create. Reason: the owner's test of Nov 4 (finding 16): a tag list is the one goal-specific song source that needs neither audio features nor other users' playlists. Impact: Last.fm titles and artists are not Spotify content but are used only to build a Spotify query and compare names; they are never stored and never sent to OpenAI (the goal review still sends ids and genres only, and the planner prompt holds the user's own words only); the Spotify titles read for matching are not stored either. Selection stays deterministic and the LLM names no song. Discover (F4) passes no tags and is unchanged. Tests: `LastFmAdapterTest`, `PlannerPlanTest.plan_readsTags`, `CandidateRetrieverTest.byTag_*`, `RecommendationEngineTest.create_tagTracks*` and `create_lastfm*`, `CreatePlaylistFlowTest.create_lastfm*`, `ConfigTest.footerShowsLastfmCredit`.
- **Create: familiar picks must fit by leading genres and by the song's own Last.fm tags (F5; Nov 4)** — Original: a familiar track fit the goal when any genre known for its artists was a seed genre, and the first live run showed that was far too loose: "music for studying" (ambient, classical, instrumental, lo-fi) picked 12 Nirvana songs, "slow dance" picked Billie Jean and "Pop", "workout" picked 8 songs by one rapper. Root cause (checked live with the cached genres printed): the filter did run, but MusicBrainz lists dozens of tags per artist and Nirvana's list holds "ambient" and "instrumental" far down it, Michael Jackson's holds "soul" and "r&b", so every artist matched some goal. Change: (1) `Retrieval.leadingGenres` and `create.fitLeadingGenres=3`: only an artist's first three genres (profile genres, else cached MusicBrainz) and the genre the track was searched under count in `RecommendationEngine.fitsGoal`. (2) When Last.fm is configured and the plan has tags, `TrackTagFit` asks `TagService.trackTags(artist, title)` (new default method, `LastFmAdapter` implements `track.gettoptags`, error 6 is an empty list, the key and title never appear in a message) for the song's top `lastfm.trackTagsKept=10` tags; a familiar track fits only if one of them equals, as letters and digits ignoring case, a plan tag or a synonym from `lastfm.synonyms.<key>` in `musicmind.properties` (a tag that merely contains a plan word, such as "slow", does not count). At most `lastfm.maxTrackTagLookups=30` songs are looked up per retrieval, once each (answers are kept in `Retrieval`, shared by `without`, and in memory only); after one failed lookup no more are made. When Last.fm is off, fails, is capped, or does not know the song, the leading-genre rule decides, and a failure is counted with the other read failures (`discoverReadFailed`); with no key the existing `lastfmOff` notice appears. A song with a goal source (keyword or Last.fm tag song) still fits. (3) In a Create run that has Last.fm tag songs, a genre search result whose title holds the genre word ("R&B All Night", "Ambiente") is scored down by `discover.weight.genreTitleContainsPenalty=0.6` (never below the bare-title penalty); Discover and runs without tag songs are unchanged. Reason: the owner's live test; artist genres are too coarse to separate one song from another. Impact: titles go only to Last.fm, are never stored and never sent to OpenAI; selection stays deterministic and the LLM adds nothing. Disclosure: with a Last.fm key, up to `lastfm.maxTrackTagLookups=30` song titles with artist names per Create run (plus the plan's tags) are sent to Last.fm to read their tags; nothing goes to OpenAI. Review round (Nov 4): tag and keyword songs now count as matching the goal in the "match the genres you asked for" notice; the pick order has three tiers (tag song and genre match; either; neither) before the ordinary score; zero kept familiar songs says `familiarNoneForGoal` instead of "Only 0 of N"; `CandidateRetriever.byTag` stops after the first Last.fm failure; MusicBrainz genre lists are already ordered by vote count (`MusicBrainzAdapter`) and the cache and enrichment keep that order, so "leading" means most voted. Tests: `RecommendationEngineTest` (`create_*GenresWith*`, `create_songTag*`, `create_withTagSongs_aGenreResult*`), `LastFmAdapterTest.trackTags_*`.
- **Create: Last.fm review fixes (F5; Nov 5)** — Original: the first Last.fm tag used the whole `lastfm.maxTracks` budget; slow Last.fm could stall Create with up to 30 sequential calls; each Create repeated up to 20 Spotify searches; a non-array `tags` in the plan failed it; synonyms of every single word of a tag were added. Change: (1) `CandidateRetriever.byTag(tag, share)`: the run's tags share the budget, share = ceil(maxTracks / tags). (2) Not changed: the review also suggested letting a song's genre tags ("soul") count for a requested genre, but that would let pop hits by artists also tagged soul or r&b (Billie Jean) back into "slow dance", which the leading-genre entry above removed on purpose; a song's own tags must name the goal. (3) `lastfm.trackTagBudgetSeconds=15`: after that time from the first song lookup no more are made and the genre rule decides. (4) In-memory, session-only cache of Last.fm song to Spotify matches in `RecommendationEngine` (`lastfm.matchCacheSize=500`, oldest dropped; failed searches not cached; never stored). (5) `ResponseParser`: a string `tags` is one tag, any other non-array is ignored. (6) `Config.lastfmSynonyms` looks up the whole tag key only. Reason: code review of the Last.fm work. Impact: second tag now returns songs; Create waits at most about 15 s on song tags; repeat Creates use fewer Spotify calls; no data stored or sent to the LLM. Tests: `CandidateRetrieverTest`, `TrackTagFitTest`, `PlannerPlanTest`, `ConfigTest`, `RecommendationEngineTest`.

## 5. F6 modify, F7 validate, F12 history and restore, and the Command pattern

- **Command pattern, PlaylistManager and snapshot undo (Task 9; R10, R11, R12)**
  - **Original:** Stage 1 lists `PlaylistCommand` (`execute`, `undo`) with five concretes and `PlaylistManager` (`history`, `execute`, `executeAll`, `undo`).
  - **Change:**
    - The five commands work through `MusicService`. `CreatePlaylistCommand.undo` unfollows the playlist. `RemoveTrackCommand` and `ReplaceTrackCommand` re-add and then reorder to the original index.
    - `executeAll` undoes the commands already applied if one fails, and rethrows. A successful batch is one undo step (`BatchCommand`).
    - `PlaylistManager.modify(current, commands)` saves a snapshot first. If the database is down the change is refused with a clear message.
    - `undo` with an empty stack restores the latest snapshot (`MemoryManager.loadLatestSnapshot`), or says why it cannot.
    - `ResponseParser.forPlaylist(PlaylistContext)` returns a parser bound to a playlist. Its one-argument `parseCommands(json)` (the Stage 1 signature) binds `CommandSpec`s to real tracks: positions or ids from the playlist, tracks to add from Spotify search, never from the LLM.
  - **Reason:** R10 needs undo across CLI runs, and the snapshot must exist before a change is made.
  - **Impact:**
    - A snapshot stores only ids and the playlist name.
    - A failed restore puts the old tracks back, and duplicate copies of a track are restored by undo.
    - Tasks 10 and 11 call `modify`.
  - **Members added:** `PlaylistCommand.playlistId()` and `describe()`, `PlaylistChangeException`, `PlaylistContext`, `ResponseParser.forPlaylist`, `PlaylistManager.modify/snapshotBeforeModify/restore(int)`, `PlaylistHistoryRepository.findLatestByPlaylist`; config keys `command.*` and `text.cmd*`.
- **F6 modify, F7 validate-by-id, F10 grounded explanation, F12 history and restore (Task 11)**
  - **Original:** Stage 1 SD02 has `modifyPlaylist(id, instruction)` call the LLM, `parseCommands`, `executeAll` and `validate`, with an Undo that calls `PlaylistManager.undo`. F7 lists explicit, duplicate, duration, blocked-artist and min/max length rules in `PlaylistValidator`. F10 has `ResponseParser` check that the explanation references only recorded facts. F12 has `getHistory`/`restore` replay a snapshot through `PlaylistManager`.
  - **Change:**
    - **Modify flow.** `MusicAgentFacade.modifyPlaylist` reads the playlist and asks `PlaylistEditor` (a small supporting class under R20) for an `EditPlan`. It calls `PlaylistManager.modify` (snapshot, then one undoable batch), re-validates against the spec saved with the creating session (else the stored preferences, and `lastValidationNote()` says which), and records the edit as a `MODIFY_PLAYLIST` session.
    - **Edit prompt.** It carries each track's id, explicit flag and minutes (no titles), so "remove explicit" and "15 minutes shorter" can be answered. The reply may also carry a `mood` and a clarifying `question`.
    - **Mood edits.** A feeling-only instruction ("calmer") has no command, so `PlaylistEditor` uses `MoodGenreMap`. It swaps out up to `edit.mood.maxChanges` tracks whose artists have known genres (from the stored profile) that all miss the mood's genres, or adds `edit.mood.addCount` tracks found by searching the mood's genres as plain keywords (R19). The result is flagged "Approximate".
    - **Ambiguous edits.** The model's question (or a request to rephrase) comes back as `ClarificationNeededException` with nothing changed.
    - **F7.** `PlaylistValidator` gains the minimum and maximum track-count rule (`playlist.minTracks`, `playlist.maxTracks`). `ValidationResult` gains `offending()` (rule name to track ids).
    - **F10.** `ResponseParser.verifyExplanation` now also rejects numbers, quoted names and curated-list genres that the decision trace does not record, so the LLM prose cannot add facts. A rejected text is replaced by the labelled computed explanation. `AppController.explain(id)` accepts a session id or a playlist id and reads the trace persisted by the creating run.
    - **F12.** `restore(snapshotId)` saves the playlist's current content first (so a restore can be undone), then `PlaylistManager.restore` replays it and drops the stale undo entries. If the Spotify playlist is gone it throws `PlaylistMissingException`, and `recreateFromSnapshot(snapshotId)` rebuilds it with `CreatePlaylistCommand`.
    - **Errors.** Spotify failures on a playlist become short messages (no permission: only owned or collaborative playlists can be read; not found; general), with no status code, URL or JSON.
  - **Reason:** Follows SD02 and UC07, UC08, UC11, UC13, and keeps the facade small.
  - **Impact:**
    - `SessionRepository.findByPlaylist` now returns only the creating session, so later edits do not hide its spec and trace.
    - `BatchCommand` no longer adds a failure to its own suppressed list.
    - The CLI gains minimal `modify`, `undo`, `validate`, `explain`, `history` and `restore` commands (completed in the CLI entry in section 7).
  - **Members added:** `PlaylistEditor`, `PlaylistEditor.EditPlan`, `ResponseParser.parseEditExtras/EditExtras`, `PromptBuilder.buildEditPrompt(String, PlaylistContext)` (replaces the id-list form), `MoodGenreMap.genreNames`, `PlaylistValidator.RULE_*` and a `Config` constructor, `ValidationResult.offending`, `PlaylistMissingException`, `SnapshotInfo`, `SessionRecord.specOf`, `MemoryManager.findSession/loadSnapshotSummaries`, `PlaylistHistoryRepository.summariesByUser`, `MusicAgentFacade.undo/validatePlaylist/lastChangeSummary/lastModifyNotices/lastValidationNote/restore/recreateFromSnapshot`, `AppController.modifyPlaylist/undo/validatePlaylist/explain/getHistory/getSnapshots/restore/recreateFromSnapshot`; config keys `playlist.minTracks/maxTracks`, `edit.mood.*`; wording `text.modify*`, `text.playlist*`, `text.validate*`, `text.restoreMissing`, `text.explain*`.
- **F11 during modify (Task 12 review)**
  - **Original:** Stage 1 F11 says "An interactive prompt presented during create / modify", but only create negotiated.
  - **Change:**
    - `ConstraintChecker.checkEdit(before, commands)` runs the edit's commands on the track list (`PlaylistCommand.simulate`, a default method overridden by remove, replace and add).
    - If the playlist would end outside `playlist.minTracks`..`playlist.maxTracks`, it reports the conflict with an `APPLY_PARTIAL` `Relaxation` ("apply only the first N of M changes") for the largest valid prefix.
    - `MusicAgentFacade.modifyPlaylist` asks the existing `NegotiationHandler` (wording from `Planner.phraseOptions`) before any snapshot or Spotify write. The chosen steps are applied and a notice says so.
    - Cancel throws `NegotiationCancelledException`. No valid prefix throws `InfeasibleRequestException`. With no handler, the options are listed in `InfeasibleRequestException`. In all three nothing is written.
    - `runModify` sets the handler, so the GUI gets it the same way.
  - **Reason:** UC12 covers create and modify.
  - **Impact:** Additive `Relaxation.Kind.APPLY_PARTIAL`, `PlaylistCommand.simulate`, `ConstraintChecker.checkEdit`; wording keys `conflictEdit*`, `relaxPartial`, `modify{None,NoHandler,Cancelled,Adjusted,BadChoice}`.
- **F6 adjustment loop and single selection notice (Task 23)**
  - **Original:** Stage 1 F7 says the validator's result "drives the adjustment loops in F5 and F6", but `modifyPlaylist` applied an edit and only reported the constraints it then broke. Also, a create run that re-selected after a validation retry kept every selection's notices, so a live run printed `createFewGenreMatches` twice (22 and 27 tracks).
  - **Change:**
    - Before any Spotify write, `MusicAgentFacade.modifyPlaylist` simulates the edit (`PlaylistCommand.simulate`) and asks `PlaylistValidator.introducedViolations` for the explicit, duplicate and blocked-artist violations the edit adds.
    - If there are any, it asks `PlaylistEditor` to plan again with those violations stated, at most `plan.maxValidationRetries` times.
    - An edit that still fails goes to the F11 negotiation already built for modify. `ConstraintChecker.checkEdit(before, commands, spec)` now also reports introduced violations and offers the longest passing prefix (`APPLY_PARTIAL`). Cancel, no handler or no passing prefix write nothing.
    - The edit is judged against the playlist's rules (saved spec, else preferences) with the duration target dropped. So "make it 15 minutes shorter" is not refused for missing the old length, and no duration target is invented. A violation the playlist already had does not count against an unrelated edit.
    - The `Playlist` signature `modifyPlaylist(playlistId, instruction)` is unchanged.
    - In F5, `SelectingState` replaces the previous selection's notices (`RunContext.selectionNotices`, `AgentSession.removeNotice`), so only the final selection's notice shows.
  - **Reason:** Stage 1 F7/F6 text. A duplicate notice with different numbers misleads.
  - **Impact:**
    - The post-edit violation notice no longer reports the old duration target.
  - **Members added:** `PlaylistValidator.introducedViolations`, `ConstraintChecker.checkEdit(.., spec)` and `introduced`, `AgentSession.removeNotice`, `RunContext.selectionNotices`; wording `conflictEditViolates` and `modifyReplanHint`.
- **Items found before merging PR #1 (Task 24)** - Each sub-entry gives its own Original, Change, Reason and Impact. Live evidence: a second `restore 1` printed "Restored ... Undo with" though nothing changed; after "make it 15 minutes shorter", `validate` reported "total duration 77 min is outside the target of 90 min"; an explanation said "the dance, edm, and electro genres you love" although those genres came from the goal; "Only 129 of 150 tracks match" counted any artist genre tag; `discover` listed tracks titled only "Hip Hop".
  - **Target length follows edits (F6, F7, F12)**
    - **Original:** Stage 1 F7 validates against the spec saved at creation, and the Task 23 edit loop dropped the duration target for every edit.
    - **Change:**
      - The edit reply may state `lengthChangeMinutes` (signed) or `targetMinutes` (a new total), read by `ResponseParser.parseEditExtras` and carried in `PlaylistEditor.EditPlan`.
      - `MusicAgentFacade.modifyPlaylist` moves the playlist's target by it, validates against that target, and records the target after, the target before and the snapshot id in the edit's `MODIFY_PLAYLIST` session. A stated length outside `parser.minTargetMinutes..maxTargetMinutes` is ignored, and so is a stated length when the user picks a partial edit in F11.
      - `undo` records the target it went back to. `restore` puts back the target in force when that snapshot was saved.
      - `validatePlaylist` and later edits read the newest recorded target. An edit that states no length is judged against the stored target again, so the duration notice stays.
    - **Reason:** The old target is wrong after a deliberate length change, but a silent drop hid real length problems.
    - **Impact:**
      - The stored values are numbers and ids only (no Spotify content). Sessions stored earlier have none of the keys and load unchanged.
    - **Members added:** `PlaylistTargets` (package-private, R20), `PlaylistManager.modify` returns the snapshot id, `EditPlan.lengthChangeMinutes/targetMinutes/statesLength/withCommands`; `EditExtras` gained two fields.
  - **Re-plan stops sensibly (F6, F11)** - Original: an LLM failure during a re-plan aborted the edit, and the loop asked again even when the answer repeated. Change: `replanWhileViolating` keeps the current plan when the LLM fails or cannot produce a plan and stops when a new plan has the same commands as the previous one; the plan then goes to F11 negotiation. Reason: the first plan is still a valid base for negotiation. Impact: fewer LLM calls; no interface change.
  - **Restore of an unchanged playlist (F12)** - Original: `restore` printed the "Restored ... Undo with" line even when the playlist already matched. Change: `MusicAgentFacade.lastRestoreChanged()` / `AppController.lastRestoreChanged()`; the CLI prints `text.cliRestoredUnchanged` and no undo hint when it is false. Reason: the hint offered an undo of a change that never happened. Impact: additive accessor and wording key.
  - **Requested genres in the trace and grounded wording (F10)**
    - **Original:** `DecisionTrace(strategyName, reasons, constraintsSatisfied)`.
    - **Change:**
      - A fourth component `requestedGenres` (the goal's seed genres; older stored traces load with none; the three-argument constructor stays).
      - `PromptBuilder.buildExplainPrompt` lists them as asked for in the goal, not the listener's taste, and `ResponseParser.verifyExplanation` rejects a preference claim ("you love", "you like", "your favourite", ...) that the trace does not contain.
    - **Reason:** Stage 1 F10 forbids claims outside the recorded facts, and "genres you love" is one.
    - **Impact:** Additive field and check; a rejected text falls back to the computed explanation as before.
  - **Genre match counts leading genres only (F4, F5)** - Original: `RecommendationEngine.matchesSeed` counted a track by an artist with any requested genre among its tags. Change: a track matches if it was found through a requested genre, or its artist's leading (first) genre is a requested one. Reason: "129 of 150" overstated the match. Impact: the count and the within-pool ranking of seed matches are stricter; nothing is filtered.
  - **Bare genre titles rank lower (F4, F5)** - Original: Spotify's text search returns tracks titled only with the genre word first. Change: `RecommendationEngine.score` subtracts `discover.weight.genreTitlePenalty` (0.15) from a track found by a genre search whose whole title is that genre (case and punctuation ignored). Reason: such tracks were listed first in `discover`. Impact: ranking only, nothing is filtered; `SearchRanker` (F3) and its weights are unchanged.
  - **Fix round 1 (Task 24)**
    - **Original:** A snapshot saved before targets were recorded had no target to go back to, so a restore kept the newest one.
    - **Change:**
      - `PlaylistTargets.targetAt(snapshot)` returns the target before the edit or restore that saved that snapshot. For a snapshot with no record, it returns the target before the next recorded change with a later snapshot id.
      - `PlaylistManager.undo` returns whether it undid a command of this run, and the facade reverts the newest unreverted record for that. For the snapshot fallback it reverts only the record that saved the newest snapshot, and only if not already reverted. So edit, undo, undo, and an undo that changes nothing, leave the target alone.
      - A re-plan that states no length keeps the first plan's (`EditPlan.keepingLengthOf`).
      - The explain prompt says requested genres came from the goal, the mood or the style requested.
    - **Reason:** A restored 90 minute playlist must be judged against its own 90 minute target. Live evidence: after `modify "make it 15 minutes shorter"` moved a 90 minute target to 75, `restore 1` (a snapshot saved before targets were recorded) left the target at 75 and `validate` failed (89 min vs 75).
    - **Impact:** No schema change; old rows keep working.
- **PR #1 final review fixes (F5, F6, F12)**
  - **Original:** R10 had undo restore the latest snapshot with an empty stack, and `RetryingMusicService` retried every call on a 5xx.
  - **Change:**
    - `playlist_snapshot` gained an `undone` flag (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`). Each undo, and each change that failed, uses up the newest open snapshot. A snapshot restored by undo is not pushed on the stack. `restore <id>` still reaches every snapshot.
    - `TrackList` keeps each track's real Spotify position, and `PlaylistOps.move` sends those positions.
    - Add, create and reorder are no longer retried on a 5xx. A 429 still is. Remove and unfollow are safe to repeat.
    - `BatchCommand.undo` re-applies its undone steps if a later one fails.
    - `modifyPlaylist` records the change before reading it back, and a failed read-back becomes a notice.
    - `recreateFromSnapshot` stores a `CREATE_PLAYLIST` session with the old constraints and that version's target.
    - Commands read the playlist once.
  - **Reason:** A repeated undo could re-apply a change the user had reverted (reproduced in a test), local files shifted positions, and a retried write could apply twice.
  - **Impact:**
    - Additive column on `playlist_snapshot`. Existing rows count as not undone, and only numbers and ids are stored.
    - `PlaylistTargets.latestSnapshotChange` is removed, since every undo now reverts the newest unreverted change.
    - Undo with nothing left now says so instead of reporting success.
- **Failure recovery in the commands (F6, F12)**
  - **Original:** Only `BatchCommand` and a failed restore put things back; a failed move inside Remove undo, Replace or an undone restore left the playlist half changed, and an undo whose snapshot already matched the playlist changed nothing but still reported success.
  - **Change:** `PlaylistOps.restoreCopies` takes its added copies out if a move fails, `ReplaceTrackCommand.execute` puts the old track back if its final move fails, `RestoreSnapshotCommand.undo` recovers the same way `execute` does, and undo skips snapshots that already match the playlist, reverting each skipped change's target.
  - **Reason:** These were deferred minors, but each could leave a duplicate or lost track, or report work that did not happen.
  - **Impact:** `PlaylistManager.undo` now returns how many changes it went back over instead of a boolean; test-only `slf4j-nop` silences a logging warning in test output.
- **Delete a playlist (addition beyond Stage 1; backend test fixes, Task 7)**
  - **Original:** Stage 1 has no delete. F6 edits tracks and F12 can recreate a playlist that was deleted in Spotify, but MusicMind itself could only remove the playlists it had just created (as an undo). In user testing a playlist could not be removed from MusicMind.
  - **Change:**
    - `DeletePlaylistCommand` (Command pattern) calls `MusicService.unfollowPlaylist`, Spotify's only delete.
    - `PlaylistManager.delete(Playlist)` saves a snapshot first and removes nothing if it cannot be saved (database down). It then drops the playlist's old commands from the undo stack.
    - The command is not pushed on the stack, since Spotify cannot restore a playlist under its old id. Its `undo` says so. The way back is F12: `restore` reports the playlist missing and offers `recreateFromSnapshot`.
    - `MusicAgentFacade.deletePlaylist` refuses unless the id is in the user's own list from `MusicService.getUserPlaylists()` (no owner field was added), and refuses a playlist too long to save whole. It passes auth-expiry and Spotify failures through the same handling as other playlist writes.
    - `AppController.deletePlaylist(playlistId)` returns the snapshot id. The CLI `delete <playlistId>` asks for the playlist's name to be typed and does nothing if it differs.
  - **Reason:** Deleting is destructive, so the CLI makes the user confirm by name, and the snapshot keeps it recoverable.
  - **Impact:**
    - Additive command, manager method, controller method and CLI command.
    - Wording keys `deleteNotOwner`, `deleteUndoUnavailable`, `cmdDelete`, `cliDelete*`, `cliUsage.delete`, `cliDesc.delete`.
    - Screens are not changed.
    - Collaborative playlists in the user's list can be deleted too (this removes them from the user's library only).

## 6. Stage 1 gaps closed, search and blocking

- **Stage 1 gaps closed in the backend and CLI (Task 25)** - Six differences from Stage 1 found while writing the progress report. The GUI shows the playlist list, session detail, trend steps and candidates; blocking by choice and delete have no screen yet (section 7).
  - **F6 listing playlists**
    - **Original:** Stage 1 says "opening a playlist", but no call listed them and an id had to be pasted.
    - **Change:**
      - `MusicService.getUserPlaylists()` (`GET /me/playlists`, owned or collaborative only, since Spotify lets an app read only those), `domain/PlaylistSummary`, `AppController.getPlaylists/getPlaylist`, CLI `playlists` and `show`.
      - A playlist with no creating session now takes its name from Spotify.
    - **Reason:** The GUI needs a list to open.
    - **Impact:** Additive interface method on every `MusicService`; nothing stored.
  - **F12 session detail** - Original: "opening a past session shows its plan and result", but `history` printed one line. Change: `history` prints session ids, `history <sessionId|playlistId>` prints request, status, plan and result; `AppController.getSession` is shared with `explain`. Reason: Stage 1 text. Impact: additive; `SessionRecord.trackCount`.
  - **F2 every step** - Original: the output is a diff per range, but only newest and oldest were compared. Change: `domain/RangeChange`, `TrendReport.steps()` (oldest first, skipping a range with no data), printed by the CLI and given to the LLM. Reason: Stage 1 text. Impact: `TrendReport` gained a component; the older constructor stays.
  - **F7 blocked tracks**
    - **Original:** Stage 1 checks "blocked artists/tracks", but only artists existed.
    - **Change:**
      - `PlaylistSpec.blockedTracks`, preference key `blockedTracks`, rule `blockedTrack` in `PlaylistValidator` (also in `introducedViolations`), excluded by `RecommendationEngine` and `ConstraintChecker`, stored in session specs (older specs load with none).
      - Spec copies now go through `with...` methods so that none can drop a field.
    - **Reason:** Stage 1 text.
    - **Impact:** Additive; only ids stored.
  - **F8 artist names** - Original: `blockedArtists` took Spotify ids. Change: names are looked up with `MusicService.searchArtists`, needing an exact match ignoring case, and stored as ids; `prefs list` shows names through `getArtist`. Reason: F8 says "disliked artists", and users know names. Impact: names are read for display and never stored (Spotify terms).
  - **F3 artists and candidates**
    - **Original:** Results are "track / artist results" with "add to candidates".
    - **Change:**
      - `search` lists artists whose name the query contains (a mood search would otherwise list artists that only share a word with it).
      - `CandidateRepository` and table `candidate_track` hold track ids.
      - CLI `candidates list|add|remove|clear`.
      - `SelectingState` puts candidates first, as far as the constraints and length allow, with the reason "you added it from search".
      - `DoneState` removes the ones used.
    - **Reason:** Stage 1 text, and a candidate set is only useful if it feeds a playlist.
    - **Impact:**
      - `MemoryManager` takes a sixth repository.
      - `ConstraintChecker.eligible` is public.
      - `getTrack` added to `MusicService`.
  - **Blocks apply to existing playlists**
    - **Original:** `validate` and `modify` judged a created playlist by the spec saved at creation only, so a track or artist blocked later was ignored there, and an edit could add it.
    - **Change:**
      - `PreferenceSettings.withBlocks` (replaced by `withCurrentBlocks` in the next entry) adds the current blocks to the saved spec in `MusicAgentFacade.specFor`.
      - Other preferences, such as the explicit policy, still apply only when a playlist is made.
    - **Reason:** Stage 1 says F6 reads preferences, and a block means never.
    - **Impact:** `validate` can now fail a playlist that passed before a block was set; its note says current blocks were used.
  - **PR #2 review fixes**
    - **Original:** Candidates were left out of the feasibility check; `findNew` took the first new search result even if blocked; a comma always split artist names; the first of several same-name artists was used with no word; `blockedTracks` accepted artist URIs; a failed candidate removal went unreported; `show` hid partial reads and skipped entries; every named playlist re-read the playlist list; setting a list preference to nothing left an empty row.
    - **Change:**
      - `RunContext.pool()` counts candidates.
      - `PlaylistContext` carries the rules, and edits pass over blocked results.
      - The longest run of comma-separated parts that names an artist wins.
      - Several artists sharing a name use Spotify's top match, with a notice. Live, Eminem, Muse, Genesis and Hayedeh each had 3 exact matches, so refusing them would make blocking by name unusable.
      - URI prefixes are stripped per key.
      - `DoneState` checks each removal.
      - `TrackList.skipped` and `lastPlaylistNotices` report what a read left out.
      - A read-back reuses the known name. Empty list preferences are removed.
      - `SpotifyAdapter.nextUrl` holds the next-page guard once.
    - **Reason:** Findings of the `code-review` skill on PR #2.
    - **Impact:** `PlaylistSpec.blocks(track)`, `PlaylistContext.rules`, `MemoryManager.deletePreference`.
  - **Second PR #2 review**
    - **Original:** Blocks were saved with a playlist and merged with current ones, so removing a block had no effect on playlists created while it was set.
    - **Change:**
      - `PreferenceSettings.withCurrentBlocks` makes the blocks in force now decide for every playlist; the spec saved at creation still records what applied then, but its blocks no longer judge the playlist.
      - This differs from Stage 1 F7, which validates against the saved spec, because a block stands for the user's current wishes; other preferences still follow the saved spec.
      - Also: a candidate keeps its own reason in the trace, only candidates that can be used count as available, notices belong to one `parsePreference`, trimmed tracks are not counted as skipped, restore replaces a placeholder name, the planner carries track blocks from a request, artists with no name are left out at the adapter, and the next-page guard needs the API path followed by a slash.
    - **Reason:**
      - Findings of the second `code-review` run, each reproduced with a test first.
      - The next-page test hung because the adapter really followed a lookalike link.
    - **Impact:** `RunContext.fittingCandidates` replaces the private helper in `SelectingState`.
- **Search merges what was typed with the AI's reading (F3; backend test fixes)**
  - **Original:** Stage 1 SD03 searches only for the query `Planner`/`ResponseParser` read from the user's words, and ranks it by `SearchRanker.rank`. In user testing the AI's reading drifted from the words typed, so an exact title or artist match could be missing or buried, and the same recording appeared more than once.
  - **Change:**
    - `MusicAgentFacade.withTypedWords` also searches the words as typed. It is skipped when the AI's query is the typed text, and when every typed word is a mood word (Spotify matches those to stock-music titles).
    - `SearchRanker.merge` orders results: a title equal to the typed text, a title holding every typed word, title plus artist names holding every typed word, then the AI's results.
    - Typed results that match none of those are kept only when the AI found nothing, and not at all when any typed word is a mood word (`merge(..., keepUnmatchedTyped)`).
    - Tracks with the same title, artists and duration in seconds count once. The result is cut to `search.resultLimit`.
    - A failed typed search leaves the AI's results as they are.
  - **Reason:** What the user typed should be found first, and a mood word must not bring back stock-music titles.
  - **Impact:** Additive `SearchRanker.merge` overloads. `rank` and the search entry points keep their signatures.
- **Track carries artist names; the Spotify display name (F3, F7; backend test fixes)**
  - **Original:** `Track(id, uri, title, durationMs, explicit, artistIds)` and `MusicService.currentUserId()` only, so a view could show neither who made a track nor the name of the signed-in user.
  - **Change:**
    - `Track` gained `artistNames` (the 6-argument constructor stays and gives an empty list; `SpotifyAdapter` fills it from the track's artists).
    - `MusicService.currentUserDisplayName()` is a default method returning the id, `SpotifyAdapter` reads `display_name` from the same `/me` request as the id (`RetryingMusicService` and `GenreEnrichingMusicService` delegate), and `AppController.currentUserName()` exposes it.
    - The search merge uses the names; the goal review does not (it sends ids and genres only, see the goal-fit entry).
  - **Reason:** A search result or a review line is not understandable without the artist, and the interface should greet the user by name.
  - **Impact:** Additive record component, default method and controller method; no schema change.
- **Blocking by choice and the saved-mix read (F7; backend test fixes)**
  - **Original:** Stage 1 F7 stores blocked artists as a typed list, so a typo or a shared name blocked the wrong artist or none. The saved familiar share could not be read apart from the safe defaults.
  - **Change:**
    - `AppController.findArtists(name)` lists every artist Spotify finds, those named exactly that first. `findTracks(text)` is the ranked search.
    - `blockArtist(id)` and `blockTrack(id)` add the chosen one to the stored list without dropping the others.
    - `savedFamiliarBias()` returns the saved familiar share or empty (the safe-defaults record does not count).
    - `blockArtist` accepts only an artist id or `spotify:artist:` URI matching `prefs.artistIdPattern` (`prefsBadArtistId`).
    - Both refuse with `prefsUnavailable` and save nothing when the stored preferences cannot be read, because the safe-defaults record would otherwise be saved over the blocks already stored.
  - **Reason:** The user should block what they chose, not what they typed, and a database outage must not erase blocks.
  - **Impact:** Additive controller methods, one tunable and three wording keys. `setPreference`, `parsePreference` and the stored format are unchanged.

## 7. The two interfaces

- **CLI complete: instance `CliApp`, exit status, interactive session, R15 scripts (Task 12)**
  - **Original:** Stage 1 lists `CliApp.run(args)` and the commands `login`, `help`, `exit`, `profile`, `trends`, `search`, `discover`, `create`, `modify`, `undo`, `validate`, `prefs`, `feedback`, `explain`, `history`, `restore`. The plan (R15) names `bin/musicmind` and `bin/musicmind-gui`.
  - **Change:**
    - `CliApp` is an instance built from a controller factory and input/output streams, so tests drive it through the real `AppController` with fakes.
    - `run(args)` returns 0 (done), 1 (could not complete, or `validate` found violations) or 2 (wrong arguments). `main` calls `System.exit` with it, so Gradle reports a failed command as a failure.
    - With no arguments, `run` starts an interactive session that reads one command per line until `exit`. The F11 prompt and the restore question read the same input. `login` shows the Spotify user; `exit` leaves the session.
    - `help` lists every command's syntax and description from `musicmind.properties`, as do `Usage:`, `Changed:`, `Notice:`, `PASS` and `FAIL` (`text.cli*`).
    - The "opens your browser" line prints only when `AppController.willOpenBrowser()` is true (no saved refresh token, `OAuthTokenStore.needsBrowserLogin()`).
    - `feedback` checks the id against `feedback.trackIdPattern` (22 letters or digits, or a `spotify:track:` URI) and saves nothing on a bad id.
    - In `build.gradle` the installed default script is the CLI (`applicationName` `musicmind`, main class `CliApp`). `guiStartScripts` adds `bin/musicmind-gui` (JavaFX on the module path from `lib`). `run` is pinned to `GuiApp`; `runCli` is unchanged.
  - **Reason:** A command-line tool must tell scripts and Gradle when it failed, and a bad track id saved silently corrupts F9 feedback (a live test saved the literal `TRACK_ID`).
  - **Impact:** Additive keys `feedback.trackIdPattern` and `text.cli*`.
  - **Members added:** `CliApp(Supplier<AppController>, BufferedReader, PrintStream, PrintStream, Config)`, `AppController.willOpenBrowser` and a constructor taking a `BooleanSupplier`, `OAuthTokenStore.needsBrowserLogin`, `Config.feedbackTrackIdPattern`, `SpotifyAdapter(OAuthTokenStore, Config)`.
- **Feedback id validated in the controller (Task 12 review)** - Original: the CLI alone checked the track id. Change: `AppController.requireTrackId` checks `feedback.trackIdPattern`, strips spaces and the `spotify:track:` prefix; `recordFeedback` saves the stripped id; the CLI calls it before signing in. Reason: Constraint 4 (no logic in the interfaces) and the GUI could save `TRACK_ID`. Impact: key `text.feedbackBadTrackId` replaces `text.cliBadTrackId`.
- **GUI complete over the shared controller (Task 13)**
  - **Original:** Stage 1 names `ProfileView`, `DiscoveryView` and `PlaylistView` and lists the GUI interactions. The earlier `GuiApp` showed only F1 and built the controller on the JavaFX thread.
  - **Change:**
    - `GuiApp.start()` hosts seven tabs (`ProfileView`, `TrendsView`, `SearchView`, `DiscoveryView`, `PlaylistView`, `PreferencesView`, `HistoryView`) plus `CreatePlaylistDialog` and the F11 conflict dialog. All are in `app/view/` and all call `AppController` only.
    - Supporting classes beyond Stage 1 (R20): `GuiContext` (the one background worker, login state, failure handling, the F11 handler), `StatusLine`, `LoginGate`, `FeedbackBar`, `WhyExpander`, `NegotiationDialog`, `UiParts`, the `TabView` interface, and `ViewFormat`.
    - `ViewFormat` is the plain-Java helper that holds everything worth testing: durations, genre percentages, validation badges such as "2 explicit tracks" and "over target by 6 min", trend columns, history rows, failure wording.
    - `GuiApp.init()` (not `start`) builds the controller, so a slow database connection does not freeze the window.
    - Opening the window calls neither Spotify nor the browser. Only the "Log in to Spotify" button does, and after login only the database is read (stored profile, candidates, preferences, history).
    - Errors from Spotify, the model or the database are shown as fixed friendly wording (`ViewFormat.failure`), because their own messages carry HTTP statuses, addresses and response bodies. Other messages are shown unless they contain an address, an HTTP status or a brace.
    - Banners cover Spotify not configured, no LLM key, no Last.fm key (tags off) and database unavailable. The footer credits Spotify and Last.fm (it credited MusicBrainz before Nov 5).
  - **Reason:** `AppController` keeps mutable `last*()` state, so every controller call from the GUI runs on a single worker thread, and the `last*()` values are read in the same task before the result reaches the JavaFX thread. The JavaFX thread never waits for the network or the database. F11 is asked on the worker through a `CompletableFuture` that the JavaFX thread completes after showing the dialog. Progress (state names) is marshalled to the JavaFX thread.
  - **Impact:**
    - No Stage 1 name or signature changed.
    - Nothing from Spotify is written to disk by the GUI.
  - **`AppController.lastValidationTargetMinutes()` (Task 13)**
    - **Original:** `validatePlaylist` returned a `ValidationResult` whose duration rule lists no figures, so a view could only repeat the validator's English sentence.
    - **Change:**
      - `MusicAgentFacade` remembers the target length the last validation judged by, and `AppController.lastValidationTargetMinutes()` returns it (0: none).
      - `ViewFormat.badges` compares it with the playlist's length to say "over target by 6 min" or "under target by 10 min".
    - **Reason:** Stage 1 GUI Interaction names that badge.
    - **Impact:** Additive accessor; the target is a number the validation already used.
  - **GUI display counts and wording in `musicmind.properties` (Task 13, Constraint 10)**
    - **Original:** The CLI keeps its display counts as constants.
    - **Change:** `gui.genresShown`, `gui.artistsShown`, `gui.tracksShown`, `gui.trendGenresShown` (read through `Config.guiGenresShown/guiArtistsShown/guiTracksShown/guiTrendGenresShown`) and the `text.gui*` wording keys; the views share the CLI's `text.cli*` lines where the meaning is the same (session lines, trend step lines, "Not saved").
    - **Reason:** User-visible wording and counts are tunables.
    - **Impact:** Configuration only.
  - **"Why these?" on recommendations (F10, Task 13)**
    - **Original:** `explain` answers for a stored playlist session only.
    - **Change:** Each recommendation's expander shows its own `reason` and the list's expander shows the run's `DecisionTrace` (strategy and constraints) read from `lastDecisionTrace()`; a playlist's expander calls `explain(playlistId)` the first time it is opened.
    - **Reason:** Stage 1 asks for the expander on each playlist and each recommendation; a discovery run has no stored session to explain.
    - **Impact:** No new component.
  - **GUI supporting classes (Task 13, R20)**
    - **Original:** Stage 1 lists the views and `GuiApp` only.
    - **Change:** `GuiContext` (one background worker, login, the F11 handler), `ControlLock` (counts locks so two queued tasks cannot unlock each other's controls, and skips bound controls), `ViewFormat` (plain text and numbers, tested without JavaFX), plus `CreatePlaylistDialog`, `NegotiationDialog`, `FeedbackBar`, `WhyExpander`, `LoginGate`, `StatusLine`, `TabView` and `UiParts`.
    - **Reason:** `AppController` keeps `last*()` state, so GUI calls must not overlap, and views stay thin.
    - **Impact:** No Stage 1 name changes; tabs refresh through `GuiContext.dataChanged()` after a playlist write.
  - **`musicmind.properties` read as UTF-8 (Task 13)** - Original: `Properties.load(InputStream)` read the file as ISO-8859-1. Change: `Config` loads it through a UTF-8 reader. Reason: live testing showed the thumbs labels garbled. Impact: non-ASCII wording now works; no existing value changed.

- **Frontend redesign: plain names, dark theme, new reads (F1-F12 screens; Nov 4)** — Original: the GUI was seven default-looking JavaFX tabs named Profile, Trends, Search, Discover, Playlists, Preferences and History, with ids, scores and raw numbers on screen (`docs/GUI_TEST_FINDINGS.md`, items 1-24). Change: the same seven screens are a left menu named Your Taste (F1, `ProfileView`), Trends (F2), Find Songs (F3), Discover (F4, `DiscoveryView`), Playlists (F5, F6, F12, `PlaylistView`), Your Rules (F7, F8) and History (F12); each has a title and a one-line description, with a "Try:" line where it takes input. One dark stylesheet, `musicmind.css`, styles every screen and dialog; wording is in `musicmind.properties` under `text.gui*`; the songs show as one shared row (`TrackRow`) with artist names, like and not-for-me icons (F9), "+ Add", "Block" or "Remove". Time ranges read Past month, Past 6 months, Past year. Create takes hours and minutes and offers the songs waiting from Find Songs as a choice; Your Rules uses pickers in place of ids; History is a timeline with saved versions grouped by playlist and a preview before Restore; Playlists gained Delete. New read-only `AppController` methods: `currentUserName()` (the Spotify display name for the header), `feedbackFor()` (track id to the user's latest verdict, so a row shows what was chosen), `artistHint(id)` (an album title and whether the artist is in the user's listening, to tell same-name artists apart; display only), `previewSnapshot(id)` (the songs of a saved version, titled from Spotify, for the Restore preview; changes nothing). `TabView.onShown()` is a new hook the menu calls each time a screen is opened (Discover and Your Rules read their saved values then). Reason: Sarina's test of Nov 2 found the screens hard to understand and the features hidden behind technical wording; the videos show every screen. Impact: the Stage 1 class names and `GuiApp.start()` are unchanged and the F-numbers still map as in `docs/STAGE1_CONFORMANCE.md`; the visible menu names differ from the Stage 1 names for F1, F3 and F7/F8 and the report should say so. Views still call only `AppController` through `GuiContext.run`; no backend behaviour changed, and nothing new is sent to OpenAI. The Nov 4 polish round added an "already waiting" message and an "N waiting" count on Find Songs, a Create button that waits for the waiting songs to load, and readable Open and Delete buttons on a selected playlist row.
- **Playlists list shows our own song count for a new or opened playlist (F5-F8; Nov 4)** — Original: each row showed Spotify's `items.total` from `/me/playlists`. Change: `PlaylistView` remembers the count of the playlists it created or opened (from MusicMind's own read of the tracks) and `ViewFormat.mergeTrackCounts` puts that count on the row; `ViewFormat.countsStillLagging` keeps it only while Spotify's total is below it and drops it once Spotify reports as many or more. Reason: right after Create the list said "Slow Dance 0 songs" for a 7-song playlist and "yoga 0 songs" for 21, because Spotify's total lags just after a change. Impact: display only; an unopened playlist keeps Spotify's total, and a playlist changed outside MusicMind shows Spotify's total as soon as that is at least the remembered count. Past-week profiles are shown on Your Taste only: Discover, Create and the listening hints use the newest Spotify-period profile, and `week` is never sent to Spotify's top-items call, which now refuses unknown ranges.
- **Task 13 GUI entries above are superseded in layout only** - Original: the seven-tab form described above. Change: the Nov 4 redesign entry replaces the tabs with a left menu and a dark theme; `FeedbackBar` was removed in favour of `TrackRow`. Reason: owner's test findings. Impact: the Task 13 entries record how the controller, worker thread and wording keys work and are still accurate for those parts.
- **GUI fixes from the owner's Nov 5 test and the Nov 5 UI/UX review (all screens; Nov 5)** — Original: the redesigned GUI of Nov 4. Change: findings 36-39 and review items 1-12 in `docs/GUI_TEST_FINDINGS.md`: visible keyboard focus on every control; field, check-box and slider borders at 3:1 or more; only messages the app throws on purpose are shown (`ViewFormat.failure`), anything else becomes fixed friendly wording; green kept for the main action and success; Playlists puts the change box and Modify under the title and gives Delete and Block a red style; one spacing scale (`UiParts.SPACE_*`, 4/8/16/24/32); song rows keep one labelled action with Like, Not for me and Spotify as icon buttons with tooltips and accessible text; Create shows plain progress words (`ViewFormat.progress`) and white labels with caption helpers; Trends is one table of genres and artists against the periods (`ViewFormat.trendTable`) with the summary first; result messages sit under each screen's input; the "Try:" example shows only on an empty screen; Discover uses plain rows and one "Why these songs?"; five type sizes with 14px body; the Your Rules Save buttons show only when a value changed; History says "Earlier versions of your playlists". Reason: the owner's test and an accessibility and usability review (WCAG 2.4.7 focus visible, 1.4.11 non-text contrast). Impact: views, `musicmind.css`, `ViewFormat` helpers with tests and wording keys only; no feature or stored data changed apart from the blocked-song titles below.
- **Your Rules: blocked songs shown by title (F7; Nov 5)** — Original: a blocked song was listed as "a song you blocked" because only its id is stored. Change: `AppController.trackLines(ids)` (via `MusicAgentFacade.trackLines`) reads "Title – Artist, Artist" from Spotify with `getTrack`, one call per song on the worker, for the first `gui.blockedTitlesShown` (20) songs; it stops at the first expired login, rate limit or server (5xx) error, as `previewSnapshot` does, so a failing Spotify is not asked 20 times; a song Spotify does not know is skipped, and a song not read (or Spotify not set up) is absent from the result so the view keeps the placeholder. Reason: the owner could not tell which song to unblock. Impact: additive; read for display only and held in memory, never stored or sent to the LLM.

## 8. Design patterns

The five patterns counted for the course are Adapter, Strategy, Command, State and Facade, as in
Stage 1. Repository and Decorator are used as well. Details by class are in
`docs/STAGE1_CONFORMANCE.md`, section "Design patterns".

| Pattern | Where | Differs from Stage 1? |
|---|---|---|
| Adapter | `MusicService` / `SpotifyAdapter`; `GenreService` and `TagService` / `LastFmAdapter`; `LLMProvider` / `OpenAiProvider` | The genre adapter is new (R18); it was `MusicBrainzAdapter` until Nov 5 and is now `LastFmAdapter`. |
| Strategy | `RecommendationStrategy`, `AbstractRecommendationStrategy`, `FamiliarStrategy`, `DiscoveryStrategy`, `BalancedStrategy`, chosen by `StrategySelector` and set with `RecommendationEngine.setStrategy` | `bindProfile` added (R7). |
| Command | `PlaylistCommand` with `CreatePlaylistCommand`, `AddItemsCommand`, `RemoveTrackCommand`, `ReplaceTrackCommand`, `ReorderCommand`, plus `BatchCommand`, `RestoreSnapshotCommand`, `DeletePlaylistCommand`; invoker `PlaylistManager` | Three commands added; undo uses snapshots across runs (R10). |
| State | `SessionState` with `IdleState`, `PlanningState`, `RetrievingState`, `SelectingState`, `ValidatingState`, `ExecutingState`, `DoneState`, `FailedState`; context `AgentSession.run()` | Phase logic lives in the states; `RunContext` and `RunServices` hold the run. |
| Facade | `MusicAgentFacade` behind `AppController` | `AppController` adds the `last*()` accessors the interfaces need. |
| Repository | `Repository<T>` with `PreferenceRepository`, `FeedbackRepository`, `SessionRepository`, `PlaylistHistoryRepository`, `CandidateRepository`; `GenreRepository` is a cache | `CandidateRepository` and `GenreRepository` are new. |
| Decorator | `RetryingMusicService`, `GenreEnrichingMusicService` over `MusicService` | `GenreEnrichingMusicService` is new (R18). |

## 9. Weekly commit summary

Dates are Toronto time. Links use the public repository
`https://github.com/sarinakavoli/EECS3311-MusicMind`.

- **Period 1 (Oct 20-26):** no code commits. Stage 1 was committed on Oct 9.
- **Period 2 (Oct 27-Nov 2):** 147 code commits. The first five, all on Oct 30:
  [`3d6644e`](https://github.com/sarinakavoli/EECS3311-MusicMind/commit/3d6644e) Gradle and JavaFX build,
  [`cb0c8ee`](https://github.com/sarinakavoli/EECS3311-MusicMind/commit/cb0c8ee) domain model,
  [`e1bb45f`](https://github.com/sarinakavoli/EECS3311-MusicMind/commit/e1bb45f) core interfaces,
  [`f8f6283`](https://github.com/sarinakavoli/EECS3311-MusicMind/commit/f8f6283) `PlaylistValidator` with tests,
  [`515e8d9`](https://github.com/sarinakavoli/EECS3311-MusicMind/commit/515e8d9) Strategy classes.
  Later work (the adapter, F1 to F12, the GUI and the backend fixes) is in the same range;
  `git log --since=2026-10-27 --until=2026-11-03 -- src build.gradle` lists all of it.
- **Period 3 (Nov 3-9):** 51 code commits by Nov 5 (the redesign, Past week, goal fit and Last.fm
  tags); the list is `git log --since=2026-11-03 -- src build.gradle`.

## 10. Open items

- Retake the report screenshots of the redesigned GUI after the owner's test, and record the videos.
- Live proof that the goal review improves playlists; `delete` has not been run live.
- `stage2-report`, updated UML, and the demonstration videos.
- Search by description ("mellow alternative for studying") is still weak, because Spotify's
  text search matches title words and this app has no mood data.
- The genre signal depends on Last.fm; some artists have no tags there (and with no `LASTFM_API_KEY` none do), so trends and discovery report partial coverage.
