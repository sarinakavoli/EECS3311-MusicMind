# Stage 1 conformance

This file traces each Stage 1 feature (F1 to F12) to the code that implements it, the tests that
cover it, how to reach it from the CLI and the GUI, and what was and was not tried against the
real Spotify and OpenAI accounts. It also lists every difference from Stage 1, with its reason, so
that nothing is left unexplained. Details and evidence for each difference are in
`docs/STAGE2_PLAN.md` (Original -> Change -> Reason -> Impact).

How to read it:

- Class paths are relative to `src/main/java/com/musicmind/`. Test paths are relative to
  `src/test/java/com/musicmind/`.
- **Status** is *Implemented* (works as Stage 1 describes), *Modified* (works, with a difference
  listed under it), or *Added* (not in Stage 1).
- **Live** says what `docs/DAILY_TEST_NOTES.md` records as run against the real accounts. Anything
  not named there was only tested with stand-in Spotify and LLM servers and an embedded
  PostgreSQL. The automated suite is 1039 tests in 57 test classes; `./gradlew test` on the
  `stage2-alignment` branch (Nov 6, `main` plus the audit fixes) passed all of them, using no keys and no database server.
- The GUI is the dark redesign merged on Nov 4 (pull request #4): a left-hand menu of seven screens
  (Your Taste, Trends, Find Songs, Discover, Playlists, Your Rules, History), one shared song row
  (`TrackRow`), a Create dialog, a confirm dialog (`ConfirmDialog`) and the conflict dialog. The
  menu names replace the Stage 1 tab names for F1, F3 and F7/F8; the F-numbers are unchanged. It
  was checked live read-only on Nov 4; its write actions were not pressed by a person yet, and
  screenshots will be retaken after the owner's test (`docs/GUI_TEST_FINDINGS.md`).

## 1. Summary

| ID | Feature | Type in code | Status | CLI | GUI |
|---|---|---|---|---|---|
| F1 | Music Taste Profile | Hybrid | Modified | `profile [--refresh] [--range week\|short\|medium\|long]` | Your Taste screen (Past week, month, 6 months, year) |
| F2 | Taste-Change Analysis | Hybrid | Modified | `trends` | Trends screen |
| F3 | Natural-Language Music Search | Hybrid | Modified | `search`, `candidates ...` | Find Songs screen ("+ Add") |
| F4 | Personalized Discovery | Deterministic | Modified | `discover [--novelty]` | Discover screen |
| F5 | AI Playlist Planning and Generation | Hybrid | Modified | `create "<goal>" [flags]` | Create playlist dialog (Playlists screen) |
| F6 | Natural-Language Playlist Modification | Hybrid | Modified | `playlists`, `show`, `modify`, `undo` | Playlists screen |
| F7 | Playlist Constraint Validation | Deterministic | Modified | `validate <playlistId>` | Badges on the Playlists screen |
| F8 | Preference Memory | Deterministic | Modified | `prefs list/get/set` | Your Rules screen |
| F9 | Recommendation Feedback | Deterministic | Implemented | `feedback <trackId> accept\|reject` | Like / not-for-me icons on every song row |
| F10 | Grounded Recommendation Explanation | AI with checked fallback | Modified | `explain <playlistId>` | "Why these?" expanders |
| F11 | Infeasible-Constraint Negotiation | Hybrid | Implemented | prompt during `create` and `modify` | Conflict dialog |
| F12 | Agent History and Snapshot/Restore | Deterministic | Modified | `history`, `restore <snapshotId>` | History screen |
| New | Delete a playlist | Deterministic | Added | `delete <playlistId>` | Delete on a playlist row, with a confirm dialog |

All of this runs through `controller/AppController` and `controller/MusicAgentFacade`. The CLI
(`cli/CliApp`) and the views (`app/view/*`) contain no business logic.

## 2. Feature by feature

### F1 Music Taste Profile (UC02, SD03)

- **Flow:** `AppController.analyzeTaste(user[, range])` -> `MusicAgentFacade.analyzeTaste` ->
  `ToolManager.execute` with the tools `getTopItems` (tracks or artists) and `getSavedTracks`
  (`tool/SpotifyTool`) -> `SpotifyAdapter` through `RetryingMusicService` and
  `GenreEnrichingMusicService` -> `ProfileBuilder.build(TopItems, saved)` and
  `computeGenreHistogram` -> `PromptBuilder.buildSummaryPrompt` -> `LLMProvider.complete`
  (`OpenAiProvider`) for the one-sentence summary -> `MemoryManager.saveSession` (derived data only).
  `AppController.lastProfile(user)` returns the stored profile.
- **Deterministic or AI:** hybrid. Genre counts and rankings are computed; the LLM writes only the
  summary sentence. With no key or a failed call the summary is computed and labelled
  "[computed from your listening data, not AI-written]".
- **Differences:** genres come from Last.fm tags, not Spotify (R18; MusicBrainz until Nov 5); the profile is stored through
  `saveSession` as ids and counts (R4); `profile` shows the stored profile and `--refresh`
  rebuilds (R5); `--range week|short|medium|long` was added; a library larger than
  `spotify.maxSavedTracks` marks the profile as partial; genre coverage is reported.
  **Past week (Nov 4):** Spotify has no top items for a week, so `MusicAgentFacade.analyzePastWeek`
  reads `MusicService.getRecentPlays()` (at most the 50 most recent plays), keeps the last
  `profile.weekDays` (7) days, ranks tracks and artists by play count and fills genres through
  `MusicService.withGenres` (the same Last.fm lookup and cache). The screen says "Built from your
  last N plays (Spotify only keeps your 50 most recent)". Only derived ids and genres are stored,
  never the play times. `AppController.timeRanges()` lists week, short, medium, long;
  `trendRanges()` keeps Trends to Spotify's three, because a 50-play sample would not compare fairly.
- **Error cases handled:** little history gives a partial profile with a notice; an expired token
  asks for login again (`AuthExpiredException`); HTTP 429 is retried with backoff.
- **Tests:** `agent/ProfileBuilderTest`, `controller/MusicAgentFacadeTest`
  (`analyzeTasteFollowsTheSd03InteractionOrder`, `littleHistoryGivesAPartialProfileWithANoticeAndStillSavesIt`,
  `withNoLlmTheSummaryIsComputedFromGenresAndLabelled`), `controller/AppControllerTest`,
  `music/GenreEnrichingMusicServiceTest`, `music/LastFmAdapterTest`,
  `music/RetryingMusicServiceTest`, `music/SpotifyAdapterTest`, `memory/repository/SessionRepositoryTest`,
  `cli/CliAppTest`, `app/view/ViewFormatTest`.
- **GUI:** `app/view/ProfileView` (the Your Taste screen): a time-range choice (Past week, Past month,
  Past 6 months, Past year), genre bars, top artists and tracks as shared song rows, a coverage
  notice, the summary, and a "Refresh profile" button.
- **Live:** `profile --refresh`, `profile` and `profile --range long` ran against the real account on
  Oct 31; genres appeared for 13 of 20 top artists. The GUI Profile refresh was run read-only
  through a throwaway script on Nov 2, and the redesigned Your Taste screen read-only on Nov 4.
  The Past week range is tested with a fake music service (`MusicAgentFacade` and `AppController`
  tests, `ViewFormatTest`); it was not tried against the real account.

### F2 Taste-Change Analysis (UC03, SD03)

- **Flow:** `AppController.analyzeTrends(user)` -> `MusicAgentFacade.analyzeTrends` -> `ToolManager`
  reads the top artists of the three ranges -> `agent/TrendComparator.compare` (set differences,
  `domain/TrendReport`, `domain/RangeChange`) -> `PromptBuilder.buildTrendPrompt` ->
  `LLMProvider.complete` for the narrative.
- **Deterministic or AI:** hybrid. The differences are computed; the LLM words the narrative, with
  a computed, labelled narrative as the fallback.
- **Differences:** every step between ranges is compared (`TrendReport.steps()`), not only the
  newest against the oldest; genre coverage per range is reported and the wording turns cautious
  below `trends.minGenreCoverage` (0.5).
- **Error cases handled:** a range with no data is marked and the others are still compared; a
  range that fails to load is a gap, not an error.
- **Tests:** `agent/TrendComparatorTest`, `controller/MusicAgentFacadeTest`
  (`analyzeTrendsFetchesAllThreeRangesAndComparesThem`, `anEmptyRangeIsMarkedAndTheOthersAreStillCompared`,
  `theComputedNarrativeSaysCoverageIsPartialInsteadOfClaimingAChange`), `agent/PromptBuilderTest`,
  `cli/CliAppTest`, `app/view/ViewFormatTest`.
- **GUI:** `app/view/TrendsView`: "Compare my listening", three columns and "Changes between ranges".
- **Live:** `trends` ran against the real account (Oct 31 and, read-only, Nov 1). GUI Trends ran
  read-only through the throwaway script on Nov 2.

### F3 Natural-Language Music Search (UC04, SD01)

- **Flow:** `AppController.searchMusic(query)` -> `MusicAgentFacade.searchMusic` ->
  `Planner.parseSearchQuery` (`PromptBuilder.buildSearchPrompt`, `ResponseParser.parseSearchQuery`)
  -> `SearchQuery.toSpotifyQuery()` -> `ToolManager` tool `search` / `searchArtists` ->
  `SearchRanker.rank` and `SearchRanker.merge` (`ResponseParser.verifyTracks` is not on this path
  since Nov 6: the model names no track, see section 5, row 17). The words as typed
  are searched too and merged in. Candidates: `AppController.addCandidate/removeCandidate/clearCandidates/getCandidates`
  -> `MemoryManager` -> `memory/repository/CandidateRepository` (ids only).
- **Deterministic or AI:** hybrid. The LLM only turns the text into a `SearchQuery`; searching and
  ranking are deterministic. A low-confidence or failed parse searches the original text and says so
  (`[plain keyword search: ...]`).
- **Differences:** genre goes to Spotify as a plain keyword (R19); mood words are removed from the
  search text and replaced by a mapped genre; the typed words are searched as well and ranked
  first when they match a title or artist; duplicates of the same recording are merged; artists
  whose name the query contains are listed; the stored candidate set feeds the next `create`.
- **Error cases handled:** no results gives a relaxed-query suggestion; an empty parsed search is
  retried with the keywords alone; a blank query is refused.
- **Tests:** `agent/PlannerTest`, `agent/SearchRankerTest`, `agent/ResponseParserTest`,
  `controller/MusicAgentFacadeTest` (`searchMusic_runsTypedWordsAndAiQuery`,
  `searchMusic_moodWordInQuery_doesNotShowUnmatchedTyped`, `noResultsGivesAnEmptyListAndARelaxedQuerySuggestion`),
  `controller/AppControllerTest`, `memory/repository/CandidateRepositoryTest`, `tool/SpotifyToolTest`,
  `cli/CliAppTest`.
- **GUI:** `app/view/SearchView`: a search box, tracks with "Add to candidates", artists above
  them, the candidate list with "Clear all". Rows show no artist names yet.
- **Live:** `search` ran live (Oct 31, Nov 1); "american girls" returned exact titles in the top
  3 in 5 of 5 runs (Nov 2); the `Artists:` section and `candidates add/list/clear` ran live (Nov 1).
  **Known weak spot:** searches by description ("mellow alternative for studying") still return
  poor results, because Spotify matches title words and this app has no mood data.

### F4 Personalized Discovery (UC05, SD04)

- **Flow:** `AppController.discoverSpec(novelty)` and `discover(spec)` ->
  `MusicAgentFacade.discover(profile, spec)` -> `MemoryManager.loadPreferences/loadFeedback` ->
  `agent/PreferenceSettings` -> `RecommendationEngine.retrieve` (via `agent/CandidateRetriever`:
  top, saved and recently played tracks, genre searches, artist -> albums -> tracks) ->
  `RecommendationEngine.recommend(...)` with the strategy chosen by `agent/strategy/StrategySelector`
  (`FamiliarStrategy`, `DiscoveryStrategy` or `BalancedStrategy`; `selectCandidates`,
  `scoreFamiliarity`, `excludeKnown`) -> `RecommendationEngine.score`.
- **Deterministic or AI:** deterministic in the code. Stage 1 allowed the LLM to phrase reasons
  optionally; the code never does (`AppControllerDiscoverTest.discoverNeverCallsTheLlm`), so
  reasons are computed from recorded facts.
- **Differences:** known tracks (top, saved, recently played) are excluded from every pool; widening
  notices; `discover --novelty` is validated; saved tracks are read up to a cap and the cut is
  reported; tracks titled only with a genre word rank lower.
- **Error cases handled:** too few candidates widens the genre set or lowers the novelty and says
  so; everything blocked is reported as a notice, not as a question (section 5, row 21); a failed artist read is a notice with a count.
- **Tests:** `agent/RecommendationEngineTest`, `agent/strategy/StrategyTest`,
  `agent/strategy/StrategySelectorTest`, `agent/CandidateRetrieverTest`,
  `agent/PreferenceSettingsTest`, `controller/AppControllerDiscoverTest`.
- **GUI:** `app/view/DiscoveryView`: "Find music", recommendations with Accept / Reject and
  "Why these?".
- **Live:** `discover` and `discover --novelty 0.9` ran live on Oct 31. That run found 13 of 20
  results came from the user's own history; the exclusion fix is covered by unit tests and is not
  recorded as re-run live. GUI Discover ran read-only through the throwaway script.

### F5 AI Playlist Planning and Generation (UC06, SD01)

- **Flow:** `AppController.createPlaylist(AgentRequest)` -> `MusicAgentFacade.createPlaylist` ->
  `AgentSession.run()` walks `IdleState -> PlanningState -> RetrievingState -> SelectingState ->
  ValidatingState -> ExecutingState -> DoneState` (any error goes to `FailedState`).
  - `PlanningState`: `Planner.plan(request, preferences)` (`PromptBuilder.buildPlanPrompt`,
    `ResponseParser.parsePlaylistSpec/parsePlanExtras`) merges explicit flags over the goal over
    stored preferences, with the mood turned into genres by `agent/MoodGenreMap`
    (`mood-genres.json`).
  - `RetrievingState`: `RecommendationEngine.retrieve`, `GoalFitReviewer.review`,
    `ConstraintChecker.checkFeasibility(spec, pool)` (F11 when infeasible).
  - `SelectingState`: candidates first, then `RecommendationEngine.recommend(Retrieval, ...)` with
    `BalancedStrategy`, then `GoalFitReviewer.reviewPicks`.
  - `ValidatingState`: `PlaylistValidator.validate`, sending a violation back to selection up to
    `plan.maxValidationRetries` (3) times.
  - `ExecutingState`: `PlaylistManager.executeAll` runs `CreatePlaylistCommand` and
    `AddItemsCommand` through `MusicService` (`POST /me/playlists`, `POST /playlists/{id}/items`).
  - `DoneState`: `MemoryManager.saveSession`, `ExplanationService.explain`.
  `AppController.lastSession()`, `setNegotiationHandler`, `setProgressListener` expose the session,
  the F11 choice and the step names (R9).
- **Deterministic or AI:** hybrid. The LLM parses the goal, suggests activity search phrases, words
  the F11 options, plans a few short tags, writes the explanation and may **remove** retrieved tracks
  that do not suit the goal. It never names or adds a track; selection, order and length are computed.
- **Differences:**
  - **Constraint 2 of the implementation plan ("the LLM never selects tracks and never receives bulk
    catalogue data") is restated.** Since Nov 2, `GoalFitReviewer` asks the LLM which of up to
    `goal.reviewMaxTracks` (120) retrieved tracks do not suit the goal; the LLM may only return ids
    to **remove** and never names or adds a track. Since Nov 3 (Spotify Developer Policy, section
    III item 14: no ingesting Spotify Content into an AI model) the prompt carries only each
    track's id and the genres known for it (from the search that found it, the user's profile, or
    cached Last.fm tags). It holds no title, artist name or album name. A track with no
    known genre is not sent and is kept. A test checks that no `PromptBuilder` prompt contains a
    title or artist name. The call comes from `RetrievingState` and `SelectingState`. The reason
    for the review is Sarina's live test, which produced unsuitable songs for a "workout"
    playlist, with no audio features available. It is best effort, says so when skipped, is less
    precise than a title-based review and cannot judge tracks without a genre, and is not yet
    proven to improve playlists on the real account (see `docs/STAGE2_PLAN.md`, section 4).
  - New tracks also come from the goal's activity words (`CandidateRetriever.byKeywords`); a "familiar"
    track is one by an artist the user knows; scores differ by taste (`discover.goalAffinityFloor`);
    stored candidates are optional (`--no-candidates`).
  - **Familiar songs must fit the goal (Nov 4).** When a run has seed genres or search terms,
    `RecommendationEngine.fitsGoal` keeps a familiar-pool track (library, album track or genre result
    by an artist the user knows) only if one of the first `create.fitLeadingGenres` (3) genres of its
    artists is a seed genre, or its id came from a goal source (keyword search or Last.fm tag song),
    or, with Last.fm configured, one of the song's own top tags (`TagService.trackTags`,
    `TrackTagFit`) equals a plan tag or a `lastfm.synonyms.<key>` entry. Too few fits are filled with
    new goal songs and a notice. Discover (F4) is unchanged.
  - **Last.fm tag songs (Nov 4-5; key optional but recommended; without it MusicMind shows no genres or tags and the tag features are off).** The plan carries up to `lastfm.maxTags` short tags
    (`AgentPlan.tags`). `TagService` (implemented by `music/LastFmAdapter`, an Adapter) gives the top
    songs for a tag; `CandidateRetriever.byTag` looks each up on Spotify and keeps it only when the title
    and an artist name both match. Tag songs join the new-music pool with a score bonus. Limits:
    `lastfm.maxTracks` songs per run shared across tags, `lastfm.maxTrackTagLookups` (30) song-tag
    lookups, `lastfm.trackTagBudgetSeconds` (15), a session-only match cache. Without
    `LASTFM_API_KEY` nothing is requested and Create adds a notice. Last.fm gets song titles with
    artist names only to read tags; nothing is stored and nothing goes to OpenAI. Credited in the
    GUI footer and after a CLI Create. A review suggestion to let a song's genre tags count for a
    requested genre was rejected, because it would let Billie Jean back into "slow dance".
  - The token is renewed once per run and the phase repeats; a failed run rolls the new playlist back.
