# Daily Test Notes

What to run each day, and what you should see. Run everything from the project folder.

## Fri Oct 30: foundation (Tasks 1-5)

No new user-facing features today, so there is one check.

```bash
./gradlew build
```

**Expected:** `BUILD SUCCESSFUL`. The first run downloads some test files and can take a few minutes.

**Built today:** PostgreSQL storage, Spotify endpoints matching the Feb 2026 API, the OpenAI provider, central settings in `musicmind.properties`, the agent's tools, and LLM prompt and response checking.

**Not verified live:** nothing has run against real Spotify or OpenAI yet. Those calls were tested against local stand-in servers only.

**Before Saturday's test:** add `SPOTIFY_CLIENT_ID` and `LLM_API_KEY` to `.env`.

## Sat Oct 31: F1, F2, F3, F4, F8, F9 (Tasks 6-8)

**Before you start:** `.env` needs `SPOTIFY_CLIENT_ID` and `LLM_API_KEY`, and PostgreSQL must be running. The first command that reaches Spotify opens your browser to log in. These are the first runs against real Spotify and OpenAI, so anything that differs from the notes below is worth telling me.

### 1. Build

```bash
env -u JAVA_HOME ./gradlew build
```

**Expected:** `BUILD SUCCESSFUL`, with about 400 tests passing. No keys or database server are needed for this step.

### 2. Taste profile (F1)

```bash
./gradlew runCli --args="profile --refresh"
```

**Expected:** a `===== Your MusicMind Taste Profile =====` block with `Generated:`, `Time range: medium`, a percentage per genre, top artists, track counts, a one-sentence `Summary:`, and the line `Listening data and metadata provided by Spotify.`

```bash
./gradlew runCli --args="profile"
```

**Expected:** `Showing your stored profile`, with the same genres and summary. Artist names are replaced by a count, because only ids are stored.

```bash
./gradlew runCli --args="profile --range long"
```

**Expected:** a fresh build with `Time range: long`.

If the summary ends with `[computed from your listening data, not AI-written]`, the LLM call did not work. The fallback itself is correct, but check `LLM_API_KEY`.

### 3. Taste trends (F2)

```bash
./gradlew runCli --args="trends"
```

**Expected:** short, medium and long sections with artists and genres, then `Genres added`, `Genres dropped`, `Artists added`, `Artists dropped`, and a short narrative. A range with no data shows `(no data)` and a `Notice:` line, and the other ranges are still compared.

### 4. Search (F3)

```bash
./gradlew runCli --args="search upbeat indie songs for running"
```

**Expected:** a numbered list of tracks, each with its Spotify URI, then the attribution line.

```bash
./gradlew runCli --args="search zzzzqqqqxxxx"
```

**Expected:** no tracks and a hint to try a simpler search or fewer words.

The line `[plain keyword search: ...]` means your text was searched as typed. That is expected with no LLM key, and otherwise means the LLM reply was unusable or low confidence.

### 5. Discovery (F4)

```bash
./gradlew runCli --args="discover"
```

**Expected:** up to about 20 numbered tracks, each with an id, a score and a reason, then the feedback hint and the attribution line. Copy one track id for step 7.

```bash
./gradlew runCli --args="discover --novelty 0.9"
```

**Expected:** mostly tracks new to you, and the order is stable if you run it twice. A `Notice:` line appears if MusicMind had to widen genres or lower novelty.

### 6. Preferences (F8)

```bash
./gradlew runCli --args="prefs set explicitPolicy block"
```

**Expected:** `Saved explicitPolicy = ...`

```bash
./gradlew runCli --args="prefs set familiarBias 0.4"
```

**Expected:** `Saved familiarBias = ...`

```bash
./gradlew runCli --args="prefs list"
```

**Expected:** both values listed.

```bash
./gradlew runCli --args="prefs set familiarBias 2"
```

**Expected:** an error saying the value must be between 0 and 1, and nothing saved.

