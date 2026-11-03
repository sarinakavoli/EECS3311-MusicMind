# GUI test findings

Things Sarina raises while testing PR #4. Nothing here gets changed until her test
and questions are finished; then all of it is worked through together.

## Open

1. **Time range wording (Profile screen).** "short / medium / long" does not say what
   is being measured.
   - **Sarina asked for:** Past week, Past month, Past 3 months, Past 6 months, Past year.
   - **Limit:** Spotify offers three periods only: about 4 weeks, 6 months and 1 year.
     Past month, Past 6 months and Past year match those. Past week and Past 3 months
     have no Spotify period behind them.
   - **To decide before changing it:** use the three matching labels, or find another
     source for the two missing periods. Recently-played returns only the latest 50
     tracks, so it may not cover a full week.

2. **Search does not say what it searches (Search screen).** Sarina could not tell
   whether it covers artists, albums or songs.
   - **What it does now:** it returns songs only (`type=track`). The AI reworks the
     typed text before Spotify sees it.
   - **To do:** say this on the screen, and decide whether artist and album results
     should be shown too.

3. **Search misses an exact song title (Search screen).** "american girls" is a Harry
   Styles song, but the visible rows showed "Native American Female Vocal", "Female"
   and "Strong Women's Song" instead. The scrolled-off rows were not checked.
   - **Stage 1 F3:** free text such as "mellow alternative for studying" becomes a
     structured Spotify search and returns ranked track / artist results.
   - **Cause:** a live CLI run of the same words returned real "American Girls" titles,
     while the GUI run did not. The AI rewrites each search and can differ between
     runs, and the typed words are used only when the AI fails.
   - **To do:** run the words exactly as typed as well as the AI version, and rank
     exact title and artist matches first. Songs that fit the meaning follow, so both
     "american girls" and "mellow alternative for studying" work.

4. **Search rows show no artist (Search screen).** Rows show only title and length, so
   two songs with the same name look identical.
   - **To do:** show artist names here, and check the other track lists.

5. **Same song listed twice (Search screen).** "150 Bars Infinity" appears twice with
   the same length. They may be separate Spotify releases.
   - **To do:** check the track ids, then merge true duplicates.

6. **Candidate message shows only the latest song (Search screen).** After two adds,
   the line said only "Added to your candidates: All the Old Things". Both songs were
   saved and shown in the list on the right.
   - **To do:** add the running total, such as "Added All the Old Things (2 candidates)".

7. **"Logged in as" shows a raw Spotify id (top of every screen).** Seen in the same
   screenshot as the Search items.
   - **To do:** show the Spotify display name, falling back to the id if there is none.

8. **Tabs do not explain themselves or feel engaging (all screens).** "Search" looked
   like a simple name search, not an AI feature that helps build a playlist.
   - **Sarina asked for:** a description on the screen, a stronger feature name than
     "Search", and a more engaging way of showing the features.
   - **To do:** rename Search (for example "Find Songs") and give every tab a short
     line saying what it does and what to try, with an example. Say how candidates
     lead into Create. Agree the names with Sarina first.
   - **Stage 1:** the feature stays F3 Natural-Language Music Search, so record the
     visible name change in `docs/STAGE2_PLAN.md` and keep the F-number in the report.

9. **Discover has no explanation (Discover screen).** Sarina could not tell what it is
   for. Stage 1 F4: new recommendations built from her own listening, excluding songs
   she already knows.
   - **Sarina's idea for the description:** "Start listening to new types and styles
     of music ..."
   - **Check first:** new-artist picks come from genres she already listens to, so
     "new styles" may promise more than it does. Possible wording: "Start listening to
     something new: artists and songs you haven't heard, picked from your taste."
   - **To do:** agree the final wording, covered by item 8.

10. **The 50% slider is unclear and can show the wrong number (Discover screen).**
    "New music share" and "familiar-bias preference" are not plain wording. The slider
    is greyed out while the box is ticked, and it stays at its starting 50% instead of
    showing the saved preference. With `familiarBias = 0.4` the real share is 60% new
    while the screen says 50%.
    - **To do:** show the saved value on the slider, and use wording such as "Mix: 60%
      new artists, 40% artists you know" with "Use my usual mix".