- **Error cases handled:** API outage or 429 (bounded retry), too few eligible tracks (shortfall
  notice, F11), expired token (renew and resume), contradictory constraints (F11), unclear goal
  (`ClarificationNeededException`), no LLM key (says so, changes nothing).
- **Tests:** `controller/CreatePlaylistFlowTest` (`aRequestWalksTheLifecycleAndCreatesThePlaylist`,
  `validationRetriesAreBoundedAndNothingIsCreated`, `anExpiredTokenIsRenewedOnceAndTheRunResumes`,
  `aFailureWhileCreatingRollsBackAndIsReported`, `withoutAnLlmKeyTheRequestSaysSoAndTouchesNothing`),
  `agent/PlannerPlanTest`, `agent/PlannerTest`, `agent/ConstraintCheckerTest`,
  `agent/RecommendationEngineTest`, `agent/GoalFitReviewerTest`, `agent/PromptBuilderTest` (a reflective check that no `build*` prompt holds a title or artist name), `agent/MoodGenreMapTest`,
  `agent/command/PlaylistManagerTest`, `agent/command/PlaylistCommandsTest`, `cli/CliAppTest`.
- **GUI:** `app/view/CreatePlaylistDialog` (goal, hours and minutes, explicit toggle, familiar
  slider hidden while the AI chooses the mix, name, and a choice to start with the songs waiting from
  Find Songs; progress shows the state names; the result is a short summary with "Why these songs?").
