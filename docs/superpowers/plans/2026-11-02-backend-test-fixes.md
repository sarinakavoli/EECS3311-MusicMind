# Backend Test Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the backend problems Sarina found while testing PR #4, so search finds real titles, playlists suit their goal, and the frontend phase has the data it needs.

**Architecture:** Additive changes behind `MusicService`, `MusicAgentFacade` and `AppController`. Selection stays deterministic; the one new AI step only removes real retrieved tracks and never names any. Screens are not redesigned here.

**Tech Stack:** Java 17, Gradle 9.1, JUnit, embedded PostgreSQL in tests, `MockLLMProvider`, the fake `MusicService` in existing tests.

**Spec:** `docs/GUI_TEST_FINDINGS.md`, `docs/MusicMind_Stage1_Report.docx`, `docs/STAGE2_IMPLEMENTATION_PLAN.md`

## Global Constraints

- Work on `stage2-gui`; PR #4 grows with these commits. Never force-push.
- No hard-coded tunables or user wording: new values go in `musicmind.properties` and are read through `Config`.
- Never print or commit secrets. Do not read `.env` or `.spotify-tokens.json`.
- Tests use fakes and the mock LLM; no test touches Sarina's account.
- Do not run `create`, `modify`, `undo`, `restore` or `delete` on her account without her saying so.
- Keep Stage 1 method signatures working; add overloads rather than changing them.
- Each Stage 1 difference gets an Original → Change → Reason → Impact entry in `docs/STAGE2_PLAN.md`.
- Every task uses TDD: watch the test fail first. Run tests with a forced run before reporting success.

## Review Focus

- A mood-only search such as "upbeat" must not bring back stock tracks with that word in the title; the typed pass is skipped when every word is a mood word.
- A title with punctuation or different case ("AMERICAN GIRLS!") still counts as an exact match.
- The AI reply is garbage, empty, names unknown ids, or removes nearly everything: the run carries on with real tracks and a notice.
- Spotify omits `display_name` or artist names, or returns null: the app falls back to the id or to an empty name list and does not crash.
- Deleting a playlist with a failed snapshot save: the playlist is not removed, and the user is told.

## Not in this plan (frontend phase)

Items 1 (labels), 2, 6, 8, 9, 11, 13, 14, 18, 20, 23, 24, plus showing what Tasks 1, 3, 6 and 7 provide on screen and the cut-off text in item 17.

---

### Task 1: Artist names and account name (items 4, 7)

**Files:**
- Modify: `domain/Track.java`, `music/MusicService.java`, `music/SpotifyAdapter.java`, `music/RetryingMusicService.java`, `music/GenreEnrichingMusicService.java`, `controller/AppController.java`
- Test: `SpotifyAdapter` test class, fake `MusicService` classes in `src/test`

**Interfaces:**
- Produces: `Track.artistNames(): List<String>` as a new record component; the existing 6-argument constructor stays and gives an empty list. `MusicService.currentUserDisplayName(): String`, never null, falling back to `currentUserId()`. `AppController.currentUserName(): String`.

- [ ] **Step 1: Write failing tests** `toTrack_readsArtistNames`, `toTrack_missingArtistNames_isEmpty`, `displayName_usesDisplayName`, `displayName_blankOrNull_fallsBackToId`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Read `artists[].name` in `toTrack`. Cache the `/me` body so id and name cost one request.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit** "Track carries artist names; expose the Spotify display name".

### Task 2: Search finds what was typed (items 3, 5)

**Cause:** live, "american girls" returned real "American Girls" titles from the CLI but unrelated songs in the GUI. The same words get different AI rewrites, and the typed words are only used when the AI fails.

**Files:**
- Modify: `agent/SearchRanker.java`, `controller/MusicAgentFacade.java:282-342`, `musicmind.properties`
- Test: `SearchRankerTest`, `MusicAgentFacadeTest`

**Interfaces:**
- Consumes: `Track.artistNames()` from Task 1.
- Produces: `SearchRanker.merge(String typed, List<Track> typedResults, List<Track> meaningResults): List<Track>`.