11. **Accept / Reject is poor design (Discover and playlist tracks).** The buttons do
    not say what happens, and Accept only records a like for later scoring. It does not
    save the song or add it to a playlist.
    - **Stage 1 F9:** thumbs-up / thumbs-down feed future scores, so that behaviour
      stays.
    - **To do:** redesign as compact like / dislike controls, add a clear action to
      add a song to the next playlist, and show the chosen state on the row. Stage 1
      F4 and F9 say accept / reject, so record any change in `docs/STAGE2_PLAN.md`.
    - **Sarina repeated this on the Playlists screen.** Two large buttons on every row
      dominate the list, and "Accept" makes little sense for a song already in the
      playlist. Use the same small icon controls in every track list.

12. **"Why these?" is poor design (Discover screen).** It repeats on every track and
    says "these" for one song. Each row also shows "score 0.60, familiar 0%", which
    means nothing to a listener, and the three visible rows all show the same score.
    - **To do:** use "Why this song?" on rows and a plain reason in one sentence. Keep
      one summary for the whole list. Replace the score with words such as "New artist
      for you", and check why the scores are identical.

13. **Length is entered in minutes only (Create dialog).** "Target length in minutes"
    is awkward for long playlists.
    - **Sarina asked for:** hours and minutes.
    - **To do:** use separate hour and minute fields, keep blank or zero as "take it
      from the goal", and show lengths the same way elsewhere. Check the Modify wording.

14. **The mix slider looks broken (Create dialog).** Sarina could not move the 50%. It
    is locked while "Let the goal decide the mix of familiar and new tracks" is ticked,
    and nothing says so. It also shows "50% familiar, 50% new" although the goal
    decides the real mix, the same problem as item 10.
    - **To do:** hide the slider while the box is ticked, or say "The AI will choose
      the mix from your goal". Show numbers only when they are real.

15. **Candidates go in even when they do not fit the goal (Create).** "a playlist for
    workout days" used "Native American Female Vocal" and "All the Old Things" because
    they were waiting in candidates. `RunContext.fittingCandidates` checks only the
    rules and length, and `SelectingState` puts them first. The dialog does not show
    them before Create runs.
    - **To do:** list the candidates in the dialog with a choice to include them, so
      nothing is added by surprise. Stage 1 says the next playlist starts with them, so
      record any change in `docs/STAGE2_PLAN.md`.

16. **Songs do not suit the goal (Create).** Sarina's verdict: the tracks are not
    related to a workout. New picks come from genres the AI guessed for the goal, here
    edm, hip hop, trap and drum and bass. Spotify gives this app no audio features, so
    energy and tempo cannot be measured, and a genre match alone does not make a
    workout song. Item 15 made this worse by taking 3 of the 6 places.
    - **To do:** also search using the goal's activity words, then have the AI review
      the real retrieved songs and drop those that do not suit the goal. It chooses only
      from real results. Measure against several goals, and record the change from
      Stage 1 in `docs/STAGE2_PLAN.md`. Say honestly in the report that tempo data is
      unavailable.

17. **"Why these?" text oversells and is cut off (Create).** It says candidates "align
    with the mood or style", but nothing checked that. The text also ends at "genr..."
    because it does not fit the box.
    - **To do:** state only what actually happened, and let the full text be read.

18. **"Allow explicit tracks" is unclear (Create dialog).** Sarina asked what it means.
    - **To do:** reword, for example "Include songs with explicit lyrics". Check the same
      wording on the Preferences screen.

19. **No delete button on playlists (Playlists screen).** Sarina asked for one on each
    playlist.
    - **Limit:** Spotify's API has no true delete. Removing a playlist is an unfollow,
      and `MusicService` already has `unfollowPlaylist`.
    - **To do:** add a delete control to each row, with a confirmation that names the
      playlist. Check that it disappears from Spotify, and how F12 can recreate it from
      a saved snapshot. It affects her real account, so test it on a test playlist.