- **Live:** `create` ran live on Nov 1 (Stage 1's own example gave 27 tracks, 8 by new artists,
  30%), including a run that used a candidate. Sarina ran Create in the GUI on Nov 2 ("workout", 6
  tracks); it finished, but the songs did not suit the goal. The fixes for that (goal review, activity
  searches, scoring) were written afterwards and **no playlist has been created since**, so goal
  fit is unproven.
  On Nov 4 the owner's further Create runs showed library songs that did not fit ("slow dance" got
  a pop song, "music for studying" got 12 Nirvana songs); the goal-fit rules and Last.fm tags above
  were added in response and are covered by `agent/RecommendationEngineTest`
  (`create_*`), `agent/CandidateRetrieverTest` (`byTag_*`), `agent/TrackTagFitTest`,
  `agent/PlannerPlanTest`, `music/LastFmAdapterTest`, `controller/CreatePlaylistFlowTest`
  and `config/ConfigTest`. A read-only live check with a Last.fm key on Nov 4 (planning, retrieval and selection only, no playlist created) found no off-goal familiar songs for "slow dance" and no Nirvana for "music for studying" (mostly lo-fi and ambient, a few weak tag picks); "workout" still picked several songs by one hip-hop artist, because Last.fm has no tags for them and hip hop is a workout genre. On Nov 6 the owner ran the Last.fm build, including the Nov 5 review fixes, on her real accounts and reported it worked (see `docs/DAILY_TEST_NOTES.md`).

