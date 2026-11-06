# Last.fm Tags Everywhere Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Last.fm tags replace MusicBrainz as MusicMind's only source of artist genres, and tags also drive Find Songs, Discover and Modify.

**Architecture:** `LastFmAdapter` implements `GenreService` with `artist.gettoptags`, behind the existing `GenreEnrichingMusicService` Decorator, so every feature that reads genres switches at once. A shared `TagTrackMatcher` turns Last.fm songs into real Spotify tracks for Create, Find Songs and Discover. Modify's edit prompt gets per-song tags from a capped `SongTagReader`.

**Tech Stack:** Java 17, `java.net.http`, Gson, JUnit 5 with a local `HttpServer` fake (as `LastFmAdapterTest`), PostgreSQL via JDBC.

**Spec:** `docs/superpowers/specs/2026-11-05-lastfm-tags-everywhere-design.md`

## Global Constraints

- Work in a git worktree `../MusicMind-lastfm` on branch `stage2-lastfm`, created from `stage2-gui`. Never push, never switch branches in the main checkout, stage files by name. Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- **No app wording changes**, apart from removing the MusicBrainz data credit. "Top genres", "genres" and all other screen and CLI text stay as they are.
- Spotify: search `limit` ≤ 10, no batch endpoints. Extra Spotify searches per action: Find Songs ≤ `lastfm.findSongsMaxMatches=10`, Discover ≤ `lastfm.discoverMaxMatches=10`. Stop matching at the first `RateLimitException` or `AuthExpiredException`.
- Never send Spotify titles, artist names or albums to OpenAI. Artist names and song titles may go to Last.fm and are never stored. Last.fm tags may be cached in the database and sent to OpenAI. `PromptBuilderTest`'s reflective guard must stay green.
- `LASTFM_API_KEY` is optional. With no key, nothing calls Last.fm, genres are unknown, and the GUI shows one banner and the CLI prints one line (`text.lastfmOff`-style wording, new key `text.tagsOffBanner`).
- Tunables and wording go in `musicmind.properties`, read through `Config`. No keys in code, tests or docs.
- Design patterns stay evident: `LastFmAdapter` is the Adapter (for `GenreService` and `TagService`), and `GenreEnrichingMusicService` is the Decorator.
- TDD. Before reporting each task, run `./gradlew build --rerun-tasks --no-build-cache` (1013 tests at the start).

## Review Focus

- An artist whose Last.fm tags are all junk ("seen live", "favorites", its own name, "2010") gets an empty genre list, which is cached as a real answer, and shows as unknown. Pinned in Task 1.
- Last.fm fails part-way through (HTTP 500 or a timeout): those artists are not cached, the cooldown starts after `lastfm.maxConsecutiveFailures`, and the screen still loads. Pinned in Task 1.
- A playlist longer than 30 songs in Modify: songs beyond the cap get artist tags, the prompt says so, and nothing is looked up twice. Pinned in Task 5.
- Spotify rate-limits during tag matching: matching stops, the results so far are kept, and one read-failed notice is shown. Pinned in Task 3.
- A database written by the old version still holds MusicBrainz rows: after start-up none of them is ever read. Pinned in Task 2.

---

### Task 1: Last.fm artist tags replace MusicBrainz genres

**Files:**
- Modify: `music/LastFmAdapter.java`, `music/GenreService.java` (Javadoc), `music/GenreEnrichingMusicService.java` (cache life from `lastfmCacheDays`), `config/Config.java`, `controller/AppController.java:204-205`, `app/GuiApp.java:190-193`, `cli/CliApp.java:948,986`, `musicmind.properties`, `.env.example` comment, and comments that name MusicBrainz as the source (`agent/ArtistGenreCache`, `TrackGenres`, `GoalFitReviewer`, `PromptBuilder`, `ProfileBuilder`, `Retrieval`, `RecommendationEngine`, `domain/TrendReport`, `MusicAgentFacade`)
- Delete: `music/MusicBrainzAdapter.java`, its test, the `musicbrainz.*` properties and the `Config.musicBrainz*` getters, `text.genreAttribution` and `Config.genreAttribution()`
- Test: `music/LastFmAdapterTest.java`, `config/ConfigTest.java`, `cli/CliAppTest.java`

