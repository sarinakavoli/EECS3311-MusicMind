# Last.fm Tags Everywhere — Design

**Date:** Nov 5, 2026. **Owner:** Sarina. **Status:** awaiting owner approval.

## Intent and why

MusicMind describes music in two different ways today. Create uses Last.fm tags (song tags and tag
charts), while everything else uses MusicBrainz genres. The owner wants **one consistent source of
music descriptions across the whole project**. The chosen source is **Last.fm**, because it has
**tags**: listener-given labels for both artists and individual songs. They cover genres ("indie pop"),
moods ("sad", "chill") and activities ("workout", "study"). MusicBrainz has only genres, and only for
artists. The report and the Stage 2 plan must say this in plain words: *we chose Last.fm because it
has tags for artists and songs, including moods and activities, which the features need, and using
one source keeps the vocabulary the same on every screen.*

Spotify stays the source of what the user plays (top items, saved songs, recent plays), of
playlists, and of the songs themselves, and it is where playlists are saved. Last.fm cannot replace
it: it has no access to the user's Spotify listening or playlists.

## Goals

1. Replace MusicBrainz with Last.fm as the only source of artist genres or tags, for every feature
   that shows or uses genres: Your Taste (F1), Trends (F2), Discover (F4), Create goal fit and goal
   review (F5), and explanations (F10).
2. Use tags where they make results more accurate:
   - **Find Songs (F3):** a meaning search ("mellow alternative for studying") also takes the top
     songs of the matching Last.fm tags, matched to Spotify tracks. Today it relies on Spotify text
     search, which matches title words.
   - **Discover (F4):** new artists come from Last.fm's similar artists of the user's top artists
     (`artist.getsimilar`), alongside today's genre search.
   - **Modify (F6):** "remove the sad songs" or "make it calmer" can act on the songs' own tags. The
     edit prompt sends, per track, the id, explicit flag, length **and its Last.fm tags**. Tags are
     not Spotify content, so this does not break the rule that no Spotify titles or names go to
     OpenAI.
3. Document the choice and its reason in `docs/STAGE2_PLAN.md` (Original → Change → Reason →
   Impact), the Stage 2 report, the README, the conformance table and the UML (Adapter for
   `GenreService` is now `LastFmAdapter`).

## Non-goals

- Replacing Spotify for anything.
- Using Last.fm for features that do not choose or describe music: Your Rules (F8), Like and Not
  for me (F9), negotiation (F11), History, Restore and Undo (F12), and validation (F7).
- A new central tag service that every feature calls directly (approach B, rejected below).
- Keeping MusicBrainz as a fallback (the owner chose full replacement).

## Anti-goals (a failure even if it works)

- Spotify rate limits come back during testing or recording because tag features make many more
  Spotify searches.