Run `discover` again afterwards: explicit tracks should be gone.

### 7. Feedback (F9)

Replace `TRACK_ID` with an id from step 5.

```bash
./gradlew runCli --args="feedback TRACK_ID reject"
```

**Expected:** `Recorded: reject for TRACK_ID`. Run `discover` again: that track should score lower or drop off the list.

### 8. Database down (F8 alternative, optional)

```bash
brew services stop postgresql@17
```

```bash
./gradlew runCli --args="prefs list"
```

**Expected:** the app keeps running and shows `(Showing defaults: ...)` instead of crashing.

```bash
brew services start postgresql@17
```

**Built today:** F1 and F2 through the facade and tools, F3 search with LLM parsing and a labelled keyword fallback, and F4 with all three strategies, F8 preferences and F9 feedback. Each of these has a CLI command.

**Verified:** the build and 285 automated tests, using stand-in Spotify and LLM services and an embedded database. On the CLI itself, only `help` and the usage messages for `prefs` and `feedback` were run.

**Not verified live:**
- `prefs` and `feedback` have not been checked on your account yet.
- Update (Oct 31): Spotify sends no genres to this app, so they now come from MusicBrainz. `profile`, `trends`, `search` and `discover` have since run live. Expect a notice saying how many of your top artists had genre data.
- A very short `discover` list, or one with widening notices, depends on what Spotify returns for your account. Tell me if it looks wrong.
- The GUI was only kept compiling. Its screens for these features arrive with Task 13.
- `create`, `modify`, `validate`, `explain` and `history` still print "implemented in a later phase". They arrive on Sunday.

**Known limits for now:**
- `blockedArtists` takes Spotify artist ids, not names.
- `profile --range` always rebuilds.
- `trends` compares the newest and oldest ranges that have data, so medium is shown but not compared when all three exist.

## Sun Nov 1: the complete CLI (Tasks 10-12)

**Before you start:** `.env` needs `SPOTIFY_CLIENT_ID` and `LLM_API_KEY`, and PostgreSQL must be running (`brew services start postgresql@17`). Log in to Spotify when the browser opens. This section runs `create`, `modify`, `undo`, `validate`, `explain`, `history` and `restore` against real Spotify and OpenAI for the first time.

**Warning: `create` makes a real private playlist in your Spotify library.** `modify`, `undo` and `restore` then change that playlist. Run those three only on the playlist that step 3 creates. Delete it in Spotify when you are done (MusicMind never deletes a playlist except to roll back a failed run). The playlist is private and only you can see it.

### 1. Build the commands

```bash
env -u JAVA_HOME ./gradlew build
env -u JAVA_HOME ./gradlew installDist
```

**Expected:** `BUILD SUCCESSFUL` both times, with about 584 tests passing in the first. The second creates `build/install/musicmind/bin/musicmind` (CLI) and `build/install/musicmind/bin/musicmind-gui` (GUI). The rest of this section uses the CLI script; `./gradlew runCli --args="..."` takes the same arguments.

```bash
build/install/musicmind/bin/musicmind help
```

**Expected:** every command with its syntax: `login`, `help`, `exit`, `profile`, `trends`, `search`, `discover`, `create`, `modify`, `undo`, `validate`, `prefs`, `feedback`, `explain`, `history`, `restore`.

### 2. Login and exit codes

```bash
build/install/musicmind/bin/musicmind login
```

**Expected:** `Logged in to Spotify as <your Spotify user id>.` The line `Opening your browser to connect Spotify...` appears only when no saved login exists (the first run), and not on later runs. If a saved login exists but Spotify rejects it, the browser still opens, with no warning line.

```bash
build/install/musicmind/bin/musicmind frobnicate; echo "status: $?"
```

**Expected:** `Unknown command: frobnicate`, the help list, and `status: 2`. A command that fails (for example Spotify is unreachable) gives `status: 1`; success gives `status: 0`.

### 3. Create a playlist (F5, F11): makes a real private playlist

