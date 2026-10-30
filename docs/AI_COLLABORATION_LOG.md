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
| 2026-10-30 | Phase 1 — spine + F1 | Implemented `SpotifyAdapter` (Authorization Code + PKCE) + `RetryingMusicService` (Decorator, 429 backoff); SQLite `Database`, repositories + `MemoryManager`; `GeminiLLMProvider`; `ProfileBuilder` + `ProfileSummarizer`; `AppController` + `MusicAgentFacade` (Facade); wired **F1 View Taste Profile** in CLI + GUI; added JUnit tests (retry, memory round-trip, genre aggregation, summary fallback) | Installed JDK 17; supplied real Spotify + LLM keys in local `.env`; to run the live F1 demo and review the code | Build + all tests green; F1 end-to-end; 8 descriptive code commits on `phase1-spine-f1` |

_Add a row per working session: what you asked for, what the AI produced, what you reviewed/changed/ran, and the result._
