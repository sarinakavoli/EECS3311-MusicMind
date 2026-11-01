# AI Tools & Collaboration Log

Stage 2 requires documenting the AI tools used during implementation. This log
is updated as development proceeds and is summarised in the Stage 2 report
(Section 5).

## Tools used

| Tool / platform | Model | Used for |
|---|---|---|
| Claude (claude.ai) | Claude Opus 4.8 | Stage 1 design; Stage 2 architecture, scaffolding, implementation, review |
| Claude Code | Claude Opus 4.8 | Local run/debug loop, live fixes, running the app and recording demos |

## Cost

- **Claude Pro subscription** — flat monthly fee (record the amount here, e.g. $X CAD/month). Individual token counts are not separately metered on the Pro plan.
- **Spotify Web API** — free (developer app, personal use).
- **LLM API for the app's own LLMProvider** — record provider, model, and actual spend here (e.g. Gemini free tier = $0.00).

> Replace the bracketed amounts with your real figures before submission.

## Collaboration entries

| Date | Task | AI contribution | Human (Sarina) contribution | Outcome |
|---|---|---|---|---|
| 2026-10-30 | Project scaffold | Generated Gradle+JavaFX build, domain model, core interfaces, PlaylistValidator (+tests), Strategy classes, MockLLMProvider, CLI skeleton | Reviewed design, confirmed Java/JavaFX stack, ran/verified locally | Compiling skeleton pushed; Period 2 commit cadence started |
| 2026-10-30 | Phase 1 — spine + F1 | Implemented `SpotifyAdapter` (Authorization Code + PKCE) + `RetryingMusicService` (Decorator, 429 backoff); SQLite `Database`, repositories + `MemoryManager`; `GeminiLLMProvider`; `ProfileBuilder` + `ProfileSummarizer`; `AppController` + `MusicAgentFacade` (Facade); wired **F1 View Taste Profile** in CLI + GUI; added JUnit tests (retry, memory round-trip, genre aggregation, summary fallback) | Approved the JDK 17 install and branch; to add Spotify + LLM keys to local `.env`, run the live F1 demo and review the code | Build + all tests green; F1 wired through CLI + GUI but not yet run against live Spotify; 8 commits on `phase1-spine-f1` |
| 2026-10-30 | Portable build | Upgraded Gradle wrapper 8.14.3 → 9.1.0, added the foojay toolchain resolver, verified a clean build from an empty Gradle home on JDK 25; fixed `LLM_MODEL` placeholder handling; found and fixed a GUI launch crash on macOS 26 by moving JavaFX 17.0.6 → 21.0.13 | Asked for the build to work on JDK 25 | Builds on JDK 25 with no `JAVA_HOME` setup; Java 17 toolchain downloaded automatically; GUI launches |
| 2026-10-31 | Stage 2 Tasks 6-8: F1, F2, F3, F4, F8, F9 | Scheduled Claude Code session (Opus 5.5 coordinating, Sonnet 5.5 implementers and independent reviewers per task). Built `ProfileBuilder.build`, `TrendComparator`, `Planner.parseSearchQuery`, `SearchRanker`, `MoodGenreMap`, `RecommendationEngine`, `CandidateRetriever`, `PreferenceSettings`, `StrategySelector`, and CLI commands `profile`, `trends`, `search`, `discover`, `prefs`, `feedback`. Reviews found and fixes corrected: an LLM failure that could skip the fallback, a stored profile shown without its range, widening overwriting the chosen strategy, and two config sources for one decision | To run the live checks in `docs/DAILY_TEST_NOTES.md` with real Spotify and OpenAI keys | 285 tests pass, build green, branch pushed; not yet run against live Spotify or OpenAI |
| 2026-10-31 | Live test and genre fix | Diagnosed `(no genre data)`: Spotify's replies have no `genres` field for this app. Tested MusicBrainz, then built `GenreEnrichingMusicService`, `MusicBrainzAdapter` and a genre cache (Sonnet 5.5 implementer and reviewers). Review caught a missing timeout and a crash on malformed replies, both fixed | Ran the first live test, found the gap, chose MusicBrainz | Genres shown for 13 of 20 top artists on the real account |
| 2026-11-01 | Stage 2 Task 10: F5 create flow and F11 negotiation | Claude Code (Sonnet 5.5 implementer under the Opus 5.5 controller) wrote `Planner.plan`, `ConstraintChecker`, `NegotiationHandler`, the eight session states with `AgentSession.run()`, `ExplanationService`, `AppController.createPlaylist`, and a thin `create` CLI command; split `RecommendationEngine` into `retrieve` and `recommend(Retrieval, ...)` so feasibility and selection share one Spotify read; wired the mood map and preferred styles into selection | To review the diff, run `create` against live Spotify and OpenAI, and judge the negotiation prompts | Tests written first for each part; passing locally, not yet run live |
| 2026-11-01 | Stage 2 Task 11: F6, F7, F10, F12 | Claude Code (Sonnet 5.5 implementer under the Opus 5.5 controller) wrote `PlaylistEditor` (instruction to commands, labelled mood heuristic), the min/max length rule and offending ids for `PlaylistValidator`, a stricter `verifyExplanation` (numbers, quoted names, genres), `modifyPlaylist`/`undo`/`validatePlaylist`/`explain`/`getHistory`/`restore`/`recreateFromSnapshot` in facade and controller, snapshot summaries, and minimal CLI commands; found and fixed a self-suppression crash in `BatchCommand` rollback | To review the diff and try `modify`, `undo`, `restore` against live Spotify | Tests written first for each part; passing locally, not yet run live |
| 2026-11-01 | Stage 2 Task 12: CLI complete and review fixes | Claude Code (Sonnet 5.5 implementer under the Opus 5.5 controller) turned `CliApp` into a testable instance with exit statuses, `login`, `exit`, an interactive session, help for every command and wording keys; added `feedback` id validation, `bin/musicmind` and `bin/musicmind-gui` in `installDist`, genre coverage in `trends`, retuned search weights, a stored `savedCapped` flag, one `Config` for `RetryingMusicService`, and the Sunday test notes | To run the Sunday steps in `docs/DAILY_TEST_NOTES.md` against live Spotify and OpenAI and judge the trends wording | Tests for each fix; passing locally; the new CLI commands are not yet run live |

_Add a row per working session: what you asked for, what the AI produced, what you reviewed/changed/ran, and the result._