```bash
build/install/musicmind/bin/musicmind create "a calm 30 minute mix for studying" --minutes 30 --no-explicit --name "MusicMind test"
```

**Expected:** progress lines (`planning...`, `retrieving...`, and so on), then `Created "MusicMind test" (<playlist id>) with N tracks:` with a numbered list, any `Notice:` lines, an explanation, and the Spotify attribution. Total length is within 10% of 30 minutes. Copy the playlist id from the `Created` line. **Every later step that says `<playlist id>` needs that id typed in place of the placeholder**, so nothing is left to guess.

To see the negotiation prompt, ask for something impossible:

```bash
build/install/musicmind/bin/musicmind create "an energetic workout mix" --minutes 600 --familiar 1
```

**Expected:** `Problem:` lines and a numbered list of changes, then a prompt `Choose an option (1-N), or anything else to cancel:`. Type `x` and Enter: `Cancelled. Nothing was created or changed on Spotify.` and `status: 1`. Check your Spotify library: no new playlist.

### 4. Validate and explain (F7, F10)

Replace `<playlist id>` with the id from step 3.

```bash
build/install/musicmind/bin/musicmind validate <playlist id>
```

**Expected:** `Checked against the constraints saved when this playlist was created.` and `PASS: the playlist meets every constraint.` (status 0).

```bash
build/install/musicmind/bin/musicmind explain <playlist id>
```

**Expected:** a short paragraph about why tracks were chosen, mentioning only the strategy, the counts and the reasons recorded at creation. A line ending in `[computed from your listening data, not AI-written]` means the LLM text was rejected or unavailable; that fallback is correct.

### 5. Modify and undo (F6)

Replace `<playlist id>` with the id from step 3. This changes the playlist.

```bash
build/install/musicmind/bin/musicmind modify <playlist id> "remove the first track"
```

**Expected:** `Changed: ...` naming one removal, `The playlist now has N-1 tracks`, and the attribution. Open the playlist in Spotify: one track fewer.

```bash
build/install/musicmind/bin/musicmind undo <playlist id>
```

**Expected:** `Undone: the playlist is back as it was before the last change.` and the track is back in its old place. Because each CLI run is a new process, this uses the snapshot saved before the change.

```bash
build/install/musicmind/bin/musicmind modify <playlist id> "make it calmer"
```

**Expected:** a mood edit labelled `Approximate: Spotify gives no mood data to this app ...`.

Modify can also run into F11. Ask for an edit that cannot be applied as asked:

```bash
build/install/musicmind/bin/musicmind modify <playlist id> "shorten it by 600 minutes"
```

**Expected:** if the edit would remove every track, a `Problem:` line and an option such as `Apply only the first N of M changes, leaving K track(s)` with the prompt `Choose an option (1-1), or anything else to cancel:`. Type `x` and Enter: `Cancelled. The playlist was not changed.` and nothing changes in Spotify. Run it again and type `1`: the smaller edit is applied (undo it with `undo <playlist id>`). If the model answers with a smaller edit that is fine, you will see no prompt. Replace `<playlist id>` with the id from step 3 first.

### 6. History and restore (F12)

```bash
build/install/musicmind/bin/musicmind history
```

**Expected:** the sessions you just ran (`CREATE_PLAYLIST`, `MODIFY_PLAYLIST`, and the earlier profile builds), then `Saved playlist versions (restore <id>):` with a number, time, playlist id, name and track count per line. Copy the number of the oldest version of your test playlist.

Replace `<snapshot id>` with that number. This changes the playlist.

```bash
build/install/musicmind/bin/musicmind restore <snapshot id>
```

**Expected:** `Restored "MusicMind test" (<playlist id>): N tracks. Undo with: undo <playlist id>`. To check the deleted-playlist path, delete the test playlist in Spotify and run the same command again: it says the playlist no longer exists and asks `Recreate it now? (y/N):`; `y` creates a new playlist from the saved version.

