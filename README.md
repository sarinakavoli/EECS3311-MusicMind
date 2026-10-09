# MusicMind — AI Music Discovery & Playlist Management Agent

**EECS3311 Software Design — Course Project**

> An AI-agent application that interprets a listener's higher-level music goal,
> reasons over constraints, builds its own recommendations from the data Spotify
> still exposes, and creates or edits real Spotify playlists on the user's behalf —
> with a GUI and a CLI over a shared application core.

- **Author:** _[your full name]_
- **Student ID:** _[your student id]_
- **Course:** EECS3311 (Software Design), Fall 2026

---

## Status

| Stage | Deliverable | Status |
|-------|-------------|--------|
| Stage 1 | Design report (this repo, `docs/`) | In progress |
| Stage 2 | Implementation | Not started |
| Stage 3 | Verification & testing | Not started |

This is **one repository used across all three stages** (per the assignment).

## Repository structure

```
MusicMind/
├─ README.md
├─ .gitignore
├─ .env.example              # template for API keys (never commit real keys)
├─ docs/
│  ├─ MusicMind_Stage1_Report.docx
│  └─ diagrams/
│     ├─ uxf/                # UMLet source files (.uxf) — the editable diagrams
│     └─ png/                # images exported from UMLet (see docs/diagrams/README.md)
└─ src/                      # application code (added in Stage 2)
```

## Diagrams (UMLet)

All UML diagrams are authored in **UMLet**. The editable source files are in
`docs/diagrams/uxf/`. To view or export them:

1. Open [UMLet](https://www.umlet.com/) (standalone or the UMLetino web version).
2. `File → Open` the `.uxf` file.
3. Rearrange boxes if you like, then `File → Export as…` → PNG/PDF/SVG into `docs/diagrams/png/`.

| File | Diagram |
|------|---------|
| `MusicMind_ClassArchitecture.uxf` | Class diagram — application & agent architecture |
| `MusicMind_Domain.uxf` | Class diagram — domain model |
| `MusicMind_UseCase.uxf` | Use-case diagram |
| `MusicMind_SD01_CreatePlaylist.uxf` | Sequence — create playlist |
| `MusicMind_SD02_ModifyPlaylist.uxf` | Sequence — modify playlist with undo |
| `MusicMind_SD03_TasteProfile.uxf` | Sequence — view taste profile |
| `MusicMind_SD04_Discover.uxf` | Sequence — discover & feedback |

## External API & compliance notes

- Music data and playlist actions go through the **Spotify Web API**, accessed only
  behind the internal `MusicService` interface (Adapter pattern).
- The design uses only endpoints still available to Development-Mode apps after the
  Nov 2024 and Feb 2026 Web API changes; discovery is built from the user's own
  listening data rather than Spotify's deprecated recommender.
- Per the Spotify Developer Terms: no model is trained on Spotify data, Spotify
  content is not durably cached, and content is attributed to Spotify.
- User data uses the **Authorization Code with PKCE** flow. **No client secret or
  access token is ever committed** — use `.env` (git-ignored); see `.env.example`.

## Build & run

_To be added in Stage 2._ Planned stack: Java (JavaFX GUI + CLI), PostgreSQL, one
hosted LLM behind an `LLMProvider` interface.
