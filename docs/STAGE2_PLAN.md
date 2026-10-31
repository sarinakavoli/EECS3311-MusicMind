# Stage 2 — Build Plan & Checklist

**Window:** Oct 20 – Nov 10, 2026 · **Due:** Nov 10, 2026

## Commit cadence (≥5 meaningful *code* commits per period, Toronto time)

| Period | Dates | Required | Status |
|---|---|---|---|
| 1 | Oct 20–26 | 5 | missed (0) — capped single −5 already applies |
| 2 | Oct 27–Nov 2 | 5 | ✅ met (scaffold + Phase 1 commits, Oct 30) |
| 3 | Nov 3–9 | 5 | pending |

## Phases

- [x] **Phase 0 — Scaffold (Oct 30):** Gradle+JavaFX, domain model, core interfaces, PlaylistValidator+tests, Strategy classes, MockLLM, CLI skeleton.
- [x] **Phase 1 — Spine + first slice (code complete Oct 30; live Spotify demo pending keys):** SpotifyAdapter (PKCE auth + core endpoints), RetryingMusicService, real LLMProvider (Gemini), SQLite repositories, MemoryManager, AppController/Facade wiring, **F1 View Taste Profile working in GUI + CLI**. Unit tests green (retry/backoff, SQLite round-trip, genre aggregation, summary fallback).
- [ ] **Phase 2 — Flagship agent features (Nov 3–5):** F5 Create Playlist (Planner, ConstraintChecker, RecommendationEngine, PlaylistManager + Commands, State lifecycle), F4 Discovery, F7 exposed, F8 Preferences, F9 Feedback. Record videos as each works.
- [ ] **Phase 3 — Remaining features (Nov 6–7):** F2 Trends, F3 Search, F6 Modify+Undo, F10 Explain, F11 Negotiate, F12 History/Restore. All 12 present.
- [ ] **Phase 4 — Report + videos + polish (Nov 8–9):** stage2-report (7 sections), updated UML, feature→impl mapping, patterns, changes table, AI log, weekly commit summary, demo index; README; record/upload all videos; final verification.
- [ ] **Submit (Nov 10):** verify public repo, build runs, links open, no secrets; submit URL.

## Documented Stage 2 changes (Section 3 / 4 of report)

- **PostgreSQL → SQLite** — reason: zero server setup for a solo build; impact: minimal; concrete repositories are SQLite-backed, `MemoryManager` façade unchanged.
- **`Repository<T, ID>` → `Repository<T>`** (per-user `save(userId, entity)` / `findByUser` / `deleteForUser`) — reason: the domain value objects (`ListeningProfile`, `UserPreference`) are immutable and do not embed their owner, so the original `save(T)` / `findById(ID)` shape could not be implemented cleanly; impact: interface reshaped before any implementation existed; SQLite repos + `MemoryManager` use the new shape.
- **`MusicService` gains `getTopArtists(timeRange)`** — reason: F1 aggregates genres, which Spotify exposes only on artists, but `getTopItems` returns `Track`; impact: additive method implemented by `SpotifyAdapter` and passed through `RetryingMusicService`.
- **LLM provider = OpenAI** (`OpenAiProvider` via the `LLMProvider` seam, per Stage 1) — impact: real provider used when `LLM_API_KEY` is set; otherwise no provider and features with a Stage 1 fallback use a labelled computed one. `MockLLMProvider` is test-only (R17).
- **Auth is PKCE (public client)** — reason: no server to hold a client secret; impact: only `SPOTIFY_CLIENT_ID` is needed; `.env.example` dropped the secret/Postgres vars and added `DATABASE_PATH`.
- **Gradle 8.14.3 → 9.1.0 + foojay toolchain resolver** — reason: Gradle 8.14.3 cannot run on JDK 25, and a machine with only JDK 25 has no Java 17 toolchain; impact: `./gradlew` works on JDK 25 with no `JAVA_HOME` setup, the project still compiles as Java 17, and the first build downloads a JDK 17 if none is found (needs network).
- **JavaFX 17.0.6 → 21.0.13** — reason: on macOS 26 the 17.0.6 native window code crashed the app on launch (`NSTrackingRectTag` exception in `libglass`); impact: GUI launches; 21 is an LTS line that still supports Java 17, and no GUI code changed.
- **Artist genres come from MusicBrainz, not Spotify (R18)** — Original: Stage 1 reads genres from Spotify artist metadata. Change: `GenreEnrichingMusicService` (Decorator over `MusicService`) fills `Artist.genres` through `GenreService`/`MusicBrainzAdapter` (Adapter), cached by artist id in `artist_genre`. Reason: live testing showed Spotify returns no `genres` to a Development Mode app. Impact: F1, F2, F4, F5 get a genre signal again; coverage can be partial, which `ProfileBuilder` reports, and the CLI credits MusicBrainz. Genres are derived data, so caching them respects the Spotify terms.
- **Search sends genre as a plain keyword (R19)** — Original: Stage 1 F3/F4 pass genre to Spotify search as a `genre:` filter. Change: `SearchQuery.toSpotifyQuery()` appends the genre as a keyword (skipped if the keywords already contain it); `year` and `artist` filters stay, and LLM filters outside `search.allowedFilters` are dropped. An empty F3 result is retried once with the keywords alone before the no-results notice. Reason: live testing showed `upbeat indie running genre:indie` returned 1 track, `upbeat indie running` returned 20. Impact: F3 and F4 (`CandidateRetriever.byGenre`) return results; genre still ranks results.
- _(Add further changes here as they happen.)_

## Design patterns (must be evident in code)

Adapter (`MusicService`/`SpotifyAdapter`), Strategy (`RecommendationStrategy` + Abstract + 3 concretes), Command (`PlaylistCommand` + concretes, `PlaylistManager`), State (`SessionState` + phases, `AgentSession`), Facade (`MusicAgentFacade`). Plus Repository (persistence) and Decorator (`RetryingMusicService`, `GenreEnrichingMusicService`).
