# MusicMind demo video script (Nov 24): one task in the GUI, then the same task in the CLI

What the professor asked: "Show how to complete a task using the graphical user interface (GUI), then
demonstrate how to complete it using the command-line interface (CLI)."

Target length: 10 to 15 minutes. Upload to YouTube as **Unlisted**.

Every CLI command below is complete: copy one block at a time, paste it into the terminal and press Enter.
Ids are never typed by hand. A few blocks save an id in a variable (`ID`, `TRACK`, `SNAP`) and the later
blocks use it, so run the blocks **in order, in the same terminal window**.

## Before you press record
- Close anything that shows `.env` or `.spotify-tokens.json`. Don't open `.env` in the editor.
- Make sure PostgreSQL is running.
- Open Spotify (app or web) in another window, to show the new playlists.
- In the terminal, go to the project and build the jar (about 20 seconds):

```bash
cd ~/Projects/EECS3311-MusicMind
```
```bash
./gradlew cliJar
```

- Make the terminal font large (Cmd and + a few times), then clear the screen:

```bash
clear
```

---

## Part 0: Intro (30 s)
Say: "This is MusicMind, my EECS3311 project, an AI agent that plans and creates Spotify playlists. I'll
complete a task in the GUI, then complete the same task in the command-line interface, using the standalone
jar run as `java -jar ... -LLMAPI -input task.txt`."

## Part 1: The task in the GUI (about 4 min)
**Task: "Create a 30-minute playlist of upbeat songs for a morning run, with no explicit songs."**

Start the GUI from a **second** terminal tab (Cmd+T), so the first tab keeps the CLI variables:
```bash
cd ~/Projects/EECS3311-MusicMind && ./gradlew run
```

1. The app opens already logged in. Click **Playlists**, then **Create playlist...**
2. Under **What playlist do you want?** type `30 minutes of upbeat songs for a morning run`
3. **How long?** 0 hours, 30 minutes.
4. Untick **Include songs with explicit lyrics**.
5. **Name (optional):** `Morning Run GUI`
6. Click **Create**. While it works, say: "The agent plans the request with the LLM, retrieves songs from my
   listening and from new artists, selects them deterministically, validates the rules, then creates the
   playlist on Spotify."
7. Show the result and open **Why these songs?** Say: "The explanation is built only from the recorded
   decisions."
8. Close the dialog. Open **Morning Run GUI** in the list and point at **Meets your rules**.
9. Switch to Spotify and show the new playlist.

## Part 2: The same task in the CLI (about 4 min)
Go back to the first terminal tab.

1. Show the options. Scroll to **Options** and say: "`--input` runs a task file, and `-LLMAPI` chooses the
   LLM: openai or none."
```bash
java -jar build/libs/musicmind-cli.jar help
```

2. Show the task file. Say: "One command per line. Lines starting with # are comments."
```bash
cat examples/task-create.txt
```

3. Run it. Point at the states (planning, retrieving, selecting, validating, executing, done), the created
   playlist and its tracks, the explanation, and the playlist list at the end.
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI -input examples/task-create.txt
```

4. Switch to Spotify and show "Morning Run Test".

5. The same jar reads commands from standard input, and can run with no LLM:
```bash
echo "profile" | java -jar build/libs/musicmind-cli.jar -LLMAPI none -input -
```

6. A read-only task file covering F1 to F4 (profile, trends, search, discover, playlists):
```bash
cat examples/task.txt
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI -input examples/task.txt
```

## Part 3: Quick tour of the other features, GUI then CLI (about 4 min, optional)

### GUI (Playlists screen, with Morning Run GUI open)
- **F6:** type `remove the last 2 songs` and click **Modify**, then click **Undo**.
- **F9:** click ♥ on one song and ⊘ on another.
- **F11:** **Create playlist...** with the goal `a 600 minute mix of only songs I have never heard`, 10 hours
  0 minutes. Untick **Let the AI choose the mix from my goal** and slide the mix to all new artists. Click
  **Create**, show the dialog "This request cannot be met as asked", then click
  **Cancel (nothing is created or changed)** and **Close**.
- **F12:** **History**, then **Restore** on a Morning Run GUI version. Show the preview, then **Cancel**.
- **Delete:** **Playlists**, **Delete** on Morning Run GUI, then **Delete** in the dialog.

Close the GUI window.

### CLI (first terminal tab)
Save the id of Morning Run Test in `ID` (no output; that's expected):
```bash
ID=$(java -jar build/libs/musicmind-cli.jar -LLMAPI playlists | grep -m1 "Morning Run Test" | sed -E 's/.*\(([A-Za-z0-9]{22})\).*/\1/')
```

**F6 show:**
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI show "$ID"
```

**F7 validate:**
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI validate "$ID"
```

**F10 explain:**
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI explain "$ID"
```

**F6 modify, then undo:**
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI modify "$ID" "remove the last 2 songs"
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI undo "$ID"
```

**F9 feedback** (saves the first song's id in `TRACK`, then likes it):
```bash
TRACK=$(java -jar build/libs/musicmind-cli.jar -LLMAPI show "$ID" | grep -m1 -E '^ +1\. ' | sed -E 's/.*\(([A-Za-z0-9]{22})\).*/\1/')
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI feedback "$TRACK" accept
```

**F8 preferences:**
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI prefs list
```

**F11 negotiation** (the options appear; press **Enter** to cancel, nothing is created):
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI create "a 600 minute mix of only songs I have never heard" --minutes 600 --familiar 0
```

**F12 history and restore** (shorten the playlist again, then restore the saved 7-song version):
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI modify "$ID" "remove the last 2 songs"
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI history
```
```bash
SNAP=$(java -jar build/libs/musicmind-cli.jar -LLMAPI history | grep -m1 -E '"Morning Run Test" +7 tracks' | awk '{print $1}')
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI restore "$SNAP"
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI show "$ID"
```

**Delete** (the name answers the "type its name" question):
```bash
printf 'delete %s\nMorning Run Test\n' "$ID" | java -jar build/libs/musicmind-cli.jar -LLMAPI -input -
```
```bash
java -jar build/libs/musicmind-cli.jar -LLMAPI playlists
```

## Part 4: Closing (20 s)
Say: "The README documents every option and has this working example. Thanks for watching."

After uploading, send Claude the link. It goes in the README and in report Sections 2 and 7.