- Any Spotify title, artist name or album reaches OpenAI.
- A screen still credits MusicBrainz, genres from the two sources are mixed in one view, or app wording changes beyond the credit.
- Junk tags ("seen live", "favorites", the artist's own name) show up as genres.

## Constraints

- Due Nov 10. All existing tests stay green.
- Spotify Development Mode: search `limit` ≤ 10, no batch endpoints, and keep total Spotify calls
  low. Every Last.fm song matched to Spotify costs one search, so caps are tunables in
  `musicmind.properties`, and the existing in-memory session cache of Last.fm→Spotify matches is
  reused.
- **Policy:** artist names and song titles may go to Last.fm (already disclosed for Create), never to
  OpenAI, and are never stored. Last.fm tags are not Spotify content, so they may be cached in the
  database and sent to OpenAI.
- `LASTFM_API_KEY` stays optional. Without it the app still runs. Genres are then simply unknown, as
  for an artist MusicBrainz did not know today, and one notice says tag data is off. The owner
  accepts that some artists end up with no genres.
- Design patterns stay evident: `LastFmAdapter` is the Adapter behind `GenreService` (and
  `TagService`), and `GenreEnrichingMusicService` stays the Decorator.

## Approaches considered

- **A. Swap the source behind the existing genre layer, then add tag uses where they help
  (chosen).** `GenreEnrichingMusicService` already feeds genres to every feature through the
  `GenreService` interface. `LastFmAdapter` implements `GenreService` with `artist.gettoptags`, so
  F1, F2, F4, F5 and F10 switch at once. Find Songs, Discover and Modify then get targeted tag
  features. Least risk, keeps the documented patterns, and fits the time left.
- **B. One central "music tags" service every feature calls directly.** Cleanest on paper, but it
  rewires every feature and the diagrams with five days left. Rejected for risk.
- **C. Swap the genre source only.** Consistent wording but no accuracy gain in Find Songs,
  Discover or Modify. Rejected because the owner wants more accurate results.

The owner picked A ("maybe A"). It can still be changed before the plan is written.

## Decided parts of the how

- `LastFmAdapter implements GenreService`. Artist tags come from `artist.gettoptags` by artist name
  (with `autocorrect=1`), most used first. They are filtered: tags under a minimum count, tags on a
  blocklist in properties ("seen live", "favorites", "favourite", "albums i own", "my top", and so
  on), the artist's own name, and bare years are dropped. At most `lastfm.artistTagsKept` (10) are
  kept, lower-cased.
- `MusicBrainzAdapter` and its `musicbrainz.*` tunables are removed, along with its tests. Cached
  MusicBrainz genres are not reused: the `artist_genre` cache gets a source marker (or a new table),
  so only Last.fm tags are read. The schema change goes through the existing schema setup.
- Wording: no screen or CLI wording changes, apart from the data credit. The "Genre data provided
  by MusicBrainz" credit is removed and the existing Last.fm credit stays. Comments and docs that
  name MusicBrainz as the source are updated.
- `MoodGenreMap` (the fixed mood→genre table) is checked against Last.fm tag names so a mood word
  maps to tags that exist on Last.fm. It is kept as the bridge from the AI's mood to tag names.
- Spotify searches made for tag features are capped per action by tunables, and every result
  goes through the existing title-and-artist match check (no loose matches).

## Owner's decision after the builder check: show all as tags

Last.fm tags mix genres ("indie pop") with moods and activities ("sad", "workout"). The owner chose
to use them all the same way, as data, everywhere. **This is a data change only: no wording in the
app changes.** Screens and CLI output keep their current words ("Top genres", "genres", and so on).
The only visible text that changes is the required data credit: the MusicBrainz credit is removed
because MusicBrainz is no longer used, and the existing Last.fm credit covers genres and tags.

- Your Taste's "Top genres" and Trends show the leading Last.fm tags of the user's artists, under
  the same headings as today. A mood tag such as "chill" may appear there.
- Discover and Create search Spotify with tags as they do with genres today. A mood or activity tag
  ("sad", "workout") is a valid search term. The existing title penalties (a result titled with the
  search word ranks lower) stay, because such searches tend to return stock-music titles.
- Only junk tags are filtered (the blocklist, the artist's own name, years, low counts). There is
  no genre-versus-mood split.
- The report states the trade-off: one vocabulary everywhere, at the cost of some noisier results
  for mood tags.

## Calls made after the builder check (Claude's calls, for the owner to check)

- **Modify song tags:** song tags come from `track.gettoptags` (`TagService.trackTags`). At most
  `lastfm.maxTrackTagLookups` (30) songs per edit are looked up, within the existing time budget
  (`lastfm.trackTagBudgetSeconds`, 15 s), held in memory for the session, and never stored. A song
  without its own tags (not looked up, or unknown to Last.fm) gets its artists' tags instead. Each
  prompt line says which kind it has: `song tags`, `artist tags` or `no tags`. The prompt tells the
  model to change only songs whose tags show the quality asked for, and to say in its reply how
  many songs had no tags.
- **Failures:** a Last.fm HTTP failure or timeout is "retry later" and is not cached; only a real
  answer is cached, including an empty one, following the existing `GenreService.lookup` contract.
  The existing "MusicBrainz paused after N failures" cooldown is reused for Last.fm. Without a key,
  the GUI shows one banner, like the existing "no LLM key" banner, and the CLI prints one line
  saying tags are off.
- **Cache migration:** `artist_genre` gains a `source` column (`ALTER TABLE ... ADD COLUMN IF NOT
  EXISTS source`). Rows that are not `lastfm` are deleted once at schema setup, so no MusicBrainz
  genre is ever read again. No full database reset is needed.
- **Spotify budget per action:** Find Songs and Discover may each make at most 10 extra Spotify
  searches for tag songs (`lastfm.findSongsMaxMatches`, `lastfm.discoverMaxMatches`), on top of
  today's searches, which are unchanged. The match cache means a repeated action makes fewer.
- **Docs in scope:** the report source `docs/report/stage2-report.html` (rebuilt to PDF), the UMLet
  diagrams in `docs/diagrams/uxf` with their PNG exports, `docs/STAGE2_PLAN.md`,
  `docs/STAGE1_CONFORMANCE.md`, the README and `src/README.md`.

## Left to the builder

- Exact tunable values (minimum tag count, caps per feature), within the constraints above.
- Whether Find Songs merges tag songs into the existing ranker or shows them as a ranked tier.
- How Discover blends similar-artist songs with today's sources, provided Discover's mix rules
  (share of new artists, blocked artists, explicit) still hold.
- How Modify's planner turns "remove sad songs" into remove commands from tags. The model chooses
  from ids with tags. It must never invent tracks.
- Test doubles for Last.fm (the local `HttpServer` pattern already used in `LastFmAdapterTest`).

## Success

- No screen or document mentions MusicBrainz as a data source (except the Stage 1 history and the
  Original column of the change entry).
- Your Taste shows Last.fm tags for most of the owner's top artists.
- Find Songs "mellow songs for studying" returns real mellow songs, not stock titles.
- Modify "remove the sad songs" removes songs tagged sad or melancholy.
- One live check per feature on the owner's account without hitting Spotify's rate limit.
