# MusicMind — Demonstration Video Script (Stage 2)

Two recordings cover every feature: **Video 1 (GUI)** and **Video 2 (CLI)**. Say each feature ID
and name out loud when you start its segment, and write down the start time (mm:ss) of each segment
for the report's Section 7.

**Before recording**
- Close `.env`, password managers and anything with keys. Turn on Do Not Disturb.
- Start PostgreSQL (`brew services start postgresql@17`) and the app (`./gradlew run`). You should
  already be logged in ("Logged in as Sarina" at the top right), so no Spotify password appears.
- Record with ⌘ Cmd + Shift + 5 → Record Selected Portion → Options: your microphone, Show Mouse Clicks.

---

## Video 1 — GUI

### 0. Introduction (about 20 s)
**Say:** "This is MusicMind, an AI music discovery and playlist agent for Spotify, written in Java with
a JavaFX GUI and a command-line interface. It uses the Spotify Web API for my listening data and
playlists, Last.fm for genre and song tags, PostgreSQL for memory, and OpenAI's gpt-4o as the LLM.
The left menu has seven screens. I'll show each feature by its ID."

### F1 — Music Taste Profile (Your Taste)
**Do:** Click **Your Taste** → choose **Past 6 months** → **Refresh profile**. Scroll to Top artists,
Top tracks and **Summary**. Then switch to **Past week** and refresh once.
**Say:** "F1, Music Taste Profile. I pick a time range and press Refresh. MusicMind reads my top
artists and tracks from Spotify, gets genres from Last.fm tags, and computes my top genres
deterministically. The Summary at the bottom is written by the LLM from that computed profile; only
genres and counts are sent to it, never song titles. The profile is saved, so it opens instantly next
time."
**Alternative case:** "Past week is built from my recent plays, because Spotify has no weekly top list."

### F2 — Taste-Change Analysis (Trends)
**Do:** Click **Trends** → **Compare my listening**. Scroll the table.
**Say:** "F2, Taste-Change Analysis. MusicMind compares my past year, past six months and past month.
The table shows which genres and artists are new or dropped, computed deterministically. The paragraph
on top is the LLM's narrative of that comparison."

### F3 — Natural-Language Music Search (Find Songs)
**Do:** Click **Find Songs** → type `mellow songs for studying` → **Search**. Then type `american girls`
→ **Search**. Click **+ Add** on one song.
**Say:** "F3, Natural-Language Music Search. I can search by meaning or by name. For a description,
the LLM turns my words into a structured query, MusicMind searches Spotify, and for a mood it also
takes Last.fm's top songs for that tag, matched to real Spotify tracks. A song name is matched
directly. Add puts a song in the waiting list for my next playlist."
**Error case:** search a nonsense word like `qzxqzx` and show the "no results" message with its hint.

### F9 — Recommendation Feedback
**Do:** In the search results, press **♥** on one song and **⊘** on another.
**Say:** "F9, Recommendation Feedback. The heart means I like a song, the crossed circle means it's not
for me. The choice is saved, and Discover and Create use it: liked songs rank higher, rejected songs
rank lower."

### F4 — Personalized Discovery (Discover)
**Do:** Click **Discover** → untick **Use my usual mix** → move the slider toward **New artists** →
**Find music** → open **Why these songs?**
**Say:** "F4, Personalized Discovery. The slider sets how many new artists versus artists I know. A
recommendation strategy is chosen from this mix, and MusicMind builds candidates from my genres, my
artists' albums and artists Last.fm lists as similar. It excludes songs already in my library and
anything I blocked. Why these songs lists the recorded reason for each song; this feature is
deterministic, no LLM."

### F5 — AI Playlist Planning and Generation (Create)
**Do:** Click **Playlists** → **Create playlist…**. First press **Create** with the goal empty.
Then type `slow dance`, set **0 hours 20 minutes**, untick **Include songs with explicit lyrics**,
type the name `Demo slow dance`, press **Create**. Wait for the progress words.
**Say:** "F5, AI Playlist Planning and Generation. I describe the playlist I want. The LLM turns my
goal into a plan: length, mood, genres, search terms and Last.fm tags. Then the agent retrieves
candidates, selects songs with the balanced strategy, checks them against my rules, and only then
creates the playlist in Spotify. The LLM never names or adds a song; selection is deterministic."
**Error case:** "If I press Create without a goal, it asks me to say what playlist I want first."

### F10 — Grounded Recommendation Explanation
**Do:** In the Create result, open **Why these songs?** (or later, **Why these?** under the opened playlist).
**Say:** "F10, Grounded Explanation. This explanation is written by the LLM, but only from the
decision trace MusicMind recorded: the reason for each song and the rules satisfied. The model sees
track ids, not titles; MusicMind puts the titles back in on my computer. A checker rejects any claim
that isn't in the trace."

