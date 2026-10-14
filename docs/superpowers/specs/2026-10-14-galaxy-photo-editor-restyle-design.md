# Galaxy Photo Editor restyle — design

Date: 2026-10-14. Source: the Samsung Photo Editor (`com.sec.android.mimage.photoretouching`,
One UI 8.5, SM-S948N) inspected live over adb: Gallery → photo → 편집, then every tab
(조절 / 필터 / 색조 / 데코 / 도구), the 비율 sub-menu, a moved 밝기 slider, a selected filter and
the ⋮ menu. Pixel colours were sampled from the screenshots.

## What the Photo Editor looks like

| Region | Observation |
|---|---|
| Background | Pure black (`#010102`) everywhere: canvas surround, top bar, bottom tabs. No surfaces, no sheet. |
| Top bar | Left ↶ ↷ (white, disabled dim). Right: `원본 복원` text, `저장` bold text, ⋮. No back arrow, no title. |
| Bottom tabs | Six icon-only tabs, white; the selected one is **yellow** (`#F9DA4B`). Photo Assist is a blue→purple gradient circle. |
| Sub-tools | Float directly on black above the tabs: a dark-grey pill of icon buttons (조절), a row of 56dp circles (색조), square thumbnails (필터), 82×90dp cards (도구). Dark grey is `#202023`. |
| Selection | Selected circle/chip = **white fill, black glyph**; selected ratio text = white bold, unselected `#A6A6A7`; selected filter = 2dp white border. |
| Slider | A tick ruler, no track: grey ticks (`#8D8D8E` major, dimmer minor), the current value a taller yellow tick, a grey dot marking zero. The value is shown inside the selected circle as a number over a yellow pie wedge (`#F9E16E`). |
| Commit model | No 취소/적용 per tool; edits are instant, ↶ ↷ and `원본 복원` undo them, `저장` writes the file. |
| Menu / dialog | `#272727` rounded card, white text; the primary dialog button is One UI blue. |

## What changes in Vibe Editor

Kept (contracts in `specs/`, `work/decisions.md`): tool list and order, the two-level strip with
the AI parent last, the sheet lifecycle (open → drag → 취소/적용), hold-to-compare, export sheet,
history, prompt bar behaviour. Only how they look and feel changes.

1. **Palette** — one black theme for Browse and Edit. Yellow `accent` for selection and the active
   tick; One UI blue `primaryFill` for the few filled buttons; `#202023` raised surfaces; white
   `ink`, `#A6A6A7` `inkSecondary`. Selection is white-on-black inverted (`selectionFill` /
   `selectionInk`). The glass-sheet tokens go away.
2. **Top bar** — black; ↶ ↷ ◐ on the left, `원본 복원` and bold `저장` text buttons on the right. The
   back arrow is dropped (system back), as the Photo Editor does.
3. **Tool strip** — black, no indicator line; selected item yellow; the AI parent is the gradient
   circle. Labels stay (the AI level has eight abstract tools) in 11sp under the icon.
4. **Sheet** — a black panel with no corners, shadow, handle or edge; a small centred title like the
   Photo Editor's `밝기`; 취소 as white text, 적용 as yellow text. 45% cap unchanged.
5. **Adjust sheets** — the Photo Editor's tone layout: one row of 56dp circles (one per kind,
   selected = white with a yellow wedge and the number), the active kind's name above, one ruler
   slider below. Unselected circles show the number when the value is non-zero.
6. **Slider** — ruler drawing (ticks, yellow current tick, zero dot); the same absolute-position
   gesture, double-tap reset and value formatting.
7. **Chips, pills, prompt bar** — chips: `#202023` with white text, selected white with black text.
   Primary pill One UI blue, secondary `#2C2C30`, tertiary white text. Prompt bar `#202023` pill.
8. **Browse** — black, white title, tiles on `#202023`, blue CTA.
9. **System bars** — light icons on black.

## Testing

Tokens/typography assertions are rewritten to the new tables. Adjust-sheet tests select the kind's
circle before dragging and look for the kind by `adjustKindTag`. Every golden is re-recorded in the
same change (the restyle touches all of them) and `scripts/check.sh` runs green.