**Interfaces:**
- Produces `LastFmAdapter implements GenreService, TagService` with `Optional<List<String>> lookup(Artist artist)`. It calls `artist.gettoptags&artist=<name>&autocorrect=1`. It returns `Optional.of(tags)` for a real answer, including empty and Last.fm error 6 (artist not found). It returns `Optional.empty()` on HTTP or parse failure, during cooldown, or when no key is set.
- Tag filter: drop tags with `count < lastfm.minTagCount` (10). Drop tags in `lastfm.tagBlocklist` (comma list: `seen live,favorites,favourites,favorite,favourite,albums i own,my top,love,awesome,beautiful,best,under 2000 listeners`). Drop tags equal to the artist's name or matching `^\d{2,4}s?$`. Lower-case and strip, keep Last.fm's order, at most `lastfm.artistTagsKept` (10), no repeats.
- `Config`: `lastfmMinTagCount()`, `lastfmTagBlocklist(): Set<String>`, `lastfmArtistTagsKept()`, `lastfmCacheDays()` (30), `lastfmMaxConsecutiveFailures()` (3), `lastfmCooldownSeconds()` (300). Wording `text.tagsOffBanner=Song and artist tags from Last.fm are off (no LASTFM_API_KEY), so genres are unknown.`
- The GUI footer shows Spotify and Last.fm credits only. The CLI prints `lastfmAttribution()` where it printed `genreAttribution()`.

- [ ] **Step 1: Write the failing tests** in `LastFmAdapterTest`:
  - `artistTags_parsedFilteredAndCapped`: fake reply with tags `indie pop`(100), `seen live`(90), `Radiohead`(80), `2010s`(70), `chill`(60), `rare`(5) for artist "Radiohead" → `Optional.of(List.of("indie pop", "chill"))`.
  - `artistTags_allJunk_presentEmpty`: only junk tags → `Optional.of(List.of())`.
  - `artistTags_error6_presentEmpty`.
  - `artistTags_http500_emptyOptional_andCooldownAfterThree`: three 500s → three `Optional.empty()`, then a fourth call makes no request.
  - `artistTags_noKey_emptyOptionalWithoutCalling`.
  - `ConfigTest.noMusicBrainzKeysLeft`: none of the tunables' keys starts with `musicbrainz.`.
  - `CliAppTest`: the profile output contains the Last.fm credit and not "MusicBrainz".
- [ ] **Step 2: Run them and see them fail:** `./gradlew test --tests '*LastFmAdapterTest*' --tests '*ConfigTest*' --tests '*CliAppTest*'`.
- [ ] **Step 3: Implement** the interfaces above. Delete `MusicBrainzAdapter` and its test. Wire `new LastFmAdapter(config)` as both the `GenreService` and the `TagService` in `AppController` (one instance). Add the no-key banner in `GuiApp` next to the existing "no LLM key" banner, and the CLI line once per run.
- [ ] **Step 4: Run the full build:** `./gradlew build --rerun-tasks --no-build-cache` → green.
- [ ] **Step 5: Commit:** "Last.fm artist tags replace MusicBrainz genres behind GenreService".

### Task 2: Cache only Last.fm tags

**Files:** `memory/Database.java:120-125`, `memory/repository/GenreRepository.java`. Test: `memory/repository/GenreRepositoryTest` (create it, or extend the existing database test that uses the test database. Check how the other repository tests run).

**Interfaces:**
- Schema: `ALTER TABLE artist_genre ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'musicbrainz'`, then `DELETE FROM artist_genre WHERE source <> 'lastfm'`, run at every start-up (cheap once empty).
- `GenreRepository.save(artistId, genres, fetchedAt)` writes `source='lastfm'`. `find(artistId, freshAfter)` reads only `source='lastfm'` rows.

- [ ] **Step 1: Failing test** `oldMusicBrainzRowsAreNeverRead`: insert a row with the old three columns (no source), run the schema setup, then `find` returns empty. `saveThenFind_roundTripsLastfmTags`.
- [ ] **Step 2: Run it and see it fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: `./gradlew build --rerun-tasks --no-build-cache`** → green. If the repository tests need PostgreSQL and none is available, they are skipped the way the existing ones are. Say so in the report.
- [ ] **Step 5: Commit:** "Genre cache keeps only Last.fm tags; old MusicBrainz rows are removed".

### Task 3: Shared Last.fm-to-Spotify matcher, and Find Songs by tag

**Files:** create `agent/TagTrackMatcher.java`. Modify `agent/CandidateRetriever.java` (byTag uses it), `agent/RecommendationEngine.java` (owns the one session match cache and hands it out), `controller/MusicAgentFacade.java` (`searchMusic`), `config/Config.java`, `musicmind.properties`. Tests: `TagTrackMatcherTest`, `CandidateRetrieverTest` (unchanged results), `MusicAgentFacadeTest` (search).

