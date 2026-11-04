# GUI test findings

Things Sarina raises while testing PR #4. Nothing here gets changed until her test
and questions are finished; then all of it is worked through together.

## Findings and their status

Status added Nov 4 after the frontend redesign and its read-only live check (`docs/DAILY_TEST_NOTES.md`).

1. **Time range wording (Profile screen).** "short / medium / long" does not say what
   is being measured.
   - **Sarina asked for:** Past week, Past month, Past 3 months, Past 6 months, Past year.
   - **Limit:** Spotify offers three periods only: about 4 weeks, 6 months and 1 year.
     Past month, Past 6 months and Past year match those. Past week and Past 3 months
     have no Spotify period behind them.
   - **To decide before changing it:** use the three matching labels, or find another
     source for the two missing periods. Recently-played returns only the latest 50
     tracks, so it may not cover a full week.
   - **Status (Nov 4):** **Done** (Nov 4): the Time range picker shows Past month, Past 6 months and Past year (`text.guiRange.*`). Past week and Past 3 months were not added: Spotify has no period behind them.

2. **Search does not say what it searches (Search screen).** Sarina could not tell
   whether it covers artists, albums or songs.
   - **What it does now:** it returns songs only (`type=track`). The AI reworks the
     typed text before Spotify sees it.
   - **To do:** say this on the screen, and decide whether artist and album results
     should be shown too.
   - **Status (Nov 4):** **Done** (Nov 4): Find Songs says on the page that it finds songs by meaning or by name (`Desc.search`). Artist and album results were not added; the page lists songs, with the matching artists in a line below.

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
   - **Status (Nov 4):** **Done** (backend Nov 2; seen live Nov 4): typed words are searched as typed and exact titles rank first. Live "american girls" on the Find Songs page returned four American Girls songs at the top.

4. **Search rows show no artist (Search screen).** Rows show only title and length, so
   two songs with the same name look identical.
   - **To do:** show artist names here, and check the other track lists.
   - **Status (Nov 4):** **Done** (Nov 4): `TrackRow` shows the artist names under every title in Your Taste, Find Songs, Discover, Playlists, Create and the History preview. A song with no artist names shows the title alone.

5. **Same song listed twice (Search screen).** "150 Bars Infinity" appears twice with
   the same length. They may be separate Spotify releases.
   - **To do:** check the track ids, then merge true duplicates.
   - **Status (Nov 4):** **Done** (backend Nov 2): duplicates merged in the search.

6. **Candidate message shows only the latest song (Search screen).** After two adds,
   the line said only "Added to your candidates: All the Old Things". Both songs were
   saved and shown in the list on the right.
   - **To do:** add the running total, such as "Added All the Old Things (2 candidates)".
   - **Status (Nov 4):** **Done** (Nov 4): the line after an add names the song and the running total, `SearchView` with `ViewFormat.candidateAdded`; a second add of the same song says it is already waiting, and "N waiting" shows by the results heading.

7. **"Logged in as" shows a raw Spotify id (top of every screen).** Seen in the same
   screenshot as the Search items.
   - **To do:** show the Spotify display name, falling back to the id if there is none.
   - **Status (Nov 4):** **Done** (Nov 4): the header shows the Spotify display name (`AppController.currentUserName`, `GuiApp.showDisplayName`), falling back to the id. Seen live: "Logged in as Sarina".

8. **Tabs do not explain themselves or feel engaging (all screens).** "Search" looked
   like a simple name search, not an AI feature that helps build a playlist.
   - **Sarina asked for:** a description on the screen, a stronger feature name than
     "Search", and a more engaging way of showing the features.
   - **To do:** rename Search (for example "Find Songs") and give every tab a short
     line saying what it does and what to try, with an example. Say how candidates
     lead into Create. Agree the names with Sarina first.
   - **Stage 1:** the feature stays F3 Natural-Language Music Search, so record the
     visible name change in `docs/STAGE2_PLAN.md` and keep the F-number in the report.
   - **Status (Nov 4):** **Done** (Nov 4): menu names Your Taste, Trends, Find Songs, Discover, Playlists, Your Rules, History; every page has a title, a one-line description and, where it takes input, a "Try:" line. Find Songs says that added songs can start the next playlist. The F-numbers are unchanged; the rename is in `docs/STAGE2_PLAN.md`.

