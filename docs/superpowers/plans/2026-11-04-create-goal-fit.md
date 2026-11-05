# Create Goal Fit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Create pick songs that suit the goal ("slow dance" must not bring "Billie Jean").

**Architecture:** Two additions to retrieval and selection, both deterministic. (1) Songs from the user's library qualify for the "artists you know" share only when they fit the goal. (2) A new `LastFmAdapter` gives human-tagged songs for the goal's tags, which are matched to real Spotify tracks and ranked first. The LLM's role is unchanged.

**Tech Stack:** Java 17, `java.net.http`, Gson, JUnit 5 with a local `HttpServer` fake (as `MusicBrainzAdapter` tests do).

**Spec:** Sarina's request (Nov 4): "slow dance" gave "Billie Jean" and unrelated songs. A live read-only check found that Spotify returns 403 for other users' playlists, so playlist mining is impossible. She chose "quick fix + Last.fm".

## Global Constraints

- Branch `stage2-gui`. Never push, never switch branch, stage by name. Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Tunables and wording live in `musicmind.properties`, read through `Config`. No keys in code, tests or docs.
- `LASTFM_API_KEY` is read from `.env` or the environment like `LLM_API_KEY`. Add a placeholder to `.env.example`. With no key, everything works as today, and Create shows one plain notice that tag data is off.
- Spotify rules: store no Spotify titles or names. Send no Spotify titles or names to OpenAI (the guard test in `PromptBuilderTest` must stay green). Last.fm data is not Spotify content, but it is still never sent to OpenAI.
- Last.fm terms: show "Song tags from Last.fm" in the app footer next to the Spotify and MusicBrainz credits, and in the CLI attribution.
- Spotify Development Mode: search `limit` ≤ 10, no batch endpoints. Cap matching lookups with `lastfm.maxTracks`.
- Selection stays deterministic, and the LLM never names or adds a track.
- TDD, run a forced build before reporting, and add `docs/STAGE2_PLAN.md` entries (Original → Change → Reason → Impact).

## Review Focus

- No Last.fm key, Last.fm down, an unknown tag, or an empty reply: Create still works and says tag data was unavailable.
- A Last.fm song with no Spotify match, or a wrong-artist match: it is dropped, never replaced by a loose match by another artist.
- A goal with no clear tag ("songs for Tuesday"): no Last.fm call, or an empty result handled quietly.
- Too few library songs fit the goal: the share of "artists you know" drops, a notice says so, and the playlist still reaches its length.
- Blocked artists and songs, and explicit rules, still apply to Last.fm songs.

---

### Task 1: Library songs must fit the goal

**Files:** `agent/RecommendationEngine.java`, `agent/Retrieval.java` (if needed), `musicmind.properties`, `docs/STAGE2_PLAN.md`. Tests: `RecommendationEngineTest`, `CreatePlaylistFlowTest`.

**Interfaces:**
- Produces `RecommendationEngine` behaviour for runs with seed genres or search terms: a library track counts toward the familiar share only when it is goal-fit. Goal-fit means its artist genres (profile or cached MusicBrainz) intersect the seed genres, or it appears in a goal source (keyword search, or Last.fm tags from Task 3).
- When fewer goal-fit library tracks exist than the familiar share needs, the shortfall goes to new goal tracks, and a notice is added (wording key `familiarShortForGoal`).
- With no seed genres or search terms (Discover, F4), behaviour is unchanged.

- [ ] Failing tests:
  - `create_libraryTrackOutsideGoalGenres_notPicked` (a pop/dance library song is not picked for a slow-dance goal with seed genres soul, r&b and ballad)
  - `create_goalFitLibraryTrack_picked`
  - `create_tooFewFitLibraryTracks_fillsWithNewAndNotices`
  - `discover_unchanged_libraryRules`
- [ ] Implement, run the tests, then commit "Create: library songs count as familiar only when they fit the goal".

### Task 2: Last.fm tag lookup

**Files:** create `music/LastFmAdapter.java` and `music/TagService.java` (interface: `List<TagTrack> topTracks(String tag, int limit)`), create `domain/TagTrack.java` (record: `title`, `artist`), modify `config/Config.java`, `musicmind.properties`, `.env.example`. Test: `LastFmAdapterTest` against a local `HttpServer`.