- [ ] **Step 1: Write failing tests:**
  - `merge_exactTitleFirst`: for "american girls", a track titled "American Girls" outranks any track from `meaningResults`.
  - `merge_artistMatchFollowsTitleMatch`: order is exact title, all typed words in title, all typed words in title plus artist names, then meaning results.
  - `merge_ignoresCaseAndPunctuation`: "AMERICAN GIRLS!" matches "American Girls".
  - `merge_unmatchedTypedResultsDropped_whenMeaningHasResults`.
  - `merge_removesDuplicates`: same normalised title, same artist names and same `durationSec()` keeps the first; same title by another artist stays.
  - `searchMusic_runsTypedWordsAndAiQuery`.
  - `searchMusic_moodOnlyQuery_skipsTypedPass` ("upbeat").
  - `searchMusic_sameAsTyped_searchesOnce`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** `merge` and call it from `searchMusic`. Skip the typed pass only when every typed word is a word `MoodGenreMap` knows, and the second request when the AI query equals the typed text. Cut the result to `search.resultLimit`.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit** "Search puts exact title and artist matches first and removes duplicates".

### Task 3: Candidates are a choice; explanations stay honest (items 15, 17)

**Files:**
- Modify: `domain/AgentRequest.java`, `controller/MusicAgentFacade.java:513`, `agent/PromptBuilder.java:84-98`, `cli/CliApp.java`, `musicmind.properties`, `docs/STAGE2_PLAN.md`
- Test: `MusicAgentFacadeTest`, `PromptBuilderTest`, CLI test class

**Interfaces:**
- Produces: `AgentRequest.useCandidates(): boolean`; the 2- and 3-argument constructors default to true. CLI flag `--no-candidates`. `AppController.candidateTracks()` keeps its current name if one exists, so the dialog can list them.

- [ ] **Step 1: Write failing tests** `create_noCandidates_usesNone_andKeepsThemSaved`, `create_default_stillStartsWithCandidates`, `explainPrompt_forbidsClaimingCandidatesFitTheGoal`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** With the flag false, the run starts with no candidate ids and leaves the stored list alone. Add a prompt rule: a track the user added may be described only as added by the user, and no track may be said to fit a mood unless its reason says so.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Add the `STAGE2_PLAN.md` entry** for candidates becoming optional.
- [ ] **Step 6: Commit** "Candidates are optional in create; explanations claim only recorded reasons".

### Task 4: Playlists suit the goal (item 16)

**Cause:** new picks come only from guessed genres, and Spotify gives this app no tempo or energy data.

**Files:**
- Create: `agent/GoalFitReviewer.java`
- Modify: `domain/AgentPlan.java`, `agent/Planner.java`, `agent/PromptBuilder.java`, `agent/ResponseParser.java`, `agent/CandidateRetriever.java`, `agent/RecommendationEngine.java:141`, `agent/Retrieval.java`, `agent/state/RetrievingState.java`, `agent/state/RunServices`, `musicmind.properties`, `docs/STAGE2_PLAN.md`
- Test: `GoalFitReviewerTest`, `PlannerTest`, `CandidateRetrieverTest`, `AgentSessionTest` or the state test class

**Interfaces:**
- Consumes: `Track.artistNames()`.
- Produces: `AgentPlan.searchTerms(): List<String>` (a 4-argument constructor stays, giving an empty list); `CandidateRetriever.byKeywords(String): List<Track>`; `Retrieval.without(Set<String> trackIds): Retrieval`; `GoalFitReviewer.review(String goal, List<Track> tracks): GoalFitReviewer.Result` with `Set<String> removedIds` and `boolean reviewed`.
- Properties: `goal.maxSearchTerms=3`, `goal.reviewMaxTracks=60`, `goal.minKeepShare=0.4`, wording `goalReviewSkipped`.

- [ ] **Step 1: Write failing tests:**
  - `plan_readsSearchTerms` and `plan_capsSearchTerms`: the plan prompt asks for short search phrases for the activity and contains no request to name songs or artists.
  - `retrieve_addsKeywordTracks`.
  - `review_removesOnlyIdsItWasGiven`: unknown ids in the reply are ignored.
  - `review_garbageReply_removesNothing_andReportsNotReviewed`.
  - `review_llmFailure_removesNothing`.
  - `review_keepsAllWhenTooFewWouldRemain`: below `goal.minKeepShare`, nothing is removed and the result says not reviewed.
  - `retrieving_appliesReview_andCandidatesAreNotReviewed`: tracks the user picked are never removed.
  - `retrieving_failedReview_addsNotice_andContinues`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Search the terms in retrieval alongside the seed genres. Send the reviewer the goal plus id, title and artist names for up to `goal.reviewMaxTracks` tracks; it replies with JSON ids to remove. Selection after that stays deterministic.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Add the `STAGE2_PLAN.md` entry:** Stage 1 said the LLM takes no part in selection; it now removes unsuitable real tracks. The reason is Sarina's test result and the missing audio features. It names no tracks, and the final pick stays deterministic.
