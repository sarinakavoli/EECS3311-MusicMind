# src/

The MusicMind application: Java 17, JavaFX GUI, CLI, PostgreSQL, one LLM (OpenAI).

```
src/
├─ main/
│  ├─ java/com/musicmind/
│  │  ├─ domain/       value classes: Track, Playlist, PlaylistSpec, ListeningProfile, AgentSession, ...
│  │  ├─ config/       Config (settings from .env, the environment and musicmind.properties)
│  │  ├─ music/        MusicService (Adapter) with SpotifyAdapter, OAuthTokenStore (PKCE),
│  │  │                RetryingMusicService and GenreEnrichingMusicService (Decorators),
│  │  │                GenreService, TagService and LastFmAdapter (optional artist and song tags)
│  │  ├─ llm/          LLMProvider and OpenAiProvider
│  │  ├─ tool/         Tool, ToolManager, SpotifyTool, MemoryTool, ToolResult
│  │  ├─ memory/       MemoryManager, Database, repositories (Repository pattern)
│  │  ├─ agent/        Planner, PromptBuilder, ResponseParser, ProfileBuilder, TrendComparator,
│  │  │                SearchRanker, RecommendationEngine, ConstraintChecker, PlaylistValidator,
│  │  │                PlaylistEditor, GoalFitReviewer, ExplanationService, ...
│  │  ├─ agent/strategy/  Strategy pattern (Familiar, Discovery, Balanced)
│  │  ├─ agent/command/   Command pattern (PlaylistCommand, PlaylistManager)
│  │  ├─ agent/state/     State pattern (SessionState and its eight states)
│  │  ├─ controller/   AppController and MusicAgentFacade (Facade)
│  │  ├─ app/          GuiApp and app/view/* (the JavaFX screens, left-hand menu and dialogs)
│  │  └─ cli/          CliApp
│  └─ resources/       musicmind.properties (tunables and wording), mood-genres.json, app/musicmind.css (the GUI theme)
└─ test/java/com/musicmind/   JUnit 5 tests, one folder per package
```

- The GUI (`app/`) and CLI (`cli/`) only call `AppController`; they contain no business logic.
- Tests need no keys, network or database server. `FakeSpotifyServer` is a local `HttpServer`,
  `PostgresTestBase` starts an embedded PostgreSQL, and `MockLLMProvider` (test code only) stands in
  for OpenAI.
- Build, run and setup instructions are in the top-level `README.md`. Which class implements which
  Stage 1 feature is in `docs/STAGE1_CONFORMANCE.md`.