**Interfaces:**
- `final class TagTrackMatcher` with constructor `(ToolManager tools, Map<String, Optional<Track>> sessionCache)`. Method `Match match(List<TagTrack> wanted, int maxSearches)` returns `record Match(List<Track> tracks, List<String> errors, boolean stopped)`. It moves the existing query, the `matches()` rule and the cache logic out of `CandidateRetriever.lookup/matches`, unchanged. It stops at the first rate-limit or auth error (`stopped=true`). A cache hit costs no search but still counts toward `maxSearches`, as today.
- `RecommendationEngine.matchCache()`, package-private, returns the existing session cache so the facade's search shares it. The facade gets it through its `create` support object; add an accessor there.
- `searchMusic`: when the parsed query has a mood or genre (`parsed.genre()` non-blank, or a typed word with `moods.genresFor(word)` non-empty), take the tag `parsed.genre()`, else the first mood word. Call `TagService.topTracks(tag, lastfm.findSongsMaxMatches)` and match the songs with `TagTrackMatcher`. Matched tracks go first in the ranked list, de-duplicated by id, then today's results. With no key or a Last.fm failure, today's behaviour is unchanged, and a failure adds one sentence to `lastSearchNotice`.
- `Config.lastfmFindSongsMaxMatches()` (10).
- `MoodGenreMap` stays the bridge from a mood word to tag names. Check its resource file and replace any value that is not a common Last.fm tag with the nearest one, for example "r&b" → "rnb". Note each change in the commit message.

- [ ] **Step 1: Failing tests:**
  - `TagTrackMatcherTest.matchesTitleAndArtist_dropsWrongArtist` (moved from `CandidateRetrieverTest`).
  - `TagTrackMatcherTest.stopsAtRateLimit_keepsEarlierMatches`: the 2nd search throws `RateLimitException`, so the 3rd is never asked, `stopped` is true and the first match is kept.
  - `TagTrackMatcherTest.cacheHitMakesNoSearch`.
  - `MusicAgentFacadeTest.search_moodQuery_tagSongsFirst`: query "mellow songs for studying", fake Last.fm returns 2 songs for "mellow", both match, so they are results 1 and 2.
  - `MusicAgentFacadeTest.search_plainTitle_noLastfmCall`: "american girls" with no mood or genre makes no `topTracks` call.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** `CandidateRetriever.byTag` keeps its signature and tests and delegates to the matcher.
- [ ] **Step 4: `./gradlew build --rerun-tasks --no-build-cache`** → green, with every existing `CandidateRetrieverTest` unchanged.
- [ ] **Step 5: Commit:** "Shared Last.fm-to-Spotify matcher; Find Songs puts tag songs first for mood and genre searches".

### Task 4: Discover adds artists similar to the user's top artists

**Files:** `music/TagService.java`, `music/LastFmAdapter.java`, `agent/CandidateRetriever.java`, `agent/RecommendationEngine.java` (Discover path only), `config/Config.java`, `musicmind.properties`. Tests: `LastFmAdapterTest`, `CandidateRetrieverTest`, `RecommendationEngineTest`.

**Interfaces:**
- `TagService.similarArtists(String artistName, int limit): List<String>`, a default method returning an empty list. `LastFmAdapter` implements it with `artist.getsimilar&artist=<name>&autocorrect=1&limit=<n>` and returns names in Last.fm's order. Error 6 gives an empty list, and other failures throw `MusicServiceException`.
- `CandidateRetriever.bySimilar(List<String> topArtistNames, Set<String> knownArtistIds, int maxSearches): List<Track>`. For the first `lastfm.discoverSeedArtists` (3) names, read similar artists (`lastfm.similarPerArtist`, 5). For each similar name not already known, make one Spotify search `artist:"<name>"` with limit `lastfm.similarTracksPerArtist` (3). Keep tracks whose artist name matches exactly (`SearchRanker.normalise`) and whose artist id is not in `knownArtistIds`. Stop at `maxSearches` = `lastfm.discoverMaxMatches` (10), or at the first rate-limit or auth error.
- `RecommendationEngine`: new `Source.SIMILAR` with wording `text.reasonSourceSimilar=Similar to artists you play (Last.fm)`. Only Discover runs (no seed genres, no search terms, no tags) call `bySimilar`, with the profile's top artist names and ids. The tracks join the new-music pool, and blocked artists, blocked tracks and explicit rules apply as for any track.

- [ ] **Step 1: Failing tests:**
  - `LastFmAdapterTest.similarArtists_parsesNames`, `similarArtists_error6_empty`.
  - `CandidateRetrieverTest.bySimilar_keepsOnlyExactNewArtists_andCapsSearches`: 3 seeds × 5 similar artists with `maxSearches=10` gives exactly 10 searches, a wrong-artist hit is dropped, and a known artist is dropped.
  - `RecommendationEngineTest.discover_includesSimilarArtistTracks_respectsBlocks`.
  - `RecommendationEngineTest.create_doesNotCallSimilar`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: `./gradlew build --rerun-tasks --no-build-cache`** → green.