### F6 Natural-Language Playlist Modification (UC07, SD02)

- **Flow:** `AppController.modifyPlaylist(id, instruction)` -> `MusicAgentFacade.modifyPlaylist` ->
  `PlaylistEditor.plan` (`PromptBuilder.buildEditPrompt`, `ResponseParser.forPlaylist(...).parseCommands`)
  -> `ConstraintChecker.checkEdit` (F11 when the edit would break the length limits or add
  violations) -> `PlaylistManager.modify` (snapshot first, then one undoable `BatchCommand` of
  `AddItemsCommand`, `RemoveTrackCommand`, `ReplaceTrackCommand`, `ReorderCommand`) ->
  `PlaylistValidator`. `AppController.undo(playlistId)` -> `PlaylistManager.undo`.
  `getPlaylists/getPlaylist` list and open playlists.
- **Deterministic or AI:** hybrid. The LLM turns the instruction into steps; the tracks to add come
  from Spotify search, never from the LLM; the commands are deterministic. A feeling-only edit
  ("calmer") uses the genre heuristic and is flagged "Approximate".
- **Differences:** undo works across CLI runs through snapshots (R10, R11, R12); the playlist's target
  length moves with a stated length change; edits are re-planned when they add violations; the
  playlist list (`playlists`, `show`) was added.
- **Error cases handled:** mood edit without a reliable signal (approximate), invalid playlist or
  missing permission (short message), ambiguous instruction (a question, nothing changed), a failed
  step (earlier steps are undone), database down (the change is refused).
- **Tests:** `controller/ModifyPlaylistFlowTest` (`modifyRemovesTheTracksTheInstructionNamesSnapshotsFirstAndSummarisesTheChange`,
  `undoRevertsTheLastChangeInThisRunAndThenInANewRunFromTheSnapshot`,
  `aMoodEditWithNoReliableSignalIsFlaggedApproximate`, `anAmbiguousInstructionChangesNothingAndSavesNoSnapshot`),
  `agent/PlaylistEditorTest`, `agent/ResponseParserCommandsTest`,
  `agent/command/PlaylistCommandsTest`, `agent/command/PlaylistManagerTest`, `cli/CliAppTest`.
- **GUI:** `app/view/PlaylistView`: a playlist list, tracks, a Modify box and an Undo button.
- **Live:** `modify` and `undo` ran live on Nov 1 ("make it 15 minutes shorter" removed 3 tracks and
  `undo` put them back); `modify` refused a blocked track. Sarina ran Modify in the GUI on Nov 2
  (6 tracks became 5); whether the songs matched the instruction was not judged. Undo in the GUI was not
  reported as tested.

### F7 Playlist Constraint Validation (UC08)

- **Flow:** `AppController.validatePlaylist(id)` -> `MusicAgentFacade.validatePlaylist` ->
  `PlaylistValidator.validate(tracks, spec)` -> `domain/ValidationResult` (pass or fail overall,
  a sentence per violated rule, `offending()` ids per violated rule). Rules: explicit content, duplicates by id, target duration within tolerance,
  blocked artists, blocked tracks, minimum and maximum length (`playlist.minTracks/maxTracks`).
- **Deterministic or AI:** deterministic. No LLM.
- **Differences:** blocked tracks were added (Stage 1 lists "artists/tracks" but only artists
  existed); a playlist is judged against the blocks in force now, not those saved at creation, and
  against the newest recorded target length; the note says which rules were used. `ValidationResult`
  is one overall flag plus `offending()` for the violated rules only; a passing rule has no entry
  (section 5, row 22).