### F7 — Playlist Constraint Validation
**Do:** Click **Open playlist** (or open it from the list) → **Check against my rules**.
**Say:** "F7, Constraint Validation. MusicMind checks the playlist against the length, explicit and
blocked-artist rules it was created with, plus my current blocks, and shows the badge. If a rule
were broken, the badge would turn amber and list the problem. This feature is deterministic."

### F6 — Natural-Language Playlist Modification
**Do:** With the playlist open, type `remove the sad songs` → **Modify**. Read the result message.
Then press **Undo**.
**Say:** "F6, Natural-Language Modification. I say what to change in plain words. The LLM turns it
into commands like add, remove, replace or reorder; for each song it sees the id, length and its
Last.fm tags, never the title. MusicMind saves a copy first, applies the commands as Command objects,
and checks the result again. Undo reverses the last change."
**Alternative case:** the result note says how many songs had no tags and were left as they are.

### F11 — Infeasible-Constraint Negotiation
**Do:** **Create playlist…** → goal `a 600 minute mix of only songs I have never heard`, length
**10 hours 0 minutes**, untick **Let the AI choose the mix**, move the slider fully to **New artists**
→ **Create**. When the dialog appears, read the options, then press **Cancel**.
**Say:** "F11, Infeasible-Constraint Negotiation. This request can't be met: there aren't enough new
songs to fill ten hours. Instead of failing or quietly breaking a rule, the agent explains the conflict
and offers options. Cancel creates nothing; choosing an option would create the closest playlist."
(If no dialog appears because Spotify found enough songs, say so and show the CLI version in Video 2.)

### F8 — Preference Memory (Your Rules)
**Do:** Click **Your Rules**. Select **Block explicit songs** and press the **Save** that appears.
Under **Artists to never use**, type an artist → **Find** → **Block** the right one. Show **Blocked now**,
then **Remove** it.
**Say:** "F8, Preference Memory. My rules are saved in PostgreSQL and every playlist and recommendation
follows them: the explicit setting, my usual mix, artists and songs to never use, and preferred
styles. Save only appears when something changed."

### F12 — Agent History and Snapshot/Restore (History)
**Do:** Click **History**. Show **Activity**, change **Sort** and **Type**. Scroll to **Earlier versions
of your playlists** → press **Restore** on one → show the preview → **Cancel** (or confirm on a test
playlist).
**Say:** "F12, History and Restore. Activity lists everything MusicMind did. Before every change it
saves a copy of the playlist, so I can preview an earlier version and restore it."

### New — Delete a playlist
**Do:** **Playlists** → **Delete** on `Demo slow dance` → read the confirm dialog → confirm.
**Say:** "This feature is new in Stage 2. Delete saves a copy first, then asks me to confirm, and only
then removes the playlist from my Spotify library."

---

## Video 2 — CLI

**Before:** in the terminal, from the project folder, run `./gradlew installDist` once (off camera).

### CLI introduction
**Do:** `build/install/musicmind/bin/musicmind help`
**Say:** "This is the MusicMind command-line interface. It uses the same controller and agent as the
GUI, so every feature works the same way here."

### F1 and F2
**Do:** `build/install/musicmind/bin/musicmind profile --range short`, then `... trends`
**Say:** "F1 profile and F2 trends from the command line, with the same computed data and LLM summary."

### F3 and F9
**Do:** `... search "mellow songs for studying"`, then `... feedback <a track id from the results> accept`
**Say:** "F3 search by meaning, and F9 feedback on a track by its id."

### F4
**Do:** `... discover --novelty 0.7`
**Say:** "F4 discovery with seventy percent new artists."

### F5 (+ F11 if prompted)
**Do:** `... create "workout" --minutes 20 --no-explicit --name "CLI workout"`
**Say:** "F5 creates the playlist from my goal. If the request can't be met, F11 asks me here to choose an option."

### F6, F7, F10
**Do:** `... playlists` (copy the new id), then:
`... modify <id> "remove the sad songs"`, `... undo <id>`, `... validate <id>`, `... explain <id>`
**Say:** "F6 modify and undo, F7 validate against the rules, and F10 the grounded explanation."

### F11 (CLI)
**Do:** `... modify <id> "shorten it by 600 minutes"` → read the Problem line and options → type `x` to cancel.
**Say:** "F11 in the CLI: the edit would empty the playlist, so the agent offers a smaller change. I cancel, and nothing changes."

### F8 and F12
**Do:** `... prefs list`, `... prefs set explicitPolicy block`, `... history`, `... restore <snapshot id>` (answer the confirm)
**Say:** "F8 preferences and F12 history and restore."

### Delete
**Do:** `... delete <id>` and type the playlist name to confirm.
**Say:** "Delete asks me to type the playlist's name before removing it."

---

**After recording:** upload both videos to YouTube as **Unlisted**, then send the two links and the
start time of every feature. The report's Section 2 video cells and Section 7 index get filled from them.