9. **Discover has no explanation (Discover screen).** Sarina could not tell what it is
   for. Stage 1 F4: new recommendations built from her own listening, excluding songs
   she already knows.
   - **Sarina's idea for the description:** "Start listening to new types and styles
     of music ..."
   - **Check first:** new-artist picks come from genres she already listens to, so
     "new styles" may promise more than it does. Possible wording: "Start listening to
     something new: artists and songs you haven't heard, picked from your taste."
   - **To do:** agree the final wording, covered by item 8.
   - **Status (Nov 4):** **Done** (Nov 4): the Discover description uses the agreed wording: "Start listening to something new. Artists and songs you haven't heard yet, picked from your taste."

10. **The 50% slider is unclear and can show the wrong number (Discover screen).**
    "New music share" and "familiar-bias preference" are not plain wording. The slider
    is greyed out while the box is ticked, and it stays at its starting 50% instead of
    showing the saved preference. With `familiarBias = 0.4` the real share is 60% new
    while the screen says 50%.
    - **To do:** show the saved value on the slider, and use wording such as "Mix: 60%
      new artists, 40% artists you know" with "Use my usual mix".
   - **Status (Nov 4):** **Done** (Nov 4): "Use my usual mix" with "Mix: 60% new artists, 40% artists you know"; the slider is hidden while the usual mix is used and shows the saved value (the default when nothing is saved). Seen live: "Mix: 60% new artists, 40% artists you know".

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
   - **Status (Nov 4):** **Done** (Nov 4): small like and not-for-me controls on every song row (the chosen one stays highlighted), plus "+ Add" on Find Songs and Discover and "Block" on playlist songs. Same row everywhere. F9 behaviour is unchanged.

12. **"Why these?" is poor design (Discover screen).** It repeats on every track and
    says "these" for one song. Each row also shows "score 0.60, familiar 0%", which
    means nothing to a listener, and the three visible rows all show the same score.
    - **To do:** use "Why this song?" on rows and a plain reason in one sentence. Keep
      one summary for the whole list. Replace the score with words such as "New artist
      for you", and check why the scores are identical.
   - **Status (Nov 4):** **Done** (backend Nov 2, screen Nov 4): rows say "Why this song?" with a plain reason ("New artist for you"); one "Why these songs?" for the list. Scores are not shown.

13. **Length is entered in minutes only (Create dialog).** "Target length in minutes"
    is awkward for long playlists.
    - **Sarina asked for:** hours and minutes.
    - **To do:** use separate hour and minute fields, keep blank or zero as "take it
      from the goal", and show lengths the same way elsewhere. Check the Modify wording.
   - **Status (Nov 4):** **Done** (Nov 4): hours and minutes fields in Create; 0 and 0 means "from the goal"; 1 hour 75 minutes and letters are refused with a message (`ViewFormat.parseHoursMinutes`, tested including a total below the planner minimum). The Modify wording was not changed.

14. **The mix slider looks broken (Create dialog).** Sarina could not move the 50%. It
    is locked while "Let the goal decide the mix of familiar and new tracks" is ticked,
    and nothing says so. It also shows "50% familiar, 50% new" although the goal
    decides the real mix, the same problem as item 10.
    - **To do:** hide the slider while the box is ticked, or say "The AI will choose
      the mix from your goal". Show numbers only when they are real.
   - **Status (Nov 4):** **Done** (Nov 4): the slider is hidden while "Let the AI choose the mix from my goal" is ticked, with a line saying so; numbers show only when the slider is used. The slider is also locked while a run is in progress.

15. **Candidates go in even when they do not fit the goal (Create).** "a playlist for
    workout days" used "Native American Female Vocal" and "All the Old Things" because
    they were waiting in candidates. `RunContext.fittingCandidates` checks only the
    rules and length, and `SelectingState` puts them first. The dialog does not show
    them before Create runs.
    - **To do:** list the candidates in the dialog with a choice to include them, so
      nothing is added by surprise. Stage 1 says the next playlist starts with them, so
      record any change in `docs/STAGE2_PLAN.md`.
   - **Status (Nov 4):** **Done** (backend Nov 2, dialog Nov 4): waiting songs are listed in the Create dialog with a choice "Start with these N songs"; Create stays off until they have loaded. Recorded in `docs/STAGE2_PLAN.md`.

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
   - **Status (Nov 4):** **Done in the backend** (Nov 2): activity searches and the goal review. **Still unproven:** no playlist has been created since the fixes, so goal fit is untested on the real account. Tempo data is unavailable from Spotify.