- **Tests:** `agent/PlaylistValidatorTest` (13), `controller/ModifyPlaylistFlowTest`
  (`validateUsesTheSpecSavedWithTheCreatingSessionAndListsOffendingTracks`,
  `validateAppliesATrackBlockedAfterThePlaylistWasCreated`), `app/view/ViewFormatTest` (badges),
  `cli/CliAppTest`.
- **GUI:** `app/view/PlaylistView` shows a badge such as "Meets every constraint", "2 explicit tracks" or
  "over target by 6 min" (`ViewFormat.badges`).
- **Live:** `validate` ran live on Nov 1; a blocked track made it fail.

### F8 Preference Memory (UC09)

- **Flow:** `AppController.parsePreference/setPreference/getPreferences/displayPreference` ->
  `agent/PreferenceSettings` -> `MemoryManager.savePreference/loadPreferences` ->
  `memory/repository/PreferenceRepository` (PostgreSQL). Keys: `blockedArtists`, `blockedTracks`,
  `explicitPolicy`, `familiarBias`, `preferredStyles`. Read by F4, F5, F6.
- **Deterministic or AI:** deterministic.
- **Differences:** artists are typed by name and stored as Spotify ids (names are read for display and
  never stored); `findArtists`, `findTracks`, `blockArtist(id)` and `blockTrack(id)` let a caller
  block what the user chose; `savedFamiliarBias()` reads the saved mix. Blocking is refused when the
  stored preferences cannot be read, so existing blocks are never overwritten.
- **Error cases handled:** database down gives safe defaults and a warning; values are validated
  (`familiarBias` must be between 0 and 1).
- **Tests:** `agent/PreferenceSettingsTest`, `memory/repository/PreferenceRepositoryTest`,
  `memory/MemoryManagerTest`, `controller/AppControllerDiscoverTest`
  (`preferencesRoundTripAndAreValidated`, `withTheDatabaseDownEverythingStillRunsWithSafeDefaultsAndAWarning`),
  `controller/AppControllerTest` (`blockArtist_*`, `findArtists_*`), `tool/MemoryToolTest`, `cli/CliAppTest`.
- **GUI:** `app/view/PreferencesView` (the Your Rules screen): an Allow / Block choice for explicit
  songs, the mix slider, and Find / choose / Block pickers for artists and songs, each section with
  its own Save (`docs/GUI_TEST_FINDINGS.md`, items 20 to 22, done Nov 4). The Save buttons were not
  pressed live.
- **Live:** `prefs set blockedArtists "<name>"` was run live on Nov 1 (it stored the id, listed the
  name, made `validate` fail, and refused an unknown name). The other keys are not recorded as run
  live in `docs/DAILY_TEST_NOTES.md`.

### F9 Recommendation Feedback (UC10, SD04)

- **Flow:** `AppController.recordFeedback(trackId, verdict)` (checks the id with `requireTrackId`) ->
  `MemoryManager.saveFeedback` -> `memory/repository/FeedbackRepository` (upsert by user and
  track). `RecommendationEngine.score` boosts accepted tracks and penalises rejected ones.
- **Deterministic or AI:** deterministic.
- **Differences:** the track id is validated (22 letters or digits, or a `spotify:track:` URI) before
  anything is saved.
- **Tests:** `memory/repository/FeedbackRepositoryTest`, `controller/AppControllerDiscoverTest`
  (`feedbackIsStoredOncePerTrackAndChangesLaterRecommendations`,
  `feedbackRejectsAnIdThatIsNotATrackIdAndSavesNothing`), `agent/RecommendationEngineTest`,
  `cli/CliAppTest`.
- **GUI:** like and not-for-me icons on every `app/view/TrackRow` (the chosen one stays highlighted;
  the older `FeedbackBar` was removed in the redesign).
- **Live:** `feedback` with a bad id was run on the real script (status 2, nothing saved). A
  successful verdict is not recorded as run live, and the GUI buttons were not tested by a person.

### F10 Grounded Recommendation Explanation (UC11)

- **Flow:** `AppController.explain(sessionOrPlaylistId)` -> `MusicAgentFacade.explain(session)` ->
  `ExplanationService.explain(session[, titles])` -> `PromptBuilder.buildExplainPrompt(DecisionTrace)`
  -> `LLMProvider.complete` -> `ResponseParser.verifyExplanation` rejects text that adds numbers,
  quoted names, genres or preference claims the trace does not record. A rejected or missing LLM
  text is replaced by a computed sentence labelled as such; a missing trace gives a general
  note. A playlist id with no stored session gives `MusicAgentFacade.explainUnrecorded`: a note that
  MusicMind did not create the playlist, its song count and minutes, and how many songs are by artists
  in the saved profile (no LLM, no titles; since Nov 6, commit 905213c).
- **Deterministic or AI:** AI, with a checked fallback.
- **Differences:** titles are shown from the run (display only, never stored); the trace also
  records the genres the goal asked for; the prompt forbids saying that a track fits a mood unless
  its own reason says so. The check is `verifyExplanation`, not Stage 1's `verifyTracks()`. An
  explanation covers the run that created the playlist, not later edits (section 5, row 20).
- **Tests:** `agent/ExplanationServiceTest`, `agent/ResponseParserTest`, `agent/PromptBuilderTest`,
  `agent/PromptRoundTripTest`, `controller/ModifyPlaylistFlowTest`
  (`explainReadsTheStoredTraceByPlaylistIdOrSessionIdEvenInANewRun`,
  `explainWithoutATraceGivesTheGeneralExplanationAndAnUnknownIdIsReported`,
  `explainForAPlaylistWithNoStoredSessionGivesTheLimitationNoteAndAGeneralDescription`,
  `modifyIsRecordedInTheHistoryButDoesNotReplaceTheCreatingSession`), `controller/AppControllerTest`
  (`explainOfAPlaylistWithNoSessionCountsSongsByArtistsInTheSavedProfile`),
  `controller/CreatePlaylistFlowTest` (`theStoredTraceLetsALaterRunExplainThePlaylistFromRecordedFactsOnly`).
- **GUI:** `app/view/WhyExpander`, labelled "Why these?", on playlists and recommendations.
- **Live:** `explain` ran live on Nov 1.

### F11 Infeasible-Constraint Negotiation (UC12)

