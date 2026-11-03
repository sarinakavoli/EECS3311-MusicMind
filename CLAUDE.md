# CLAUDE.md — MusicMind (EECS3311 Stage 2)

Project context for Claude Code. The binding plan is `docs/STAGE2_IMPLEMENTATION_PLAN.md`;
changes are recorded in `docs/STAGE2_PLAN.md`; the Stage 1 feature to code mapping is in
`docs/STAGE1_CONFORMANCE.md`.

## What this is
MusicMind — an AI music-discovery & playlist-management agent that integrates the
Spotify Web API. EECS3311 Software Design course project (individual). Stage 1
(design) is complete and in `docs/`; **Stage 2 is implementation**, due **Nov 10, 2026**.

## Stack
- **Java 17**, **JavaFX** (GUI), a **CLI**, **PostgreSQL** (JDBC) for persistence, and one
  **LLM** (`OpenAiProvider`) behind the `LLMProvider` interface. Build tool: **Gradle 9.1** (wrapper committed).
- `./gradlew` runs on JDK 17–25; a Java 17 toolchain is downloaded automatically if missing.
- Spotify returns no artist genres to this app, so genres come from MusicBrainz through
  `GenreEnrichingMusicService` (R18 in `docs/STAGE2_IMPLEMENTATION_PLAN.md`).

## Commands
```bash
./gradlew build                      # compile and run all tests (no keys or database server needed)
./gradlew test                       # run unit tests
./gradlew run                        # launch the GUI
./gradlew runCli --args="help"       # launch the CLI
./gradlew installDist                # build/install/musicmind/bin/musicmind and musicmind-gui
```
Tests need no keys: Spotify is a local `HttpServer`, PostgreSQL is an embedded binary, the LLM is
`MockLLMProvider` (under `src/test`). Do not read or print `.env` or `.spotify-tokens.json`.

## Package map (`src/main/java/com/musicmind/`)
- `domain/` — entities (Track, Playlist, PlaylistSpec, ListeningProfile, AgentSession, …)
- `music/` — `MusicService`, `SpotifyAdapter`, `OAuthTokenStore`, `RetryingMusicService`,
  `GenreEnrichingMusicService`, `MusicBrainzAdapter`
- `llm/` — `LLMProvider`, `OpenAiProvider` (`MockLLMProvider` is test-only)
- `agent/` — planning, recommendation, parsing and validation classes
- `agent/strategy/`, `agent/command/`, `agent/state/` — Strategy, Command and State patterns
- `memory/` — `MemoryManager`, `Database`, repositories
- `tool/` — `Tool`, `ToolManager`, `SpotifyTool`, `MemoryTool`
- `controller/` — `AppController`, `MusicAgentFacade`; `app/` GUI (`app/view/` tabs); `cli/` CLI
- `config/Config` reads `src/main/resources/musicmind.properties` (tunables and all user-facing
  wording) and `mood-genres.json`. No canned results, keys or user ids belong in `src/main`.
- The GUI and CLI hold no business logic; both call `AppController`.

## Design patterns (must stay evident in code)
Adapter · Strategy · Command · State · Facade (+ Repository, Decorator).

## Conventions — important
- **Never commit secrets.** Real keys live in local `.env` (git-ignored); only
  `.env.example` is tracked. No keys in code, commits, reports, or recordings.
- **Commit cadence:** ≥5 meaningful code commits per weekly period (see STAGE2_PLAN.md).
  Make small, descriptive commits as you implement; push regularly.
- **Document every Stage 2 change** (feature or pattern) in `docs/STAGE2_PLAN.md`
  using Original → Change → Reason → Impact.
- **Keep `docs/AI_COLLABORATION_LOG.md` updated** each session (required in the report).
- **Implement all 12 Stage 1 features** (F1–F12); don't silently drop any.
- Prefer real, working (if simple) implementations over stubs — empty methods don't count.

## Spotify API constraints (verified)
Dev-mode apps: no `/recommendations`, no audio-features, limited search (≤10/call),
≤5 users without quota, Premium required. Build discovery from the user's own
listening data (top items, saved, recently-played, search, artist→albums→tracks).
Auth: **Authorization Code with PKCE**, redirect `http://127.0.0.1:8080/callback`.

## LLM rule
The LLM never names or adds a track. Since Nov 2 it may return ids of already retrieved tracks to
remove (`GoalFitReviewer`); selection stays deterministic. Keep this true and recorded
(`docs/STAGE2_PLAN.md` section 4).

## Current status
All twelve features are implemented (CLI and GUI); 842 tests pass. Open: the GUI redesign
(`docs/GUI_TEST_FINDINGS.md`), the Stage 2 report, updated UML, demo videos. What was tried against
the real accounts is in `docs/DAILY_TEST_NOTES.md`; status by feature is in
`docs/STAGE1_CONFORMANCE.md`.