17. **"Why these?" text oversells and is cut off (Create).** It says candidates "align
    with the mood or style", but nothing checked that. The text also ends at "genr..."
    because it does not fit the box.
    - **To do:** state only what actually happened, and let the full text be read.
   - **Status (Nov 4):** **Done** (backend Nov 2 for the wording; Nov 4 for the box): the explanation scrolls (`WhyExpander`), so the full text can be read.

18. **"Allow explicit tracks" is unclear (Create dialog).** Sarina asked what it means.
    - **To do:** reword, for example "Include songs with explicit lyrics". Check the same
      wording on the Preferences screen.
   - **Status (Nov 4):** **Done** (Nov 4): "Include songs with explicit lyrics" in Create, and Allow / Block explicit songs on Your Rules.

19. **No delete button on playlists (Playlists screen).** Sarina asked for one on each
    playlist.
    - **Limit:** Spotify's API has no true delete. Removing a playlist is an unfollow,
      and `MusicService` already has `unfollowPlaylist`.
    - **To do:** add a delete control to each row, with a confirmation that names the
      playlist. Check that it disappears from Spotify, and how F12 can recreate it from
      a saved snapshot. It affects her real account, so test it on a test playlist.
   - **Status (Nov 4):** **Done** (Nov 4): Delete on every playlist row with a confirmation naming the playlist (an unfollow in Spotify). **Not run live:** it would change the real account.

20. **Preferences screen is confusing (Preferences).** Sarina could not tell what it is
    for. It is a form of typed text boxes with a Save button per row.
    - **Sarina asked for:** a description on every feature, covered by item 8.
    - **To do:** explain that these are saved rules the app follows for every playlist
      and recommendation. Replace raw entry with plain controls: allow / block choice
      for explicit songs, a slider with the same mix wording as item 10 instead of 0.4,
      and block buttons on song rows rather than typed track ids. Use one Save button
      if that works. Keep validation and the F7 behaviour unchanged.
   - **Status (Nov 4):** **Done** (Nov 4): Your Rules explains itself and has an Allow / Block choice for explicit songs, the mix slider with the Discover wording, and Find / choose / Block for artists and songs. It has a Save per section, not one button for the whole page (each rule is saved on its own so one failure does not hide another).

21. **Blocked tracks refuses song names (Preferences).** Sarina typed "hello" and got a
    long red error asking for a 22-character Spotify id. A user cannot know that id.
    - **To do:** let the user search for a song by name and pick it, and keep ids out of
      sight. Item 20's block buttons cover the song rows. Write errors in plain words.
   - **Status (Nov 4):** **Done** (backend Nov 2, screen Nov 4): a song is found by name and chosen from a list; no ids show.

22. **Blocked artist picks a same-name artist without asking (Preferences).** "Ebi"
    matched 3 artists, so the app used Spotify's top match and showed a raw artist id.
    It may have blocked the wrong person.
    - **To do:** show the matching artists and let the user choose. Show names, not ids.
      The same applies to artist blocks in the CLI wording, if it fits there.
   - **Status (Nov 4):** **Done** (backend Nov 2, screen Nov 4): matching artists are listed to choose from, with a display-only hint (an album title and whether the artist is in your listening, `AppController.artistHint`) that tells same-name artists apart. The CLI wording was left as it was.

23. **History looks dated and hard to read (History).** Sarina wants it more modern. The
    visible rows are almost all "Taste profile: view taste profile (medium)", so the
    useful events are buried. Saved versions are one long flat list with near-identical
    lines, and the two scrolling tables sit inside a scrolling page.
    - **To do:** split it into two clear parts: activity shown as a timeline, and
      playlists with their saved versions grouped under each one. Add filters for
      playlist changes, and hide routine profile views by default. Show what changed in
      each version, such as "27 to 22 tracks", plus a preview before Restore.
   - **Status (Nov 4):** **Done** (Nov 4): History is an Activity timeline (routine profile views hidden unless "Show routine views" is ticked) and Saved playlist versions grouped by playlist, each with the change ("27 → 22 songs") and a preview before Restore. Seen live; the preview was cancelled, nothing was restored.

24. **The whole app looks like a default desktop form.** Sarina's comments on Accept /
    Reject and History point the same way, and the videos will show every screen.
    - **To do:** after the screen fixes, apply one modern style to all tabs: spacing,
      type, colours, cards, buttons and clear empty states. It stays JavaFX, using a
      stylesheet in place of inline styling. Agree the look with Sarina first.
   - **Status (Nov 4):** **Done** (Nov 4): one dark stylesheet (`musicmind.css`) for every screen and dialog: menu, cards, pill buttons, empty states. JavaFX only; the inline styles were removed as the views were touched.