- **Flow:** `ConstraintChecker.checkFeasibility(spec, pool)` (create) and `checkEdit(...)` (modify)
  -> `domain/FeasibilityReport` with `Relaxation`s -> `Planner.phraseOptions` (the LLM words the
  options, used only if it returns one sentence per option, else computed wording) ->
  `NegotiationHandler.choose` (CLI prompt or `app/view/NegotiationDialog`) -> `Planner.regenerate`
  (create) or a partial edit (`APPLY_PARTIAL`, modify). Cancelling writes nothing.
- **Deterministic or AI:** hybrid. The feasibility check is computed; the LLM only words the options.
- **Differences:** none from Stage 1 other than the extra relaxation kinds (shorten, change the
  share, allow explicit, apply only part of an edit).
- **Tests:** `agent/ConstraintCheckerTest`, `agent/PlannerPlanTest`, `controller/CreatePlaylistFlowTest`
  (`anInfeasibleRequestIsNegotiatedRelaxedAndRegenerated`, `cancellingTheNegotiationChangesNothingOnSpotify`,
  `withoutAHandlerAnInfeasibleRequestStopsAndListsTheOptions`, `whenNoRelaxationCanHelpTheAgentExplainsAndStops`),
  `controller/ModifyPlaylistFlowTest` (`anEditThatWouldEmptyThePlaylistOffersAPartialEditAndAppliesTheChosenOne`,
  `cancellingTheModifyNegotiationMakesNoSpotifyWritesAndNoSnapshot`), `cli/CliAppTest`.
- **GUI:** `app/view/NegotiationDialog`, shown on the worker's request through a future the
  JavaFX thread completes.
- **Live:** **not run live.** `docs/DAILY_TEST_NOTES.md` lists the negotiation prompt as still to
  check. The GUI dialog was shown with stand-in text only.

### F12 Agent History and Snapshot/Restore (UC13, SD02)

- **Flow:** `AppController.getHistory(user)`, `getSession(id)`, `getSnapshots(user)`,
  `restore(snapshotId)`, `recreateFromSnapshot(snapshotId)` -> `MemoryManager.loadHistory/loadSnapshot/saveSnapshot`
  -> `SessionRepository`, `PlaylistHistoryRepository` -> `PlaylistManager.restoreFrom`
  (`RestoreSnapshotCommand`). Snapshots hold ids and the playlist name only (R14). A restore
  saves the current content first so it can be undone; a snapshot of a playlist that no longer exists on Spotify offers to
  recreate it.
- **Deterministic or AI:** deterministic.
- **Differences:** `history <id>` opens one session with its plan and result; a restore of an
  unchanged playlist says nothing changed.
- **Tests:** `memory/repository/SessionRepositoryTest`, `memory/repository/PlaylistHistoryRepositoryTest`,
  `memory/MemoryManagerTest`, `agent/state/SessionRecordTest`, `agent/command/PlaylistManagerTest`,
  `controller/ModifyPlaylistFlowTest` (`historyListsSessionsNewestFirstAndSnapshotsAreListedWithTheirIds`,
  `restoreReplaysTheSnapshotAndCanItselfBeUndone`,
  `restoreOfADeletedPlaylistOffersToRecreateItFromTheSnapshot`), `controller/AppControllerTest`
  (`delete_canBeRecreatedFromSnapshot`), `cli/CliAppTest`.
- **GUI:** `app/view/HistoryView`: sessions with plan and result, saved versions, Restore (and a
  recreate offer).
- **Live:** `history` and `history <sessionId|playlistId>` ran live (Nov 1). `restore` is listed as
  still to check live in `docs/DAILY_TEST_NOTES.md`; the AI log (Nov 1) says it was tried on a test
  playlist, and recreating a deleted playlist is not recorded as run live. Restore through the GUI was not
  reported as tested.

### New: delete a playlist