20. **Preferences screen is confusing (Preferences).** Sarina could not tell what it is
    for. It is a form of typed text boxes with a Save button per row.
    - **Sarina asked for:** a description on every feature, covered by item 8.
    - **To do:** explain that these are saved rules the app follows for every playlist
      and recommendation. Replace raw entry with plain controls: allow / block choice
      for explicit songs, a slider with the same mix wording as item 10 instead of 0.4,
      and block buttons on song rows rather than typed track ids. Use one Save button
      if that works. Keep validation and the F7 behaviour unchanged.

21. **Blocked tracks refuses song names (Preferences).** Sarina typed "hello" and got a
    long red error asking for a 22-character Spotify id. A user cannot know that id.
    - **To do:** let the user search for a song by name and pick it, and keep ids out of
      sight. Item 20's block buttons cover the song rows. Write errors in plain words.

22. **Blocked artist picks a same-name artist without asking (Preferences).** "Ebi"
    matched 3 artists, so the app used Spotify's top match and showed a raw artist id.
    It may have blocked the wrong person.
    - **To do:** show the matching artists and let the user choose. Show names, not ids.
      The same applies to artist blocks in the CLI wording, if it fits there.

23. **History looks dated and hard to read (History).** Sarina wants it more modern. The
    visible rows are almost all "Taste profile: view taste profile (medium)", so the
    useful events are buried. Saved versions are one long flat list with near-identical
    lines, and the two scrolling tables sit inside a scrolling page.
    - **To do:** split it into two clear parts: activity shown as a timeline, and
      playlists with their saved versions grouped under each one. Add filters for
      playlist changes, and hide routine profile views by default. Show what changed in
      each version, such as "27 to 22 tracks", plus a preview before Restore.

24. **The whole app looks like a default desktop form.** Sarina's comments on Accept /
    Reject and History point the same way, and the videos will show every screen.
    - **To do:** after the screen fixes, apply one modern style to all tabs: spacing,
      type, colours, cards, buttons and clear empty states. It stays JavaFX, using a
      stylesheet in place of inline styling. Agree the look with Sarina first.

25. **Searches by description are still weak (Search).** Live after the backend fixes,
    "mellow alternative for studying" returned "Boss Battle" and "Alternative Rock",
    and "upbeat" returned songs with "Dance" in the title. Exact titles now work, but
    Spotify's text search matches title words, and this app has no tempo or mood data.
    - **To do:** decide whether the goal review should also run on search results.

## Backend status (Nov 2)

Plan: `docs/superpowers/plans/2026-11-02-backend-test-fixes.md`. Forced run on c6d947f: 842
tests, 0 failures.

- **Done in the backend:** 3, 5, 12, 15 (choice), 16, 17 (honest wording), 19, 21, 22, and
  the data for 4, 7 and 10.
- **Exact title check:** "american girls" returned exact titles in the top 3 in 5 of 5
  live runs.
- **Not yet shown on screen:** artist names, the display name, the candidate choice, the
  real mix, block choosers and delete. These wait for the frontend phase.
- **Not yet proven:** whether playlists now suit their goal. Sarina needs to run Create
  on several goals. No playlist has been created since the fixes.
- **Limits:** Past week and Past 3 months have no Spotify period. Spotify gives no tempo
  data, so goal fit depends on the AI judging titles and artists. Each Create makes up to
  three extra AI calls.
- **The GUI must catch** the `IllegalStateException` from `blockArtist` and `blockTrack`
  and show its message.

## Passed

- **Create in the GUI (F5), Nov 2.** It finished for real on Sarina's account: "workout",
  6 tracks for a 20 minute target, 3 from candidates. The Critical bug from the PR #4
  review no longer stops it. Song quality is items 15 and 16.
- **Modify in the GUI (F6), Nov 2.** Sarina ran it on "workout": 6 tracks became 5 tracks,
  19 min, and the playlist still met its constraints. Whether the songs matched her
  instruction has not been judged yet.
