# CLAUDE.md — MusicMind (EECS3311 Stage 2)

Project context for Claude Code. Read `docs/STAGE2_PLAN.md` for the full plan.

## What this is
MusicMind — an AI music-discovery & playlist-management agent that integrates the
Spotify Web API. EECS3311 Software Design course project (individual). Stage 1
(design) is complete and in `docs/`; **Stage 2 is implementation**, due **Nov 10, 2026**.

## Stack
- **Java 17**, **JavaFX** (GUI), a **CLI**, **SQLite** (JDBC) for persistence, and one
  **LLM** behind the `LLMProvider` interface. Build tool: **Gradle** (wrapper committed).
- SQLite replaces the PostgreSQL planned in Stage 1 (documented change — see STAGE2_PLAN.md).

## Commands
```bash
./gradlew build                      # compile
./gradlew test                       # run unit tests
./gradlew run                        # launch the GUI
./gradlew runCli --args="help"       # launch the CLI
```

## Package map (`src/main/java/com/musicmind/`)
- `domain/` — entities (Track, Playlist, PlaylistSpec, ListeningProfile, AgentSession, …)
- `music/` — `MusicService` (Adapter target); `SpotifyAdapter`, `RetryingMusicService` (to build)
- `llm/` — `LLMProvider`; `MockLLMProvider` (done), real provider (to build)
- `agent/` — `PlaylistValidator` (done); Planner, ToolManager, ConstraintChecker,
  RecommendationEngine, ProfileBuilder, ExplanationService, PromptBuilder, ResponseParser (to build)
- `agent/strategy/` — Strategy pattern: Abstract + Familiar/Discovery/Balanced (done)
- `agent/command/` — `PlaylistCommand` + concretes, PlaylistManager (to build)
- `agent/state/` — `SessionState` + phase classes (to build)
- `memory/` — MemoryManager + repositories (`Repository` interface done; SQLite impls to build)
- `tool/` — `Tool`, `ToolResult`; SpotifyTool, MemoryTool (to build)
- `controller/` — `AppController` (to build); `app/GuiApp` (done), `cli/CliApp` (done)

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

## Next task (Phase 1)
Build the spine and the first end-to-end feature:
1. `SpotifyAdapter` implementing `MusicService` — PKCE auth + `GET /me`, `/me/top/*`,
   `/me/tracks`, `/search`, create playlist, add items. Wrap with `RetryingMusicService`
   (Decorator) for 429 backoff.
2. SQLite repositories + `MemoryManager`.
3. `AppController` + `MusicAgentFacade` wiring.
4. **F1 View Taste Profile** working in both GUI and CLI (ProfileBuilder aggregates
   genres; LLM writes a one-line summary). Record a short demo when it works.