- [ ] **Step 6: Commit** "Create searches the goal's activity and reviews real tracks against the goal".

### Task 5: Scores tell tracks apart (item 12)

**Cause:** `RecommendationEngine.affinity` sets 1.0 for any track from a seed genre, so all new tracks from those genres score the same (0.60 in Sarina's test).

**Files:**
- Modify: `agent/RecommendationEngine.java:401-411`, `musicmind.properties`
- Test: `RecommendationEngineTest`

**Interfaces:**
- Properties: `discover.seedGenreBonus=0.2`.

- [ ] **Step 1: Write failing tests** `score_seedTracksDifferByTaste`: two tracks from one seed genre, one by an artist with higher affinity, score differently; `score_seedGenreStillBeatsNonSeed`; `score_acceptedStillBeatsRejected`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement:** affinity becomes the best taste affinity plus `discover.seedGenreBonus` for a seed genre, capped at 1.0.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit** "Scores reflect taste within a requested genre".

### Task 6: Saved mix and blocking by name (items 10, 21, 22)

**Files:**
- Modify: `controller/AppController.java`, `controller/MusicAgentFacade.java`, `cli/CliApp.java`, `musicmind.properties`
- Test: `AppControllerTest`

**Interfaces:**
- Consumes: `Track.artistNames()`.
- Produces: `AppController.savedFamiliarBias(): OptionalDouble`; `AppController.findArtists(String name): List<Artist>`, exact-name matches first; `AppController.findTracks(String text): List<Track>`, using Task 2's search; `AppController.blockArtist(String artistId)`; `AppController.blockTrack(String trackId)`. Both block methods add to the stored list and keep what is there.

- [ ] **Step 1: Write failing tests** `savedFamiliarBias_readsStoredValue`, `savedFamiliarBias_emptyWhenUnset`, `findArtists_listsEverySameName`, `blockTrack_keepsExistingBlocks`, `blockArtist_addsChosenId`, `blockTrack_rejectsBadId`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** on top of `PreferenceSettings`, `parsePreference` and `setPreference`. The existing top-match behaviour stays for the CLI.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit** "Expose the saved mix and block songs and artists by choosing them".

### Task 7: Delete a playlist (item 19)

**Files:**
- Create: `agent/command/DeletePlaylistCommand.java`
- Modify: `agent/command/PlaylistManager.java`, `controller/MusicAgentFacade.java`, `controller/AppController.java`, `cli/CliApp.java`, `musicmind.properties`, `docs/STAGE2_PLAN.md`
- Test: `PlaylistManagerTest`, `AppControllerTest`

**Interfaces:**
- Produces: `AppController.deletePlaylist(String playlistId)`; CLI `delete <playlistId>`, which asks for the playlist's name to be typed before acting.

- [ ] **Step 1: Write failing tests** `delete_snapshotsThenUnfollows`, `delete_snapshotFails_playlistKept`, `delete_notOwner_refused`, `delete_canBeRecreatedFromSnapshot`, `cliDelete_wrongName_doesNothing`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** as a Command, so it matches the other playlist changes. Save a snapshot first, then call `MusicService.unfollowPlaylist`.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Add the `STAGE2_PLAN.md` entry** for delete as an addition beyond Stage 1.
- [ ] **Step 6: Commit** "Delete a playlist after saving a snapshot".

### Task 8: Check the whole change

- [ ] **Step 1: Forced run.** `./gradlew build --rerun-tasks --no-build-cache`. Expected: 0 failures.
- [ ] **Step 2: Live read-only check.** `search "american girls"` five times: an "American Girls" title in the top 3 every time. `search "mellow alternative for studying"`: no literal-title stock results at the top.
- [ ] **Step 3: Sarina runs Create** with her approval for several goals, such as workout, study and road trip, and judges the songs. This is the real test of Task 4.
- [ ] **Step 4: Update the notes:** `docs/GUI_TEST_FINDINGS.md`, `docs/DAILY_TEST_NOTES.md`, `docs/AI_COLLABORATION_LOG.md`.
- [ ] **Step 5: Run `code-review` on PR #4** and report the findings.
- [ ] **Step 6: Push** `stage2-gui`. Merging stays Sarina's decision.