25. **Searches by description are still weak (Search).** Live after the backend fixes,
    "mellow alternative for studying" returned "Boss Battle" and "Alternative Rock",
    and "upbeat" returned songs with "Dance" in the title. Exact titles now work, but
    Spotify's text search matches title words, and this app has no tempo or mood data.
    - **To do:** decide whether the goal review should also run on search results.
   - **Status (Nov 4):** **Not done:** the goal review does not run on Find Songs results. Live Nov 4, "mellow alternative for studying" still gave a plain keyword result, and the page says so ("plain keyword search: your request could not be interpreted"). Left open; it needs a backend decision.

26. **An added song stays in "Songs found" (Find Songs).** Sarina: "when I add a music to the playlist it should disappear from" the results.
    - **Status (Nov 4):** **Done:** after "+ Add" the song leaves "Songs found", and songs already waiting are left out of new results (`ViewFormat.withoutWaiting`, tested). Remove on the waiting list brings it back into the current results. Seen in a rendered check with sample data.

27. **No way to play a chosen song (Discover).** Sarina: "add an option for playing the chosen songs".
    - **Status (Nov 4):** **Done, as an open-in-Spotify button:** the app cannot play audio itself (Development Mode gives no previews, and in-app playback is out of scope). Song rows on Discover, Find Songs and playlist tracks have a small "\u25B6 Spotify" button that opens `https://open.spotify.com/track/<id>` through JavaFX `HostServices` (`GuiContext.openInSpotify`). No Spotify call, nothing stored, nothing sent to OpenAI. The tooltip says it opens Spotify.

28. **The delete confirmation does not match the design (Playlists).** It showed the system alert with a blue "?" and a grey title bar.
    - **Status (Nov 4):** **Done:** one in-app dialog (`ConfirmDialog`): dark MusicMind surface, white title, message, a quiet "Keep it" button and a red "Delete". It replaces every Alert and the restore preview, the recreate prompt and the F11 conflict dialog (options stacked, Cancel last). No stock graphic or system title bar; Escape keeps things as they are.

29. **The Create dialog runs off the screen and cannot be resized (Playlists).** Sarina: "I can not click or see the green button and I can not make it full screen".
    - **Status (Nov 4):** **Done:** the dialog is resizable, its preferred height is capped to 90% of the screen (`UiParts.fitHeight`, tested), the form and result scroll, and the buttons stay pinned at the bottom. Progress and errors show above the buttons, outside the scroll. After a create the form gives way to a short summary (name, count, notices, "Why these songs?" collapsed) and "Open playlist" replaces Create; it closes the dialog and the new playlist is already selected and open on Playlists. Checked at a short window (430 px) and at about 1440x900: both buttons stay visible.

30. **History cannot be sorted or filtered.** Sarina: "can also have the option to sort by date, ...".
    - **Status (Nov 4):** **Done:** a Sort control (Newest first / Oldest first) and a Type filter (All, Created, Changed, Restored, Taste profile) on the activity timeline (`ViewFormat.activity`, tested). Taste profile views stay hidden under All unless "Show routine views" is ticked; choosing the Taste profile type shows them. "Deleted" is not offered: a deletion is not recorded as an activity today (only its saved version appears under Saved playlist versions), so that filter would always be empty. Left for a backend decision.

31. **"Why these songs?" shows an internal class name (Discover).** It said "Chosen with the BalancedStrategy strategy."
    - **Status (Nov 4):** **Done:** strategy names are mapped to plain words ("Chosen using a balance of artists you know and new ones."); an unknown name gets a generic phrase, never the class name (`ViewFormat.strategyWords`, tested). The other lines (constraints) were already plain sentences.

32. **The mix line renders squashed ("60%new artists").**
    - **Status (Nov 4):** **Done:** the bold 14 px face drew the space after "%" too tight, and 13 to 15 px still crowd it; the mix line is now 16 px regular and renders every space (compared in a rendered test sheet).

33. **Open and Delete buttons on unselected playlist rows look faint.**
    - **Status (Nov 4):** **Done:** rendered; Open was readable, Delete (muted grey outline) was dim. Delete now has a lighter border and text; it still turns amber on hover.

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