### 7. Feedback validation (F9)

```bash
build/install/musicmind/bin/musicmind feedback TRACK_ID reject; echo "status: $?"
```

**Expected:** this is the failing case on purpose. It prints `That is not a Spotify track id...`, `Usage: feedback <trackId> accept|reject`, `status: 2`, and saves nothing. (`TRACK_ID` is typed literally here.) To save a real verdict, take an id from `discover` or `create` output and replace `<track id>`:

```bash
build/install/musicmind/bin/musicmind feedback <track id> reject
```

**Expected:** `Recorded: reject for <track id>`.

### 8. Interactive session

```bash
build/install/musicmind/bin/musicmind
```

Type `prefs list`, press Enter, then `exit`, press Enter.

**Expected:** the help list, a `musicmind>` prompt that runs each line as a command, and `Goodbye.` on `exit`.

### 9. Trends coverage and search order (live fixes)

```bash
build/install/musicmind/bin/musicmind trends
```

**Expected:** under each range a line like `genre data: 12 of 20 artists`. When a range has genre data for fewer than half of its artists you also see a `Notice:` that says genre coverage is partial, and the narrative says so instead of claiming a change in genres. Many Persian artists have no MusicBrainz genres, so partial coverage is likely on your account.

```bash
build/install/musicmind/bin/musicmind search "upbeat indie songs for running"
```

**Expected:** results for indie and pop music, not titles such as "Upbeat Corporate Inspiration". Mood words like `upbeat` are no longer sent to Spotify (it matches them against track titles); MusicMind replaces them with a genre from its mood map (for `upbeat`, `indie pop`). The LLM is also told not to put mood words in the search. With no LLM key, or when the reply is unclear, the text is searched exactly as typed and labelled, so stock titles can still appear then.

**Built today:** F5 create with F11 negotiation, F6 modify and undo, F7 validate, F10 explain, F12 history and restore, F11 prompts during `modify`, the `login` and `exit` commands, mood words kept out of Spotify searches, an interactive session, exit statuses, track-id validation for `feedback`, genre coverage in `trends`, retuned search ranking, and `bin/musicmind` and `bin/musicmind-gui` from `installDist`.

**Verified:** the build and the automated tests (stand-in Spotify and LLM services, embedded database). On the real scripts only `help`, an unknown command (status 2), `feedback` with a bad id (status 2, nothing saved), and the GUI script starting (JavaFX loads) were run. The GUI window was not exercised.

**Not verified live:**
- `create` (including the F11 prompt), `modify`, `undo`, `validate`, `explain`, `history` and `restore` against your real Spotify account and OpenAI. Expect surprises in how well the LLM reads edits, in mood edits, and in how Spotify answers for playlists you do not own.
- Whether the browser warning prints exactly once on your machine, and not on later runs.
- How the LLM turns real instructions such as "shorten it by 600 minutes" into steps, and whether the new search query text gives good results on your account.
- The coverage threshold (`trends.minGenreCoverage`, 0.5) and the new search weights (0.9 Spotify order, 0.1 title match) on your real data. Tell me if `trends` is too cautious or `search` still puts odd titles first.
- `restore` for a deleted playlist, and the interactive session on a real terminal.

**Known limits for now:**
- A playlist saved before `savedCapped` was stored is treated as not cut short.
- The F11 prompt in `modify` covers edits that would leave the playlist outside its length limits (for example empty). An unclear edit still stops with a message asking you to rephrase.
- `explain` and `history` work from stored data, so the track titles are not shown (only ids and counts).

### Update after live checks (Nov 1)

Already run live on the test playlists: `create`, `validate`, `explain`, `history`, `modify` and `undo`. Stage 1's example gave 27 tracks with 8 by new artists (30%), and "make it 15 minutes shorter" removed 3 tracks and `undo` put them back.

**Known limit:** after a deliberate length change, `validate` still compares against the original target, so it reports a duration failure. Still to check live: `restore` and the negotiation prompt.