- **Flow:** `AppController.deletePlaylist(id)` -> `MusicAgentFacade.deletePlaylist` (refuses a
  playlist that is not in the user's own list) -> `PlaylistManager.delete` (snapshot first) ->
  `DeletePlaylistCommand` -> `MusicService.unfollowPlaylist`. CLI `delete <playlistId>` asks for the
  playlist name to be typed. The way back is F12 (`restore` offers to recreate).
- **Why:** Stage 1 has no delete, and in user testing a playlist could not be removed from MusicMind.
- **Tests:** `controller/AppControllerTest` (`delete_notOwner_refused`,
  `delete_snapshotCannotBeSaved_playlistIsKept`, `delete_canBeRecreatedFromSnapshot`),
  `agent/command/PlaylistCommandsTest`, `agent/command/PlaylistManagerTest`, `cli/CliAppTest`.
- **GUI:** a Delete button on each playlist row (`app/view/PlaylistView`) with an in-app confirm
  dialog (`ConfirmDialog`) that names the playlist (Nov 4). Not run live: it would change the real account.
- **Live:** not run live.

## 3. Stage 1 names and signatures

Every class and member named in the Stage 1 class diagram and traceability table exists with its
name (checked by search over `src/main`). The differences in shape are:

- `ProfileBuilder.build(topItems, saved)` takes the new `TopItems` value (R2).
- `Repository<T>` methods are as in Stage 1; the owning user comes from a `Supplier<String>`
  given to the repository (R3).
- `AbstractRecommendationStrategy` gains `bindProfile(profile)` (R7).
- `ResponseParser.parseCommands(json)` has the Stage 1 signature on a parser bound to a playlist
  with `forPlaylist(PlaylistContext)`.
- `PlaylistManager.undo(playlistId)` returns the number of changes it went back over (Stage 1: no
  result), and `PlaylistManager` also has `modify`, `restoreFrom`, `delete` and `snapshotBeforeModify`.
- `AppController` and `MusicAgentFacade` have the Stage 1 methods plus `login`, `currentUser`,
  `lastProfile`, `lastSession` and the other accessors listed in `docs/STAGE2_PLAN.md`.
- `Planner.parseGoal(text)` stays the plan parser; `parseSearchQuery(text)` is new (R6).
- `AgentRequest` gained `constraints` (R8) and `useCandidates`.
- `LLMProvider` has `complete(prompt)` only; Stage 1's `parse()` does not exist (row 18).
  `ResponseParser.verifyTracks` exists but nothing calls it (row 17).
- `AgentSession` has `planSteps` (strings) instead of an `AgentPlan` (row 23); `ValidationResult`
  lists violated rules only (row 22).
- `DecisionTrace` gained `requestedGenres`; `TrendReport` and `ListeningProfile` gained fields
  for coverage and the saved-track cap.

## 4. Design patterns

The five patterns that count for the course are Adapter, Strategy, Command, State and Facade, as
in Stage 1. Repository and Decorator are used as well and are not counted.

| Pattern | Problem it solves | Participants | Matches Stage 1? |
|---|---|---|---|
| Adapter | The core must not depend on the Spotify, Last.fm or OpenAI wire formats. | `MusicService` / `SpotifyAdapter` (HTTP, PKCE through `OAuthTokenStore`); `GenreService` and `TagService` / `LastFmAdapter`; `LLMProvider` / `OpenAiProvider` | Yes. The Last.fm adapter is new: genres for every feature (R18, MusicBrainz until Nov 5) and tags for Find Songs, Create, Discover and Modify. `TagTrackMatcher` and `SongTagReader` support it. |
| Strategy | F4 and F5 need different mixes of familiar and new music, chosen at run time. | `RecommendationStrategy`, `AbstractRecommendationStrategy`, `FamiliarStrategy`, `DiscoveryStrategy`, `BalancedStrategy`; context `RecommendationEngine.setStrategy`; chosen by `StrategySelector` | Yes. `bindProfile` added (R7). Create always uses `BalancedStrategy`; only Discover chooses from the request (row 24). |
| Command | F6 and F12 need reversible, composable playlist changes. | `PlaylistCommand` (`execute`, `undo`), `CreatePlaylistCommand`, `AddItemsCommand`, `RemoveTrackCommand`, `ReplaceTrackCommand`, `ReorderCommand`, plus `BatchCommand`, `RestoreSnapshotCommand`, `DeletePlaylistCommand`; invoker `PlaylistManager` with its `history` Deque | Yes. Three commands added; undo also works from snapshots across runs (R10). |
| State | A create run moves through ordered phases with a different action and failure handling in each. | `SessionState` (`handle`, `name`), `IdleState`, `PlanningState`, `RetrievingState`, `SelectingState`, `ValidatingState`, `ExecutingState`, `DoneState`, `FailedState`; context `AgentSession` (`setState`, `getState`, `run`) | Yes. Shared run data lives in `RunContext` and `RunServices`. `AgentSession.run()` calls `handle()` in a loop and `fail()` sets `FailedState` (row 23). |
| Facade | The GUI and CLI need one simple entry point over many collaborators. | `MusicAgentFacade` behind `AppController` | Yes. Some Spotify reads go from the facade to `MusicService` directly, not through `ToolManager` (row 25). |
| Repository | Persistence details stay out of the agent. | `Repository<T>`, `PreferenceRepository`, `FeedbackRepository`, `SessionRepository`, `PlaylistHistoryRepository`, `CandidateRepository`, behind `MemoryManager`; `GenreRepository` caches genres | Yes. `CandidateRepository` and `GenreRepository` are new. |
| Decorator | Retry and genre lookup wrap the music service without changing it. | `RetryingMusicService` (`delegate`, `withBackoff`), `GenreEnrichingMusicService` over `MusicService` | `GenreEnrichingMusicService` is new (R18). `CachingMusicService`, optional in Stage 1, was not built (row 26). |

## 5. Differences from Stage 1

| # | Difference | Reason | Where |
|---|---|---|---|
| 1 | R1 to R17 additive members (see section 2 of the plan) | Stage 1 left types or behaviour unspecified | `STAGE2_PLAN.md` section 2 |
| 2 | R18: genres from Last.fm (MusicBrainz until Nov 5) | Spotify returns no genres to this app | section 2 |
| 3 | R19: genre sent as a plain keyword | `genre:` filter returned 0 to 1 tracks in live tests | section 2 |
| 4 | R20: supporting classes (section 6 below) | Keep Stage 1 classes focused | this file |
| 5 | LLM may remove retrieved tracks; it sees ids and Last.fm genres only, no titles or names | Unsuitable workout songs in live testing; no audio features; Spotify policy | `STAGE2_PLAN.md` section 4 |
| 6 | F4 never calls the LLM | Stage 1 made it optional; computed reasons are enough | `STAGE2_PLAN.md` section 3 |
| 7 | Stored candidates, optional per run | Candidates forced into a workout playlist | `STAGE2_PLAN.md` section 4 |
| 8 | Blocks in force now judge every playlist | A block stands for the user's current wishes | section 6 of the plan |
| 9 | Target length follows edits | The old target is wrong after a deliberate length change | section 5 of the plan |
| 10 | Delete a playlist | Not in Stage 1; needed in user testing | section 5 of the plan |
| 11 | PostgreSQL tables created on start; tests use embedded PostgreSQL | Build must run with no server | section 1 of the plan |
| 12 | Gradle 9.1, JavaFX 21.0.13, run scripts (R15) | Portability and a macOS 26 crash | section 1 of the plan |
| 13 | GUI redesign: dark theme, left-hand menu, plain names (Your Taste, Find Songs, Your Rules), shared song row, confirm dialog, Delete in the GUI | The owner's test of Nov 2 found the GUI hard to understand | section 7 of the plan |
| 14 | F1 Past week range from recent plays; left out of Trends | Asked for twice; Spotify has no weekly top items | section 3 of the plan |
| 15 | F5: familiar songs must fit the goal (leading genres, the song's own Last.fm tags); Last.fm tag songs matched to Spotify (key optional but recommended; without it MusicMind shows no genres or tags and the tag features are off) | Live Create runs gave songs that did not fit | section 4 of the plan |
| 16 | Settings and wording in `musicmind.properties` (user-visible app text since Nov 6; developer-facing exception text and prompts stay in code) | Stage 2 rejects hard-coded demonstration content | section 1 of the plan |
| 17 | `ResponseParser.verifyTracks` is on no live path | The model names no track: search uses Spotify's own tracks, Create selects deterministically, Modify binds commands to listed positions and ids. Kept as the guard for any future path where the model names ids | section 4 of the plan |
| 18 | `LLMProvider` has `complete(prompt)` only, no `parse()` | Stage 1 lists both; parsing a reply is `ResponseParser`'s job | section 4 of the plan |
| 19 | The LLM is called by `Planner`, `ExplanationService`, `GoalFitReviewer`, `PlaylistEditor` and the facade (F1 summary, F2 narrative), not two components | Each is a language task; none receives a Spotify title or name | section 4 of the plan |
| 20 | F10: a playlist with no stored session gets a general description and a note; an explanation covers the creating run only; the check is `verifyExplanation` | Stage 1 alternative case; later edits record no new ranking | section 4 of the plan |
| 21 | F4: every candidate blocked gives a notice, not a question | `discover` is one read; the notice names the setting to change | section 3 of the plan |
| 22 | F7: `ValidationResult` lists violated rules only | Callers need whether anything failed and what to name | section 5 of the plan |
| 23 | `AgentSession` stores `planSteps`, not an `AgentPlan`; `run()` drives the states and `fail()` sets `FailedState` | The saved session is the record; one place for error handling | section 8 of the plan |
| 24 | Create always uses `BalancedStrategy`; Discover chooses a strategy from the request | Stage 1 names Balanced for playlists | section 8 of the plan |
| 25 | The facade reads some Spotify data through `MusicService` directly, not `ToolManager` | Single user-triggered reads; still behind the Adapter | section 8 of the plan |
| 26 | No `CachingMusicService` (optional in Stage 1) | Last.fm genres are cached by artist id and session caches cover the rest | section 8 of the plan |
| 27 | Search ranks typed words first; weights 0.9 / 0.1 | Live results put stock-music titles first | sections 3 and 6 of the plan |
| 28 | GUI has no automated UI tests; F5 progress is a status line; F2 is one table | See the plan | section 7 of the plan |

The GUI is unchanged in scope from Stage 1 and is now the dark redesign of Nov 4 (row 13); see
section 7 of the plan. Rows 17 to 26 and 28 were added on Nov 6 after the alignment audit; the reasons are
written out as Original, Change, Reason, Impact in the sections of the plan named in the last column.

## 6. Classes beyond Stage 1 (R20)

Each is small and exists so that a Stage 1 class keeps one job. Full entries are in
`docs/STAGE2_PLAN.md`.

- **Agent:** `CandidatePool` (familiar and novel tracks for the feasibility check), `Retrieval`
  (one Spotify read shared by every re-selection), `CandidateRetriever` (reads pools from Spotify),
  `GoalFitReviewer` (the removal-only review), `TagTrackMatcher` (Last.fm song to Spotify track), `SongTagReader` (tags for Modify's prompt), `TrackGenres` (the genres MusicMind already knows for a
  track, so the review needs no title) and `ArtistGenreCache` (read-only view of cached Last.fm tags), `MoodGenreMap` (curated mood to genre data),
  `SearchRanker`, `TrendComparator`, `PreferenceSettings` (preferences as F4 and F5 use them),
  `PlaylistEditor` (edit instruction to commands), `PlaylistContext`, `CommandSpec` (an edit
  before it is bound to real tracks), `NegotiationHandler` (the F11 choice), and the exceptions
  `ClarificationNeededException`, `InfeasibleRequestException`, `NegotiationCancelledException`.
- **Command:** `BatchCommand` (one undo step per edit), `RestoreSnapshotCommand`,
  `DeletePlaylistCommand`, `PlaylistOps` (position helpers), `PlaylistChangeException`,
  `PlaylistMissingException`.
- **State:** `RunContext`, `RunServices`, `Negotiator` (one F11 round), `SessionRecord` (derived
  data stored with a session).
- **Strategy:** `StrategySelector`.
- **Controller:** `PlaylistTargets` (a playlist's target length over time).
- **Domain values:** `TopItems`, `TrendReport`, `RangeChange`, `SearchQuery`, `FeasibilityReport`,
  `Relaxation`, `DecisionTrace`, `PlaylistSummary`, `SnapshotInfo`.
- **Music:** `GenreEnrichingMusicService`, `GenreService`, `TagService`,
  `LastFmAdapter`, `TrackList` (a
  list that knows it was cut short), `MusicServiceException`, `AuthExpiredException`,
  `RateLimitException`.
- **Memory and tools:** `Database`, `PersistenceException`, `CandidateRepository`,
  `GenreRepository`, `ToolResult`, `LLMException`, `ParseException`.
- **Settings:** `config/Config`.
- **GUI:** `GuiContext` (one background worker, login, the F11 handler), `ControlLock`,
  `ViewFormat` (plain text and numbers, testable without JavaFX), `CreatePlaylistDialog`,
  `NegotiationDialog`, `ConfirmDialog`, `TrackRow` (the shared song row), `WhyExpander`, `LoginGate`,
  `StatusLine`, `TabView`, `UiParts`, and the views `TrendsView`, `SearchView`, `PreferencesView`, `HistoryView`.

## 7. Not verified, or known to be weak

- No automated tests drive the GUI views. `ViewFormatTest`, `ControlLockTest` and the controller tests
  cover what a view shows and calls; the screens were checked by hand (section 5, row 28).
- Goal fit of created playlists with the Nov 4-5 rules (leading genres, Last.fm song tags): not
  judged live with a Last.fm key yet.
- F11 against a real conflict, `delete`, recreate from a deleted playlist, and the GUI's Undo,
  Restore (confirming), like / not-for-me, Save and candidate buttons pressed by a person; the
  Delete button; the Past week range on the real account.
- Searches by description, and genre data for artists Last.fm does not know.
- The updated UML diagrams are in `docs/diagrams/` (`.uxf` sources, PNGs in `docs/diagrams/png/`, the Stage 1 PNGs in `docs/diagrams/png/stage1/`; the README there lists what changed). The Stage 2 report is `docs/report/stage2-report.pdf` (source `stage2-report.html`).
- The GUI items in `docs/GUI_TEST_FINDINGS.md` are done except the goal review on Find Songs
  results (searches by description). Screenshots of the redesign will be retaken after the owner's
  test; the UML in `docs/diagrams/` shows the Last.fm classes but does not show `TrackTagFit`,
  `TrackRow` or `ConfirmDialog`.
- **Open policy question (F6 edit prompt).** The edit prompt sends the model each track's id, explicit
  flag and length in minutes (Spotify metadata, not titles or names), because Stage 1 F6 needs
  them for edits such as "15 minutes shorter" or "remove explicit". Whether this counts as Spotify
  Content under the Developer Policy is unresolved and is for Sarina to decide.
