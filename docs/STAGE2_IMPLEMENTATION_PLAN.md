# MusicMind Stage 2 — Implementation Plan (exact Stage 1 conformance)

**Spec (binding authority):** `docs/MusicMind_Stage1_Report.docx`, `docs/diagrams/uxf/*.uxf`, and the Stage 2 instructions (`Project-Stage2-Instruction.pdf`).
Plain-text extracts for implementers live beside the task briefs in `spec/` (paths given in each dispatch).

**Goal:** implement all twelve features (F1–F12) so that classes, method names, signatures, flows,
patterns, commands and GUI screens match Stage 1. Where this plan and the report disagree, the report wins,
except for the rulings in "Resolved Stage 1 conflicts" below.

**Branch:** `phase1-spine-f1` (open PR #1). Never commit to `main` directly. Merge at the end with a merge commit (never squash) so every commit keeps its real date for the weekly summary.

## Daily schedule

Dates are Toronto time. Each day ends with real commits pushed to GitHub, and a test note for Sarina in `docs/DAILY_TEST_NOTES.md`.

| Day | Work | Output Sarina checks |
|---|---|---|
| Fri Oct 30 | Tasks 1-5: PostgreSQL, Spotify API fix, OpenAI, tools, LLM parsing | `./gradlew build` passes |
| Sat Oct 31 | Tasks 6-8: F1, F2, F3, F4, F8, F9 | `profile`, `trends`, `search`, `discover`, `prefs`, `feedback` |
| Sun Nov 1 | Tasks 9-12: F5, F6, F7, F10, F11, F12, full CLI | `create`, `modify`, `undo`, `validate`, `explain`, `history`, `restore` |
| Mon Nov 2 | Tasks 13-17: GUI, docs, UML, report, final review, merge | All GUI tabs, README setup, report PDF |
| Nov 3-9 | Buffer: fixes from Sarina's testing (real code commits only), video links added to the report | Videos recorded |
| Tue Nov 10 | Final submission check | Repository URL submitted |

## Skills used

`subagent-driven-development` (all tasks), `test-driven-development` (implementers), `systematic-debugging` (any failure), `verification-before-completion` (before any done claim), `requesting-code-review` (final review), `security-review` (before merge), `run` (launching the app), `pdf` (report), `finishing-a-development-branch` (merge).

## Global Constraints

1. **Stack:** Java 17 toolchain, Gradle 9.1 wrapper, JavaFX 21.0.13, PostgreSQL via JDBC, Gson, dotenv-java. **PostgreSQL replaces SQLite: no SQLite code or dependency remains.**
2. **LLM:** only `OpenAiProvider` (the app) and `MockLLMProvider` (tests only, R17) implement `LLMProvider.complete(prompt)`. Only `Planner` and `ExplanationService` plus the narrating steps named by F1, F2, F3, F6, F11 call the LLM. The LLM never selects tracks and never receives bulk catalogue data.
3. **Names are a contract.** Use these exact class names and members.
   - `AppController`: `analyzeTaste(user): ListeningProfile`, `analyzeTrends(user): TrendReport`, `searchMusic(query): List<Track>`, `discover(spec): List<Recommendation>`, `createPlaylist(request): Playlist`, `modifyPlaylist(id, instruction): Playlist`, `validatePlaylist(id): ValidationResult`, `setPreference(pref)`, `recordFeedback(trackId, verdict)`, `explain(sessionId): String`, `getHistory(user): List<AgentSession>`, `restore(snapshotId): Playlist`, `undo(playlistId)`.
   - `MusicAgentFacade`: `analyzeTaste(user)`, `searchMusic(query)`, `discover(profile, spec)`, `createPlaylist(request)`, `modifyPlaylist(playlistId, instruction)`, `validate(playlist, spec): ValidationResult`, `explain(session): String`, plus `analyzeTrends(user)` (required by F2).
   - `RecommendationEngine`: field `strategy`; `setStrategy(s)`, `recommend(profile, spec, feedback): List<Recommendation>`, `score(track, spec): double`.
   - `Planner`: `plan(request): AgentPlan`, `parseGoal(text): PlaylistSpec`.
   - `ConstraintChecker`: `checkFeasibility(spec, pool): FeasibilityReport`.
   - `ProfileBuilder`: `build(topItems, saved): ListeningProfile`, `computeGenreHistogram(artists): Map`.
   - `PlaylistValidator`: `validate(tracks, spec): ValidationResult`. `ExplanationService`: `explain(session): String`.
   - `ToolManager`: `register(tool)`, `execute(toolName, args): ToolResult`.
   - `MemoryManager`: `loadPreferences(user): List<UserPreference>`, `savePreference(p)`, `loadFeedback(user): List<Feedback>`, `saveFeedback(f)`, `saveSession(s)`, `loadHistory(user): List<AgentSession>`, `saveSnapshot(playlist): int`, `loadSnapshot(id): Playlist`.
   - `RecommendationStrategy.selectCandidates(pools, spec): List<Track>`; `AbstractRecommendationStrategy`: `#scoreFamiliarity(track, profile): double`, `#excludeKnown(candidates, profile): List<Track>`; `FamiliarStrategy`, `DiscoveryStrategy`, `BalancedStrategy`.
   - `PromptBuilder`: `buildPlanPrompt(request): String`, `buildExplainPrompt(trace): String`. `ResponseParser`: `parsePlaylistSpec(json): PlaylistSpec`, `parseCommands(json): List<PlaylistCommand>`, `verifyTracks(tracks, results): List<Track>`.
   - `PlaylistManager`: field `history: Deque<PlaylistCommand>`; `execute(command)`, `executeAll(commands)`, `undo(playlistId)`.
   - `PlaylistCommand`: `execute()`, `undo()`; `CreatePlaylistCommand`, `AddItemsCommand`, `RemoveTrackCommand`, `ReplaceTrackCommand`, `ReorderCommand`.
   - `Tool`: `name()`, `execute(args)`; `SpotifyTool`, `MemoryTool`.
   - `SpotifyAdapter`: fields `http: HttpClient`, `auth: OAuthTokenStore`. `RetryingMusicService`: field `delegate: MusicService`, method `withBackoff(call)`.
   - `Repository<T>`: `save(entity)`, `findById(id): T`, `findByUser(userId): List<T>`, `delete(id)`; `PreferenceRepository`, `FeedbackRepository`, `SessionRepository`, `PlaylistHistoryRepository`.
   - `SessionState`: `handle(session)`, `name()`; `IdleState`, `PlanningState`, `RetrievingState`, `SelectingState`, `ValidatingState`, `ExecutingState`, `DoneState`, `FailedState`. `AgentSession`: `setState(state)`, `getState()`.
   - Views: `ProfileView`, `DiscoveryView`, `PlaylistView` (plus views for Trends, Search, Preferences, History).
   - `GuiApp.start()`, `CliApp.run(args)`.
4. **`AppController` is shared.** GUI and CLI contain no business logic. `AppController` calls `MusicAgentFacade`; F8, F9 and F12 reads/writes go to `MemoryManager` as Stage 1 describes.
5. **Spotify terms:** never durably store Spotify content (titles, artist names, album names). Store only identifiers (track ids/URIs, artist ids, playlist ids) and MusicMind's own derived data (genre histogram, reasons, scores, summaries). Attribute content to Spotify in both UIs. Do not train on Spotify data.
6. **Spotify API (Feb 2026 Development Mode):** create with `POST /me/playlists`; `GET/POST/PUT/DELETE /playlists/{id}/items`; DELETE body key is `items`; a playlist entry's track object is under `item` (accept legacy `track` as fallback); `GET /me`; search `limit` ≤ 10, paginate with `offset`; do not use `/recommendations`, audio features, related artists, popularity, batch `GET /artists`, `/artists/{id}/top-tracks`, `/users/{id}`. Auth is PKCE with redirect `http://127.0.0.1:8080/callback`; no client secret.
7. **Secrets:** never commit `.env`, tokens or keys. Do not read or print `.env` values.
8. **Real code:** no stubs, TODOs or empty methods. Every error/alternative case in the Stage 1 feature text must be handled.
9. **Tests:** JUnit 5, deterministic, no network and no real keys. Spotify HTTP is tested against a local `com.sun.net.httpserver.HttpServer`; PostgreSQL against embedded binaries. `./gradlew build` must pass with no database server and no keys.
10. **No hard-coding (Stage 2: hard-coded demonstration responses do not count as functionality).** No canned results, sample playlists or fake data in `src/main`. Every result comes from Spotify, the LLM, the database or a real computation. Tunables (retry counts, tolerances, score weights, page sizes, track-length estimates) live in `src/main/resources/musicmind.properties` and are read through `Config`. Curated data (mood→genre map) lives in `src/main/resources/mood-genres.json`. Model names and URLs come from configuration. No key, id or user-specific value in source. Deterministic fallbacks are allowed only where Stage 1 documents one, must be computed from real data, and must be labelled in the output.
11. **Commits:** small and descriptive, each ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Implementers commit but do not push. Never rewrite history or fake dates.

## Resolved Stage 1 conflicts (rulings)

Stage 1 is internally inconsistent or silent in these places. These decisions bind all tasks; each additive member is recorded in `docs/STAGE2_PLAN.md`.

| # | Stage 1 issue | Ruling |
|---|---|---|
| R1 | `getTopItems(type, range): List<Track>` cannot return artists' genres, yet SD03 calls `getTopItems("artists", range)` for "top artists (genres)". | Keep `getTopItems` for tracks and keep the additive `getTopArtists(range): List<Artist>`. Tool name `getTopItems` with arg `type=artists` routes to it. |
| R2 | `build(topItems, saved)` has no stated type for `topItems`. | New value class `domain/TopItems(List<Artist> artists, List<Track> tracks)`. |
| R3 | `Repository<T>.save(entity)` and `MemoryManager.savePreference(p)` take no user, but the entities have no owner. | Repositories take a `Supplier<String>` current-user-id in their constructor and stamp it on save. `findByUser(userId)` filters explicitly. Ids are `String`. |
| R4 | "ListeningProfile is persisted" but MemoryManager only has `saveSession`, and Spotify terms forbid durable content. | Persist through `saveSession`: the session row holds derived data (histogram, ids, summary, generatedAt) as JSON. No separate profile repository. Delete `SqliteProfileRepository`. |
| R5 | `profile [--refresh]` meaning. | `profile` shows the latest stored derived profile (histogram, summary, date) if one exists, else builds; `--refresh` always rebuilds. `AppController.analyzeTaste(user)` always rebuilds; add `lastProfile(user)`. |
| R6 | F3 lists `parseGoal()`, which returns `PlaylistSpec`, not `SearchQuery`. | Add `Planner.parseSearchQuery(text): SearchQuery`. `parseGoal` stays the plan parser. |
| R7 | `selectCandidates(pools, spec)` has no profile, but helpers take `profile`. | `AbstractRecommendationStrategy.bindProfile(profile)` is called by `RecommendationEngine.recommend()` first; concrete strategies use the protected helpers. |
| R8 | `createPlaylist(request)` must carry flags (`--minutes`, etc.) but `AgentRequest` has only `rawText`, `kind`. | Add optional `PlaylistSpec constraints` to `AgentRequest`. Explicit values override LLM-parsed ones. |
| R9 | `createPlaylist` returns `Playlist`, yet shows an explanation and reports progress; F11 needs a user choice mid-run. | `AppController.lastSession()` exposes the finished `AgentSession` (id, explanation). `AppController.setNegotiationHandler(NegotiationHandler)` supplies the choice (interactive CLI prompt / GUI dialog); `setProgressListener(Consumer<String>)` publishes `SessionState.name()`. |
| R10 | Undo across CLI processes: `history` Deque is in-memory. | `PlaylistManager` saves a snapshot before each modify. `undo(playlistId)` pops the stack; if empty, it restores that playlist's latest snapshot. |
| R11 | `CreatePlaylistCommand.undo()` has no receiver method. | Additive `MusicService.unfollowPlaylist(playlistId)` using `DELETE /me/library?uris=spotify:playlist:{id}`; add scope `user-library-modify`. |
| R12 | `addItems` has no position, but Remove undo must restore order. | Undo re-adds then calls `reorderItems` to the original index. |
| R13 | `explain(sessionId)` vs CLI `explain <playlistId>`. | `MemoryManager.findSessionByPlaylist(playlistId)` resolves CLI input; `AppController.explain` accepts either id. |
| R14 | `MemoryManager.saveSnapshot(playlist)` and F12 store a Playlist, but content cannot be stored. | Snapshot stores playlist id, name and ordered track URIs/ids. `loadSnapshot` returns a `Playlist` with id-only `Track`s (blank title). |
| R15 | `musicmind` executable name. | `installDist` produces `bin/musicmind` (CLI) and `bin/musicmind-gui`. `./gradlew run` and `runCli` remain. |
| R16 | Authentication (UC01) has no class method. | `AppController.login(): User` and `currentUser()`. |
| R17 | Stage 1 lists `MockLLMProvider`; Stage 2 rejects hard-coded responses. | `MockLLMProvider` stays as a test double and is never created by `AppController`. With no `LLM_API_KEY`, features needing the LLM say so; features with a Stage 1 fallback (F1, F2 wording, F3 keyword search) use it and label it. |

## File map

```
config/Config
domain/   + TopItems, TrendReport, SearchQuery, FeasibilityReport, DecisionTrace
llm/      OpenAiProvider, MockLLMProvider, LLMProvider, LLMException      (delete GeminiLLMProvider)
music/    MusicService, SpotifyAdapter, OAuthTokenStore (rename SpotifyAuth), RetryingMusicService, exceptions
memory/   Database, MemoryManager, PersistenceException
memory/repository/ Repository, PreferenceRepository, FeedbackRepository, SessionRepository, PlaylistHistoryRepository
tool/     Tool, ToolResult, ToolManager, SpotifyTool, MemoryTool
agent/    Planner, PromptBuilder, ResponseParser, ProfileBuilder, TrendComparator, SearchRanker, MoodGenreMap,
          RecommendationEngine, ConstraintChecker, PlaylistValidator, ExplanationService, NegotiationHandler
agent/strategy/  agent/command/ (+ PlaylistManager)  agent/state/
controller/ AppController, MusicAgentFacade                                 (delete TasteProfileResult; delete agent/ProfileSummarizer)
app/GuiApp, app/view/*  cli/CliApp
```

---

### Task 1: PostgreSQL persistence and exact repositories

Replace SQLite with PostgreSQL and restore the Stage 1 `Repository<T>`.

- `build.gradle`: remove `sqlite-jdbc` and `slf4j-nop` if nothing needs it; add `org.postgresql:postgresql:42.7.14`. Test deps: `io.zonky.test:embedded-postgres:2.2.2` plus the binaries needed for Linux, Windows and macOS on amd64 and arm64 (use its binaries BOM).
- `Config`: add `databaseUrl()`, `databaseUser()`, `databasePassword()` from `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`; remove `databasePath`. `.env.example`: restore the three `DATABASE_*` lines with defaults `jdbc:postgresql://localhost:5432/musicmind`, `musicmind`, `changeme`.
- `Database(url, user, password)`: JDBC connection, `init()` creating `user_preference`, `feedback`, `agent_session`, `playlist_snapshot`, `isAvailable()`.
- `Repository<T>` with exactly `save(T)`, `T findById(String)`, `List<T> findByUser(String)`, `delete(String)` (R3).
- Implement `PreferenceRepository`, `FeedbackRepository` (upsert by user+track), `SessionRepository`, `PlaylistHistoryRepository` (R14). Delete `SqliteProfileRepository`, `SqlitePreferenceRepository`.
- `SessionRepository` persists `AgentSession`: id, startedAt, status, request (rawText, kind), plan steps, playlistId, and a derived-data JSON column (R4). Extend `AgentSession` with those fields, including a `DecisionTrace` placeholder type added in this task: `domain/DecisionTrace` holding strategy name, per-track id → reason, constraints satisfied.
- `MemoryManager` exact API from Global Constraint 3, plus `findSessionByPlaylist(playlistId)`. F8 alternative: if the database is unavailable, `loadPreferences` returns safe defaults, `savePreference`/`saveFeedback` warn, and the app keeps running. Add `boolean isAvailable()` and a warning message accessor. Validate preference values (`familiarBias` 0..1, non-blank key).
- Update `AppController` so the project still compiles and F1 still functions.
- Delete the local reliance on `musicmind.db`.

Tests: repositories and MemoryManager round-trips on embedded PostgreSQL, feedback upsert, per-user isolation, unavailable-database defaults, and a shared test base that starts one embedded server per JVM.

### Task 2: Spotify adapter conforming to Stage 1 and the Feb 2026 API

- `.gitignore`: use `.env`, `.env.*`, `!.env.example`, `config.local.json`.
- Rename `SpotifyAuth` → `OAuthTokenStore`. `SpotifyAdapter` fields are `http: HttpClient` and `auth: OAuthTokenStore`. Add `user-library-modify` scope.
- Fix endpoints per Global Constraint 6: `POST /me/playlists`; all `/playlists/{id}/items`; DELETE body key `items`; entries read `item` with fallback `track`. Skip null entries and local tracks with no id.
- `search(query, limit)`: page size ≤ 10, using `offset` to satisfy `limit`.
- `getTopItems(type, range)`: tracks; R1 for artists.
- Add `unfollowPlaylist` (R11) in `MusicService`, adapter and decorator.
- `*a` token expiry: on HTTP 401, force token refresh and retry once; if that fails raise `AuthExpiredException` (extends `MusicServiceException`), with a message telling the user to log in. Add `OAuthTokenStore.login()` that forces a full browser flow.
- Page results for `getSavedTracks`, `getPlaylistItems` until the response has no `next`, bounded at 500 items. Reduced-limit responses must not crash.
- Constructor seam for API base URL and token supplier so tests use a local server.
- `RetryingMusicService`: `withBackoff(call)` retries 429 and 5xx, honouring `Retry-After`; `delegate` field; never retries 4xx other than 429; surfaces a clear message on persistent failure.

Tests: local `HttpServer` assertions on method, path, body and pagination for every `MusicService` method; 401 refresh; 429 and 5xx retry.

### Task 3: OpenAiProvider

- Add `OpenAiProvider(apiKey, model)`: `POST https://api.openai.com/v1/chat/completions` with `{model, messages:[{role:"user",content}]}`; return `choices[0].message.content`; send the key only in the `Authorization: Bearer` header; 30s timeout; `LLMException` on non-2xx or bad shape, never including the key. Base URL seam for tests.
- Delete `GeminiLLMProvider`. `Config.llmModel()` default `gpt-4o`, ignoring placeholders `your_model_name_here` and `gemini-*`.
- `.env.example`: OpenAI key URL and default model. `AppController` uses `OpenAiProvider` when configured, otherwise no provider (R17).
- Add `src/main/resources/musicmind.properties` and `Config` accessors (Global Constraint 10); `Config.llmModel()` default also comes from there. Move the existing constants (`PlaylistValidator` tolerance, strategy track length, retry limits) into it.
- Move `MockLLMProvider` to `src/test/java` (same package) and extend it to return valid deterministic JSON or text for plan, search, edit, explain, summary, trends and options prompts.

Tests: local server for request shape and error handling.

### Task 4: Domain additions, ToolManager, SpotifyTool, MemoryTool

- Domain: `TopItems`, `TrendReport` (per-range artists/genres, added/dropped, gap notices, narrative), `SearchQuery` (keywords, genre, filters, confidence, `toSpotifyQuery()`), `FeasibilityReport` (feasible, conflicts, relaxation options). `ListeningProfile` adds saved-track ids, `summary`, `isPartial()`, familiarity baseline (known artist ids and known track ids). `PlaylistSpec` adds `novelty` semantics through `familiarRatio` only, plus `setBlockedArtists`. `Playlist` keeps `totalDuration()`. `AgentRequest` gains `constraints` (R8).
- `ToolManager.register(tool)` / `execute(toolName, args)` returning `ToolResult`; an unknown tool returns failure rather than throwing; tool exceptions become `ToolResult.failure` except that auth expiry propagates.
- `SpotifyTool`: one instance per operation name. `SpotifyTool.allFor(music)` returns tools named `getTopItems`, `getSavedTracks`, `getRecentlyPlayed`, `search`, `getArtistAlbums`, `getAlbumTracks`, `getPlaylistItems`.
- `MemoryTool`: name `memory`, args `action` = `loadPreferences` | `loadFeedback`, `user`.

Tests: routing, unknown tool, arg handling, failures.

### Task 5: PromptBuilder and ResponseParser

- `PromptBuilder`: `buildPlanPrompt(request)`, `buildExplainPrompt(trace)`, plus `buildSummaryPrompt(profile)`, `buildTrendPrompt(report)`, `buildSearchPrompt(text)`, `buildEditPrompt(instruction, trackRefs)`, `buildOptionsPrompt(report)`. Prompts carry only ids/genre names/numbers and the small facts required; never a bulk catalogue.
- `ResponseParser`: `parsePlaylistSpec(json)`, `parseCommands(json)`, `verifyTracks(tracks, results)` (keeps only tracks confirmed by real Spotify results), `parseSearchQuery(json)`, and `verifyExplanation(text, trace)` (rejects output naming ids or tracks absent from the trace).
- Tolerates fenced JSON; malformed JSON raises `LLMException`-derived `ParseException` so callers use their documented fallback. `parseCommands` needs a `PlaylistContext` (playlist id, current tracks, `MusicService`) to build real commands; because commands arrive in Task 9, this task defines `parseCommandSpecs(json): List<CommandSpec>` and Task 9 adds `parseCommands`.

Tests: valid, fenced, malformed, range clamping (`familiarRatio` 0..1, positive minutes), hallucinated-track rejection.

### Task 6: F1 and F2 aligned to Stage 1

- `ProfileBuilder.build(TopItems, List<Track> saved)` and `computeGenreHistogram(artists)` (rename `aggregateGenres`); makes partial profiles for little history.
- `MusicAgentFacade.analyzeTaste(user)` per SD03: `ToolManager.execute` for top artists, top tracks, saved tracks → `build` → `LLMProvider.complete(summaryPrompt)` with deterministic fallback → `MemoryManager.saveSession(session)`.
- `AppController.analyzeTaste(user)`, `lastProfile(user)`, `login()`, `currentUser()` (R5, R16). Delete `ProfileSummarizer` and `TasteProfileResult`; move the summary fallback to the facade.
- F2: `TrendComparator` computes per-range set differences; `analyzeTrends(user)` fetches the three ranges through tools, LLM narrates, deterministic narrative fallback; marks empty ranges and compares populated ones.
- Keep CLI and GUI compiling with minimal changes; the full surfaces arrive in Tasks 12–13.

Tests: SD03 interaction order with fakes, partial profile, derived-only persistence, trend diffs, gap handling.

### Task 7: F3 natural-language search

- `Planner.parseSearchQuery(text)` (R6): LLM → `SearchQuery`. Low confidence or malformed → raw keyword search on the original text.
- `MusicAgentFacade.searchMusic(query)` / `AppController.searchMusic(query)`: tool `search` paginated with limit ≤ 10 per page, `ResponseParser.verifyTracks`, `SearchRanker` (deterministic), no-results → `suggestRelaxedQuery` message available via `lastSearchNotice()`.
- `Planner` is created here with its LLM/PromptBuilder/ResponseParser dependencies; `plan` and `parseGoal` arrive in Task 10.

Tests: parse fallback, ranking determinism, no-results notice.

### Task 8: F4, F8, F9 recommendation engine

- Strategies: change helpers to `scoreFamiliarity(track, profile)` and `excludeKnown(candidates, profile)`; add `bindProfile` (R7).
- `MoodGenreMap`: curated mood→genre mapping replacing audio features, loaded from `src/main/resources/mood-genres.json`.
- `RecommendationEngine`: `setStrategy`, `recommend(profile, spec, feedback)`, `score(track, spec)`. Pools via `ToolManager`: top/saved/recently played (familiar); genre-adjacent `search` and artist albums → album tracks (novel). Excludes the library for novel tracks, filters blocked artists, penalises rejected and boosts accepted tracks, records a reason in each `Recommendation` and in a `DecisionTrace`. Deterministic ordering.
- `MusicAgentFacade.discover(profile, spec)` per SD04 selecting strategy from `familiarRatio`; `AppController.discover(spec)` builds the profile on demand. Too few candidates → widen genres / lower novelty and notify. All filtered → report and ask to adjust. Results are not persisted.
- `AppController.setPreference(pref)`, `recordFeedback(trackId, verdict)`, `getPreferences()`. Preferences (blocked artists, explicit policy, familiar bias) apply to F4.

Tests: each strategy, feedback effect on score, blocklist, exclusion, widening, all-filtered, determinism.

### Task 9: Command pattern and PlaylistManager

- `CreatePlaylistCommand`, `AddItemsCommand`, `RemoveTrackCommand`, `ReplaceTrackCommand`, `ReorderCommand` with working `execute`/`undo` through `MusicService` (R11, R12).
- `PlaylistManager`: `history: Deque<PlaylistCommand>`, `execute`, `executeAll` (on failure undoes commands already applied in that batch, then rethrows), `undo(playlistId)` (R10).
- `ResponseParser.parseCommands(json)` over Task 5 `CommandSpec`s.

Tests: fake `MusicService` recording calls, execute/undo for each command, batch rollback, undo ordering, undo from snapshot.

### Task 10: F5, F11 planning, state lifecycle and create flow

- `Planner.plan(request)`, `parseGoal(text)`. Merge `constraints` (R8) and stored preferences. Low-confidence or malformed parse → raise `ClarificationNeededException`.
- `ConstraintChecker.checkFeasibility(spec, pool)`: contradictory ratios, eligible minutes against target, explicit limits. Produces relaxation options.
- `NegotiationHandler.choose(report, phrasedOptions): Optional<Relaxation>`; empty = cancel, aborting with no changes. No viable relaxation → explain and stop.
- States per Stage 1 lifecycle; each `handle(session)` does its phase and sets the next state. `FailedState` centralises error handling. `ValidatingState` loops back to `SelectingState` up to 3 retries. `AgentSession` owns the run context and exposes `run()`.
- Order per UC06: plan → load preferences/feedback → retrieve pools → feasibility → recommend with `BalancedStrategy` → validate → `executeAll([CreatePlaylistCommand, AddItemsCommand])` → `saveSession` → explanation.
- `AppController.createPlaylist(request)`, `lastSession()`, `setNegotiationHandler`, `setProgressListener` (R9). Insufficient tracks → shortfall notice and negotiation. Token expiry → re-auth and resume once.

Tests: state transition order, validation retry bound, infeasible → relax → regenerate, cancel with zero Spotify writes, failure path, session persistence. Uses fake music, mock LLM, embedded DB.

### Task 11: F6, F7, F10, F12

- F6 `modifyPlaylist(playlistId, instruction)` per SD02: snapshot; LLM → `parseCommands`; `executeAll`; re-validate; change summary via `lastChangeSummary()`. Heuristic mood edits flagged approximate. Ambiguous → conservative interpretation. Invalid playlist or permission → clear error. `AppController.undo(playlistId)`.
- F7 `validatePlaylist(id)`: loads tracks; spec from the playlist's session, else preferences. Add `minLength`/`maxLength` rule to `PlaylistValidator` so all ordered rules from F7 exist; `ValidationResult` lists offending items.
- F10 `ExplanationService.explain(session)` per Stage 1 with `buildExplainPrompt`, `verifyExplanation`; missing trace → generic explanation noting the limitation. Persist the trace.
- F12 `AppController.getHistory(user)`, `restore(snapshotId)`: replays snapshot with `PlaylistManager` commands. Deleted playlist → offer recreate (`RestoreOutcome`-free: raise `PlaylistMissingException`, then `recreateFromSnapshot(snapshotId)`).

Tests: all of the above alternatives.

### Task 12: CLI complete

`CliApp.run(args)` with `main` delegating. Commands exactly: `login`, `help`, `exit`, `profile [--refresh] [--range short|medium|long]`, `trends`, `search "<query>"`, `discover [--novelty 0.3]`, `create "<goal>" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name "..."]`, `modify <playlistId> "<instruction>"`, `undo <playlistId>`, `validate <playlistId>`, `prefs get|set|list`, `feedback <trackId> accept|reject`, `explain <playlistId>`, `history`, `restore <snapshotId>`. Interactive F11 prompt. Friendly errors, usage messages, and `runCli` stdin. Spotify attribution line. Implement R15 in `build.gradle`.

Tests: argument parsing and dispatch with a fake controller seam. Add a small `AppController` interface only if unavoidable; otherwise constructor injection of facade fakes.

### Task 13: GUI complete

JavaFX views over `AppController` only, using background workers: login; **Profile** tab with genre breakdown, top artists/tracks, "Refresh profile"; **Trends**; **Search** with "add to candidates"; **Discover** with accept/reject; **Create Playlist** dialog with goal, target duration, explicit toggle, familiar-vs-new ratio, name, and progress from state names; **Playlists** view with "Modify" box, "Undo", validation badges, "Why these?" expander, and thumbs up/down on tracks; **Preferences** screen; **History** list with plan/result and "Restore". F11 conflict dialog. Spotify attribution. Not-configured and database-unavailable banners. Keep view logic thin.

Tests: formatting helpers. Launch `./gradlew run` for 15s and confirm no crash, reported in the task report.

### Task 14: Documentation and conformance

- `docs/STAGE2_PLAN.md`: rewrite changes as Original design → Implemented change → Reason → Impact. Remove the SQLite and Gemini entries; add R1-R17 additive changes and the Gradle/JavaFX changes; update phases.
- `README.md` per the Stage 2 checklist: prerequisites, dependency installation, PostgreSQL setup (`CREATE ROLE`/`CREATE DATABASE`), copying `.env.example` and entering keys locally, GUI and CLI launch commands, example usage. Update `CLAUDE.md`, `src/README.md`, `.env.example`. Follow the README from scratch to prove it works.
- `docs/AI_COLLABORATION_LOG.md`: tool, model, tasks, and token/money cost rows (blank cost cells clearly marked for Sarina), plus representative interactions.
- `docs/STAGE1_CONFORMANCE.md`: every F1-F12 row from the Stage 1 traceability table → actual classes, methods, tests, CLI and GUI entry, status.
- Add safe sample data if any feature needs it to reproduce. Remove remaining SQLite or Gemini references with a repo-wide grep.

### Task 15: Updated UML

Update `docs/diagrams/uxf/MusicMind_ClassArchitecture.uxf` and `MusicMind_Domain.uxf` to match the implemented system (R1-R17 additions). Update use-case and sequence diagrams wherever the implementation made them inaccurate (at least SD01 for negotiation and session). Keep Stage 1 PNGs under `docs/diagrams/png/stage1/` and export updated PNGs.

### Task 16: Stage 2 report

Create `docs/report/stage2-report.pdf` (generated from a tracked source in `docs/report/`) with the seven required sections, describing only what was actually verified:
1. Project overview and implementation status, with honest limitations.
2. Feature to implementation mapping: every field in the Stage 2 table for F1-F12, how classes collaborate from input to result, and deterministic/AI/hybrid.
3. Five implemented patterns (Adapter, Strategy, Command, State, Facade) plus MVC, Repository, Decorator: roles, files, methods, Stage 1 match; updated UML.
4. Changes and rationale in Original design → Implemented change → Reason → Impact form, including the items 1-10 the instructions require.
5. AI tools and collaboration log.
6. Development and verification summary: weekly commit summary with commit links per period, checks, defects found and corrected, limitations (including the missed Period 1).
7. Demonstration index with one row per feature; video link cells are placeholders for Sarina.

### Task 17: Final verification and merge

Whole-branch review against Stage 1 and Stage 2, secret scan of all history, `security-review`, full build from a clean Gradle home, `./gradlew run` launch, README walk-through, then merge PR #1 with a merge commit after Sarina approves.