- [ ] **Step 5: Commit:** "Discover adds songs by artists Last.fm lists as similar to the user's top artists".

### Task 5: Modify sees each song's tags

**Files:** create `agent/SongTagReader.java` and `domain/SongTags.java`. Modify `agent/PromptBuilder.java` (`buildEditPrompt`), `agent/PlaylistEditor.java`, `controller/MusicAgentFacade.java:813,913`. Tests: `SongTagReaderTest`, `PromptBuilderTest`, `PlaylistEditorTest`, `ModifyPlaylistFlowTest`.

**Interfaces:**
- `record SongTags(List<String> tags, Kind kind)` with `enum Kind { SONG, ARTIST, NONE }`.
- `SongTagReader(TagService service, ArtistGenreCache artistTags, Config config, LongSupplier nanos)`. Method `Map<String, SongTags> read(List<Track> tracks)` is keyed by track id. It looks up `trackTags(firstArtist, title)` for at most `lastfm.maxTrackTagLookups` (30) songs, in playlist order, within `lastfm.trackTagBudgetSeconds` (15). It stops after the first failure. Results stay in memory only, at most `lastfm.trackTagsKept` per song after the Task 1 junk filter. Any song without its own tags gets its artists' cached tags (`ArtistGenreCache.cached`, first artist that has some) as `ARTIST`, else `NONE`.
- `PromptBuilder.buildEditPrompt(String instruction, PlaylistContext playlist, Map<String, SongTags> tags)`. Each track line becomes `n: id, explicit|clean, X min, song tags: a, b` (or `artist tags: …`, or `no tags`). The instruction text adds: "Use the tags to judge mood or style; change only songs whose tags show the quality asked for; never guess for a song with no tags." The two-argument overload stays and passes an empty map.
- `PlaylistEditor.plan(...)` gains an overload taking the `Map<String, SongTags>`. The facade builds it with `SongTagReader` before planning, and only when Last.fm is configured.

- [ ] **Step 1: Failing tests:**
  - `SongTagReaderTest.capsLookups_thenArtistTags_thenNone`: 32 tracks with cap 30 gives 30 `trackTags` calls, track 31 gets its artist's cached tags (`ARTIST`), and track 32, with no artist tags, is `NONE`.
  - `SongTagReaderTest.stopsAfterFailure`.
  - `SongTagReaderTest.timeBudget` (fake clock).
  - `PromptBuilderTest.editPrompt_listsTagsAndKind_noTitles`: the prompt contains `song tags: sad, mellow` and no title or artist name.
  - The existing reflective guard stays green.
  - `ModifyPlaylistFlowTest.removeSadSongs_usesTags`: the mock LLM gets a prompt containing the tags, and its reply removes the tagged-sad position.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: `./gradlew build --rerun-tasks --no-build-cache`** → green.
- [ ] **Step 5: Commit:** "Modify's edit prompt carries each song's Last.fm tags (song or artist), never titles".

### Task 6: Documents say why Last.fm, and match the code

**Files:** `docs/STAGE2_PLAN.md` (one entry "Last.fm replaces MusicBrainz; tags in Find Songs, Discover, Modify (F1-F6, F10; Nov 5)", Original → Change → Reason → Impact). The Reason must say: *we chose Last.fm because it has tags for artists and songs, including moods and activities, which the features need, and one source keeps the vocabulary the same on every screen*. Also mark older MusicBrainz entries as superseded in a line. Update `docs/STAGE1_CONFORMANCE.md`, `README.md`, `src/README.md`, `docs/AI_COLLABORATION_LOG.md` (Nov 5 row), and `docs/report/stage2-report.html`: the data policy and the technology table (MusicBrainz removed, Last.fm's role), the changes table row, the Adapter section (`LastFmAdapter` behind `GenreService` and `TagService`), and the trade-off for mood tags under genres. Rebuild `docs/report/stage2-report.pdf` with headless Chrome as in `docs/report/build-report.sh`'s last step. Update the UML: in `docs/diagrams/uxf/MusicMind_ClassArchitecture.uxf` (and the SD03 and SD04 sequences), replace `MusicBrainzAdapter` with `LastFmAdapter` and add `TagService`, `TagTrackMatcher` and `SongTagReader`. Export the PNGs if a UMLet command line is available; otherwise list the diagrams as needing re-export in the report's refresh note.

- [ ] **Step 1:** Make the edits. `grep -rni musicbrainz docs README.md src/README.md` must then show only historical lines: Stage 1 history, an "Original" column, or a superseded note.
- [ ] **Step 2:** Rebuild the PDF and check its page count and that it opens.
- [ ] **Step 3: Commit:** "Document the switch to Last.fm tags and why".
