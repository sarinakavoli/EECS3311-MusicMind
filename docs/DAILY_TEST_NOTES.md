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

**Expected:** `BUILD SUCCESSFUL`, with 285 tests passing. No keys or database server are needed for this step.

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
- Nothing has run against real Spotify or OpenAI. The commands above, from `profile` onwards, have never run on your account.
- Artist genres are the main risk. If `profile` shows `(no genre data)` even with plenty of listening history, Spotify is probably not returning genres for this app, and that also weakens `trends` and `discover`. Tell me if you see it.
- A very short `discover` list, or one with widening notices, depends on what Spotify returns for your account. Tell me if it looks wrong.
- The GUI was only kept compiling. Its screens for these features arrive with Task 13.
- `create`, `modify`, `validate`, `explain` and `history` still print "implemented in a later phase". They arrive on Sunday.

**Known limits for now:**
- `blockedArtists` takes Spotify artist ids, not names.
- `profile --range` always rebuilds.
- `trends` compares the newest and oldest ranges that have data, so medium is shown but not compared when all three exist.