**Interfaces:**
- `TagService.topTracks(String tag, int limit): List<TagTrack>`. It calls `GET {lastfm.apiBaseUrl}?method=tag.gettoptracks&tag=<tag>&limit=<n>&api_key=<key>&format=json`. It returns an empty list when there is no key. It throws `MusicServiceException` on an HTTP error or a body it cannot read.
- `Config`: `lastfmApiKey()`, `lastfmConfigured()`, `lastfmApiBaseUrl()` (default `https://ws.audioscrobbler.com/2.0/`), `lastfmMaxTracks()` (20), `lastfmTimeoutSeconds()`, and `lastfmUserAgent()`, which reuses the MusicBrainz user-agent pattern.
- Properties: `lastfm.apiBaseUrl`, `lastfm.maxTracks=20`, `lastfm.maxTags=2`, `lastfm.timeoutSeconds=10`, and wording `text.lastfmAttribution=Song tags from Last.fm`, `text.lastfmOff=Song tags from Last.fm are off (no LASTFM_API_KEY), so songs were chosen from genres and searches only.`

- [ ] Failing tests:
  - `topTracks_parsesTitlesAndArtists`
  - `topTracks_noKey_emptyWithoutCalling`
  - `topTracks_httpError_throws`
  - `topTracks_malformedBody_throws`
  - `topTracks_tagIsUrlEncoded`
  - `topTracks_respectsLimit`
- [ ] Implement, run the tests, then commit "Last.fm tag lookup behind TagService".

### Task 3: Use Last.fm songs in Create and credit Last.fm

**Files:** `agent/Planner.java` and `agent/PromptBuilder.java` and `agent/ResponseParser.java` (the plan gets `tags`: up to `lastfm.maxTags` short Last.fm-style tags such as "slow dance" or "romantic", or [] when none fit; no song or artist names), `domain/AgentPlan.java`, `agent/CandidateRetriever.java`, `agent/RecommendationEngine.java`, `agent/Retrieval.java`, `controller/AppController.java` (wiring), `app/GuiApp.java` footer, `cli/CliApp.java` attribution, `musicmind.properties`, `docs/STAGE2_PLAN.md`, `README.md` (the key and the attribution). Tests: `PlannerPlanTest`, `CandidateRetrieverTest`, `RecommendationEngineTest`, `CreatePlaylistFlowTest`, `PromptBuilderTest` (the guard stays green).

**Interfaces:**
- Consumes `TagService` from Task 2, and the goal-fit rule from Task 1, where tag songs count as a goal source.
- `AgentPlan.tags(): List<String>`. The old constructors stay and give an empty list.
- `CandidateRetriever.byTag(String tag): List<Track>`. It calls `TagService.topTracks`, then for each tag track up to `lastfm.maxTracks` it runs a Spotify search `track:"<title>" artist:"<artist>"` with limit 1. It keeps a result only when the normalised title matches and one artist name matches (reuse the `SearchRanker` normaliser). Failures are recorded the way other retriever reads are.
- `RecommendationEngine`: new `Source.TAG`. A tag track gets the goal floor and a bonus `discover.weight.tagBonus=0.15`. A library track that is also a tag track counts as goal-fit.
- When Last.fm is off or fails, Create adds the `lastfmOff` notice or the existing read-failed notice once, and carries on.

- [ ] Failing tests:
  - `plan_readsTags`
  - `byTag_matchesTitleAndArtistOnly`
  - `byTag_dropsWrongArtistMatch`
  - `byTag_noKey_noCalls`
  - `create_tagTracksRankFirstAndRespectBlocks`
  - `create_lastfmDown_stillCreatesWithNotice`
  - `footerShowsLastfmCredit` (or a ViewFormat or Config test)
- [ ] Implement, then run `./gradlew build --rerun-tasks --no-build-cache`, then commit "Create uses Last.fm tag songs; Last.fm credited".
- [ ] Live check (read-only): with Sarina's key in `.env`, a throwaway driver calls `planner` + `retrieve` + `recommend` for "slow dance", "workout" and "study" without creating a playlist, and prints titles, artists and sources to a scratch file. Without the key, record that the live check waits for it.
