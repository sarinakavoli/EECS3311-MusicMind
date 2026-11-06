# Diagrams

The `uxf/` folder holds the **UMLet source files** — these are the editable,
authoritative diagrams for the report.

## How to open and export

1. Download UMLet (standalone) from https://www.umlet.com/ — it is a single Java
   app — or use the browser version **UMLetino** at https://www.umletino.com/.
2. `File → Open` a `.uxf` file from `uxf/`.
3. Adjust box positions / line routing if needed (drag boxes; relation lines
   re-route automatically).
4. `File → Export as…` and save a **PNG** (or PDF/SVG) into the `png/` folder,
   using the same base name, e.g. `MusicMind_Domain.png`.

## Notation used

- `<<interface>>` / `<<abstract>>` stereotypes mark interfaces and abstract classes.
- Member visibility: `+` public, `-` private, `#` protected; an _italic_ method is abstract.
- Relationships: association (plain/▷ open arrow), generalization (△ hollow triangle),
  realization (dashed △), dependency (dashed → ), aggregation (◇ hollow diamond),
  composition (◆ filled diamond). Multiplicities are shown with `m1=/m2=`.

## Files

| File | Diagram | What changed in Stage 2 |
|------|---------|-------------------------|
| `MusicMind_ClassArchitecture.uxf` | Class diagram: application and agent architecture | Rebuilt for readability. Every Stage 1 class and member is kept; Stage 2 members are listed under a "Stage 2:" line in the same box. New classes: `GenreEnrichingMusicService` (Decorator), `GenreService` and `TagService` / `LastFmAdapter` (Adapter; MusicBrainz until Nov 5), `TagTrackMatcher`, `SongTagReader`, `GoalFitReviewer`, `PlaylistEditor`, `PlaylistContext`, `Retrieval`, `TopItems`, `NegotiationHandler`, `Negotiator`, `RunContext`, `RunServices`, `StrategySelector`, `BatchCommand`, `RestoreSnapshotCommand`, `DeletePlaylistCommand`, `CandidateRepository`, `GenreRepository`. Pattern relations: `RecommendationEngine` holds a `RecommendationStrategy` and a `StrategySelector` (which selects the strategy); `PlaylistManager` holds its `PlaylistCommand` history; `ToolManager` holds its `Tool`s; `MusicAgentFacade` reaches `PlaylistManager` through `CreateSupport` and creates the `AgentSession` that runs the `SessionState` chain. `Planner`, `GoalFitReviewer`, `PlaylistEditor` and `ExplanationService` use `PromptBuilder` and `ResponseParser`; `Retrieval`, `PlaylistContext` and `TopItems` are linked to their users. Additive members on `AppController`, `MusicAgentFacade`, `MusicService`, `MemoryManager`, `PlaylistManager`, `PlaylistCommand`, `Planner`, `ConstraintChecker`, `RecommendationEngine`, `PromptBuilder`, `ResponseParser`, `AgentSession`, `SessionState`. |
| `MusicMind_Domain.uxf` | Class diagram: domain model | New types `TopItems`, `PlaylistSummary`, `SnapshotInfo`, `TrendReport`, `RangeChange`, `SearchQuery`, `FeasibilityReport`, `Relaxation`, `DecisionTrace`; new fields such as `PlaylistSpec.blockedTracks`, `AgentPlan.searchTerms` / `seedGenres`, `AgentRequest.constraints` / `useCandidates`, `Track.artistNames`, `ListeningProfile.savedTrackIds` / `summary` / `range`, `ValidationResult.offending`; the two enumerations `RequestKind` and `Verdict` are drawn. |
| `MusicMind_UseCase.uxf` | Use-case diagram | New UC14 Delete Playlist; UC07 now includes UC12 (an infeasible edit is negotiated); UC05 no longer uses the LLM (F4 never calls it). |
| `MusicMind_SD01_CreatePlaylist.uxf` | Sequence: create playlist | Drawn as the State pattern runs it: Planning, Retrieving (with the goal review that sends only track ids and Last.fm genres), Selecting (pick review loop), Validating (loop back), Executing, Done. Shows the F11 negotiation handler, the token-expiry resume and the lines `AgentSession.run()` owns. |
| `MusicMind_SD02_ModifyPlaylist.uxf` | Sequence: modify playlist with undo | `PlaylistEditor` replaces the direct LLM and parser calls; adds the adjustment loop (re-plan when an edit would break a rule), the F11 choice, the snapshot saved before every modify and undo from the latest snapshot when the history is empty. |
| `MusicMind_SD03_TasteProfile.uxf` | Sequence: view taste profile | Shows the stored profile (R5), genres from Last.fm through the decorator with its cache (R1, R18), `TopItems` and the labelled fallback summary. |
| `MusicMind_SD04_Discover.uxf` | Sequence: discover music and give feedback | Preference merge, strategy choice, library tracks excluded, no LLM call, similar artists from Last.fm (Nov 5); feedback goes from `AppController` straight to `MemoryManager`. |

## Marking Stage 2 changes

Every changed diagram carries a legend note. Yellow background = new in Stage 2; blue
background = a Stage 1 element with Stage 2 additions; white = unchanged. In the sequence
diagrams orange arrows and frames are new in Stage 2. The class and domain diagrams were
generated from a script for layout and every name on them was checked against `src/main`
by search; small supporting classes are left out (the list is in `docs/STAGE1_CONFORMANCE.md`,
section 6).

## Stage 1 versions

The Stage 1 PNGs are kept unchanged in `png/stage1/`. Stage 1 had no PNG of the class
architecture diagram; `png/stage1/MusicMind_ClassArchitecture.png` was exported from the Stage 1
`.uxf` (git history, commit before the rebuild) so it can be compared. The updated PNGs are in `png/`.

Export from the command line (from the UMLet folder):

```
cd /Applications/Umlet && java -jar umlet.jar -action=convert -format=png \
  -filename=<abs .uxf path> -output=<abs png path without extension>
```
