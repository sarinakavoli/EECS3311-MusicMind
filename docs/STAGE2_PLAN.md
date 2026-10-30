# Stage 2 — Build Plan & Checklist

**Window:** Oct 20 – Nov 10, 2026 · **Due:** Nov 10, 2026

## Commit cadence (≥5 meaningful *code* commits per period, Toronto time)

| Period | Dates | Required | Status |
|---|---|---|---|
| 1 | Oct 20–26 | 5 | missed (0) — capped single −5 already applies |
| 2 | Oct 27–Nov 2 | 5 | ✅ met (scaffold commits Oct 30) |
| 3 | Nov 3–9 | 5 | pending |

## Phases

- [x] **Phase 0 — Scaffold (Oct 30):** Gradle+JavaFX, domain model, core interfaces, PlaylistValidator+tests, Strategy classes, MockLLM, CLI skeleton.
- [ ] **Phase 1 — Spine + first slice (Nov 1–2):** SpotifyAdapter (auth + core endpoints), RetryingMusicService, real LLMProvider, SQLite repositories, MemoryManager, AppController/Facade wiring, **F1 View Taste Profile working in GUI + CLI**.
- [ ] **Phase 2 — Flagship agent features (Nov 3–5):** F5 Create Playlist (Planner, ConstraintChecker, RecommendationEngine, PlaylistManager + Commands, State lifecycle), F4 Discovery, F7 exposed, F8 Preferences, F9 Feedback. Record videos as each works.
- [ ] **Phase 3 — Remaining features (Nov 6–7):** F2 Trends, F3 Search, F6 Modify+Undo, F10 Explain, F11 Negotiate, F12 History/Restore. All 12 present.
- [ ] **Phase 4 — Report + videos + polish (Nov 8–9):** stage2-report (7 sections), updated UML, feature→impl mapping, patterns, changes table, AI log, weekly commit summary, demo index; README; record/upload all videos; final verification.
- [ ] **Submit (Nov 10):** verify public repo, build runs, links open, no secrets; submit URL.

## Documented Stage 2 changes (Section 3 / 4 of report)

- **PostgreSQL → SQLite** — reason: zero server setup for a solo build; impact: none on design, `Repository` interfaces unchanged.
- _(Add further changes here as they happen.)_

## Design patterns (must be evident in code)

Adapter (`MusicService`/`SpotifyAdapter`), Strategy (`RecommendationStrategy` + Abstract + 3 concretes), Command (`PlaylistCommand` + concretes, `PlaylistManager`), State (`SessionState` + phases, `AgentSession`), Facade (`MusicAgentFacade`). Plus Repository (persistence) and Decorator (`RetryingMusicService`).
