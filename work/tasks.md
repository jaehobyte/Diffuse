# tasks.md — Ralph loop task queue

T01–T38 are done. What each one built is in `progress.md` under `## Done`, why it was built that
way is in `work/decisions.md`, and the `done when` checklists are in git history. None of it is
repeated here: this file is read on every loop iteration, so it holds only what is still open.

Rules for the agent: unchanged — see CLAUDE.md "Ralph loop rules". One task per iteration,
`scripts/check.sh` is the only verdict, only edit checkboxes in this file.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked · `[H]` human-only, loop must skip

---

## Prerequisites (human work, not a task — the loop must never pick this up)

One item is outstanding:

| Missing | Blocks |
|---|---|
| `POST /v1/edit/erase` implemented in `~/sam3-server` | the 지우기 tool, which gets a 404 today |

T37 and T38 are written against the contract in `specs/generative_erase.md` §2 and tested with
`MockWebServer`, so the Android side needs no change when it lands.

Everything else the v2 line needed is done: the two `libs.versions.toml` entries, the CLAUDE.md
network rule, the three `AppError` cases, the `local.properties` keys, and deleting the EdgeTAM
`.pte` files.

If a task hits a missing prerequisite, mark it `[!]` and write the reason in `blocked.md`. Do not
add the dependency yourself.

---

## Backlog

_Empty._ T26–T38 completed the v2 line — server-side SAM 3 selection, add/subtract merging,
masked adjustments, cut-out, the prompt bar with voice, and the generative eraser — and it is
verified on a device against the real model.

---

## Deferred
- D01 Layers · D02 Text · D03 GPU render (AGSL) · D04 Tablet · D05 Onboarding
- D06 Box prompt for selection (`/segment/box` already exists server-side, so this is UI only)
- D08 Video (SAM 3 tracking)
- D09 On-device segmentation fallback — the retired EdgeTAM / ExecuTorch plan (ADR-007, ADR-008).
  Revisit only if offline selection becomes a requirement.
- D10 Generative fill / replace, reusing the T37 proxy boundary
