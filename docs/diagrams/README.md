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

| File | Diagram |
|------|---------|
| `MusicMind_ClassArchitecture.uxf` | Class diagram — application & agent architecture |
| `MusicMind_Domain.uxf` | Class diagram — domain model |
| `MusicMind_UseCase.uxf` | Use-case diagram |
| `MusicMind_SD01_CreatePlaylist.uxf` | Sequence — create playlist (with alt/loop flows) |
| `MusicMind_SD02_ModifyPlaylist.uxf` | Sequence — modify playlist with undo |
| `MusicMind_SD03_TasteProfile.uxf` | Sequence — view taste profile |
| `MusicMind_SD04_Discover.uxf` | Sequence — discover music & give feedback |
