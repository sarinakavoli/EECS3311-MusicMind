# MusicMind: AI Music Discovery and Playlist Management Agent

**EECS3311 Software Design, course project**

MusicMind is an AI-agent application. It reads your Spotify listening data, interprets a
music goal written in plain words ("a 90 minute study playlist, mostly familiar but with some new
artists"), checks the goal against constraints, builds its own recommendations from the data
Spotify still exposes, and creates or edits real Spotify playlists for you. It has a graphical
interface (JavaFX) and a command-line interface (CLI) that share one application core.

- **Author:** Sarina Kavoli
- **Student ID:** 220816757
- **Course:** EECS3311 (Software Design), Fall 2026

## Status

| Stage | Deliverable | Status |
|-------|-------------|--------|
| Stage 1 | Design report (`docs/`) | Done |
| Stage 2 | Implementation | All 12 features implemented; report written (`docs/report/stage2-report.pdf`); GUI redesign and demonstration videos still to do |
| Stage 3 | Verification and testing | Not started |

This is one repository used across all three stages.

- Stage 1 to code mapping, with tests and what was tried live: `docs/STAGE1_CONFORMANCE.md`
- Every change from the Stage 1 design (Original -> Change -> Reason -> Impact): `docs/STAGE2_PLAN.md`
- AI tools and collaboration log: `docs/AI_COLLABORATION_LOG.md`
- What was run against the real accounts, and what was not: `docs/DAILY_TEST_NOTES.md`
- The GUI is a plain tabbed form today. Known problems and the planned redesign are in
  `docs/GUI_TEST_FINDINGS.md`.

## Features

All twelve Stage 1 features work in the CLI and the GUI.

| ID | Feature | CLI command |
|----|---------|-------------|
| F1 | Music taste profile | `profile [--refresh] [--range short\|medium\|long]` |
| F2 | Taste-change analysis | `trends` |
| F3 | Natural-language music search | `search "<query>"`, `candidates list\|add\|remove\|clear` |
| F4 | Personalized discovery | `discover [--novelty 0.3]` |
| F5 | AI playlist planning and generation | `create "<goal>" [--minutes 90] [--no-explicit] [--familiar 0.7] [--name "..."] [--no-candidates]` |
| F6 | Natural-language playlist modification | `playlists`, `show <id>`, `modify <id> "<instruction>"`, `undo <id>` |
| F7 | Playlist constraint validation | `validate <id>` |
| F8 | Preference memory | `prefs list`, `prefs get <key>`, `prefs set <key> <value>` |
| F9 | Recommendation feedback | `feedback <trackId> accept\|reject` |
| F10 | Grounded explanation | `explain <playlistId>` |
| F11 | Infeasible-constraint negotiation | asked during `create` and `modify` |
| F12 | History and snapshot/restore | `history [<id>]`, `restore <snapshotId>` |
| (new) | Delete one of your playlists | `delete <playlistId>` |

Run `help` for the full list.

## Prerequisites

1. **JDK 17 or newer** (`java -version`). Gradle can also download a Java 17 toolchain by itself
   the first time, which needs internet access.
2. **PostgreSQL** (tested with version 17). MusicMind stores preferences, feedback, history,
   snapshots, candidates and a genre cache there. It is only needed to run the app, not to build
   or test it.
3. **A Spotify account with Premium**, and a free **Spotify Developer app**:
   - Open <https://developer.spotify.com/dashboard> and create an app.
   - Add this Redirect URI exactly: `http://127.0.0.1:8080/callback`
   - Copy the app's **Client ID**. MusicMind uses the Authorization Code with PKCE flow, so there is
     no client secret to copy.
   - Under **User management** in the app's settings, add the Spotify account you will log in with
     (a Development Mode app only allows listed users).
4. **An OpenAI API key** with some credit (the default model is `gpt-4o`). Without a key the app
   still runs; features that need the model say so, and F1, F2 and F3 use labelled fallbacks.

You do not need to install Gradle. The wrapper (`./gradlew`) does it.

## Install dependencies

```bash
git clone https://github.com/sarinakavoli/EECS3311-MusicMind.git
cd EECS3311-MusicMind
./gradlew build
```

The first build downloads Gradle 9.1, the libraries (JavaFX 21, the PostgreSQL driver, Gson,
dotenv-java, JUnit) and the embedded PostgreSQL binaries the tests use. It takes a few minutes.
A passing build ends with `BUILD SUCCESSFUL` and runs the unit tests (846 of them) with no keys and
no database server.

## Set up PostgreSQL

Create a login role and an empty database once. On macOS with Homebrew:

```bash
brew install postgresql@17
brew services start postgresql@17
```

Then, with any PostgreSQL install (change the password to your own):

```bash
psql postgres -c "CREATE ROLE musicmind LOGIN PASSWORD 'choose-a-password';"
psql postgres -c "CREATE DATABASE musicmind OWNER musicmind;"
```

On Linux you may need `sudo -u postgres psql -c "..."` in place of `psql postgres -c "..."`.
MusicMind creates its tables the first time it connects, so there is nothing else to run.

## Local configuration (keys stay on your machine)

```bash
cp .env.example .env
```

Open `.env` in an editor and fill in your own values:

| Setting | What to put |
|---------|-------------|
| `SPOTIFY_CLIENT_ID` | The Client ID from the Spotify dashboard |
| `SPOTIFY_REDIRECT_URI` | Leave as `http://127.0.0.1:8080/callback` (must match the dashboard) |
| `LLM_API_KEY` | Your OpenAI key |
| `LLM_MODEL` | Leave as `gpt-4o`, or another OpenAI chat model |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/musicmind` |
| `DATABASE_USER` | `musicmind` |
| `DATABASE_PASSWORD` | The password you chose above |

`.env` is git-ignored; only `.env.example`, which holds placeholders, is committed. The app reads
`.env` from the folder you start it in, so run the commands below from the project folder. After you
log in to Spotify, the login token is kept in `.spotify-tokens.json`, which is git-ignored too.
Never paste real keys into source files, reports, or recordings.

## Run

### GUI

```bash
./gradlew run
```

A window opens with seven tabs: Profile, Trends, Search, Discover, Playlists, Preferences, History.
Opening it does not contact Spotify. Press **Log in to Spotify** first; the first time, your
browser opens for you to approve access.

### CLI

```bash
./gradlew runCli --args="help"          # list every command
./gradlew runCli --args="profile"       # run one command
```

Or install the scripts once and call them directly:

```bash
./gradlew installDist
build/install/musicmind/bin/musicmind help
build/install/musicmind/bin/musicmind              # no arguments: an interactive session
build/install/musicmind/bin/musicmind-gui          # the GUI
```

On Windows use `musicmind.bat` and `musicmind-gui.bat` in the same folder. A command exits with
status 0 when it works, 1 when it could not finish, and 2 for a wrong command or arguments.

## Example usage

The first command that reaches Spotify opens your browser to log in. Below, `musicmind` stands for
`build/install/musicmind/bin/musicmind` (or use `./gradlew runCli --args="..."`).

```bash
musicmind login                                  # connect Spotify and show the account
musicmind profile --refresh                      # F1: genre mix, top artists, one-line summary
musicmind trends                                 # F2: how your taste changed across time ranges
musicmind search "upbeat indie songs for running"   # F3: tracks, then add some to candidates
musicmind candidates add <trackId>
musicmind discover --novelty 0.7                 # F4: new tracks, each with a reason
musicmind prefs set explicitPolicy block         # F8: standing rules for every playlist
musicmind feedback <trackId> reject              # F9: later picks learn from it
```

**These commands change your real Spotify library** (`create` makes a private playlist; `modify`,
`undo`, `restore` and `delete` change or remove playlists). Try them on a test playlist:

```bash
musicmind create "a calm 30 minute mix for studying" --minutes 30 --no-explicit --name "MusicMind test"
musicmind playlists                              # find the playlist id
musicmind validate <playlistId>                  # F7: pass or fail per rule
musicmind explain <playlistId>                   # F10: why these tracks
musicmind modify <playlistId> "remove the first track"
musicmind undo <playlistId>
musicmind history                                # F12: sessions and saved versions
musicmind restore <snapshotId>
```

If a request cannot be met (for example 600 minutes of only familiar music), MusicMind lists the
problem and some changes to choose from (F11). Choosing nothing cancels without writing anything.

## What works without keys

| Missing | What happens |
|---------|--------------|
| No Spotify Client ID | The app starts, and every feature that needs Spotify says so. |
| No `LLM_API_KEY` | Features that need the model say so. F1 summary, F2 narrative and F3 search use a computed fallback, labelled as such. |
| PostgreSQL not running | The app keeps running with safe default preferences and shows a warning; history and saved data are unavailable. |

## Troubleshooting

- **Port 8080 busy during login:** close the program using it, or change the port in both the
  Spotify dashboard and `SPOTIFY_REDIRECT_URI`.
- **`Could not execute test class` for a file such as `Foo 2.class`:** a synced folder (iCloud) copied
  files into `build/`. Run `./gradlew clean build`.
- **The GUI does not start on a remote machine:** it needs a desktop session.

## Repository structure

```
MusicMind/
├─ README.md
├─ CLAUDE.md                 # notes for Claude Code, the AI development tool
├─ build.gradle, settings.gradle, gradlew   # Gradle 9.1 wrapper build
├─ .env.example              # template for keys (never commit real keys)
├─ docs/
│  ├─ MusicMind_Stage1_Report.docx
│  ├─ STAGE1_CONFORMANCE.md  # Stage 1 feature -> classes, tests, entry points
│  ├─ STAGE2_PLAN.md         # status and every change from Stage 1
│  ├─ STAGE2_IMPLEMENTATION_PLAN.md   # the binding implementation plan
│  ├─ AI_COLLABORATION_LOG.md
│  ├─ DAILY_TEST_NOTES.md    # what to run, and what was run live
│  ├─ GUI_TEST_FINDINGS.md
│  └─ diagrams/              # UMLet sources (uxf) and exported images (png), Stage 1 versions
└─ src/
   ├─ main/java/com/musicmind/   # see src/README.md
   ├─ main/resources/            # musicmind.properties, mood-genres.json
   └─ test/java/com/musicmind/   # JUnit 5 tests
```

## Diagrams (UMLet)

All UML diagrams are authored in **UMLet**. The editable sources are in `docs/diagrams/uxf/`. To
view or export them, open the `.uxf` file in [UMLet](https://www.umlet.com/) and use
`File -> Export as...`. The `.uxf` files and the PNGs in `docs/diagrams/png/` are the updated Stage 2
diagrams; the Stage 1 PNGs are kept in `docs/diagrams/png/stage1/`, and `docs/diagrams/README.md` lists what changed.

| File | Diagram |
|------|---------|
| `MusicMind_ClassArchitecture.uxf` | Class diagram: application and agent architecture |
| `MusicMind_Domain.uxf` | Class diagram: domain model |
| `MusicMind_UseCase.uxf` | Use-case diagram |
| `MusicMind_SD01_CreatePlaylist.uxf` | Sequence: create playlist |
| `MusicMind_SD02_ModifyPlaylist.uxf` | Sequence: modify playlist with undo |
| `MusicMind_SD03_TasteProfile.uxf` | Sequence: view taste profile |
| `MusicMind_SD04_Discover.uxf` | Sequence: discover and feedback |

## Stage 2 report (rebuilding the PDF)

The report is `docs/report/stage2-report.pdf`. It is generated from the tracked source
`docs/report/stage2-report.html` (self-contained CSS; the UML images are read from `docs/diagrams/png/`)
with headless Google Chrome. The UML images are first cropped into readable per-page figures
(`docs/report/figures/`) by `docs/report/make-figures.py`, which needs Python 3 and Pillow (`pip install pillow`).
After editing the HTML, rebuild the PDF from the project folder:

```bash
docs/report/build-report.sh
```

The script expects Chrome at `/Applications/Google Chrome.app` (macOS). On another system, set `CHROME` to
the browser executable, for example `CHROME=/usr/bin/google-chrome docs/report/build-report.sh`.

**Refresh before submission:** the merge status of pull requests #4 and #5, the Period 3 commit count,
the demonstration video links, the AI token and money costs, and the GUI redesign status. Search the
HTML for `REFRESH BEFORE SUBMISSION` to find each spot, edit it, and rebuild.

## External services and compliance

- Music data and playlist actions go through the **Spotify Web API**, only behind the
  `MusicService` interface (Adapter pattern). Only endpoints that Development Mode apps may still
  call are used; discovery is built from your own listening data, not Spotify's retired recommender.
- Spotify sends no artist genres to this app, so genres come from **MusicBrainz**
  (<https://musicbrainz.org>) and are cached by artist id.
- Playlist goals are interpreted by an **OpenAI** model. Prompts carry your own words, ids, counts and
  MusicBrainz genres. They never contain Spotify track titles, artist names or album names. The
  goal-fit review sends only track ids and genres, and may only name ids to remove.
- Per the Spotify Developer Terms: no model is trained on Spotify data, Spotify content (titles,
  artist and album names) is not stored durably (only ids and MusicMind's own derived data), and
  content is attributed to Spotify in both interfaces.
- No client secret, access token or API key is ever committed. Keys live in `.env`.
