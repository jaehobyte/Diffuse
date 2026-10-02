# specs/multishot.md — 멀티샷 (sequential-pose composite)

Owner task: work/tasks.md (2026-09-29, 멀티샷; 2026-10-01, 시간 순서 배치; 2026-10-02, 여러 장 일괄 추출·자동 배치)
Modules: `core/imaging` (model, render), `core/data` (files), `feature/editor` (`tools/multishot/`),
`core/ai` (existing `SegmentationProvider` only)
Depends on: edit_model.md, render.md, persistence.md, segmentation.md, selection_tool.md, canvas.md,
tool_groups.md, DESIGN.md §4, §7, §8
Decision: work/decisions.md D085, D086 (time layout, hero protection), D087 (placement step), D088
(장수별 균등 배치, up to five added shots), D090 (one press: extract all, lay out automatically)

## 1. What it is
Two to six moments of the same motion — a golf swing, a pitch — in one picture. The **current
project photo is the background and stays exactly as it is**; one to five added photographs
contribute only their extracted subject, drawn over it semi-transparently. Real pixels only: nothing
is generated, no pose, no club, no background. No automatic alignment, no capture-time guess.

Two layouts (§2.1):
- **자유 배치** (free) — the original: the user places each subject; thumbnail order is drawing order.
- **시간 순서 배치** (time) — the reference picture (`test.jpg`): the current photo is the **last
  moment / hero**, kept sharp in front; the earlier moments are spread along a straight path towards
  it in the order the user confirmed, older ones fainter.

Out of scope: a general layer editor, homography/stabilisation/optical flow, video frames, HDR or
noise stacking, generative recomposition, colour matching, shadows, brush editing, recovering an
unapplied draft after process death.

## 2. UI and states
- **Entry.** `Tool.MultiShot` ("멀티샷") at the **AI level of both menu profiles**, appended before
  지시 so every existing tool keeps its relative order. No face or content condition.
- **Sheet** (`EditSheet`: 45% max height, fixed 취소 | 적용, 적용 the one accent):
  - New composite: "어떻게 배치할까요?" with 시간 순서 배치 / 자유 배치 and one line each. Nothing can
    be added before a layout is chosen. A stored composite opens in its stored layout; a row of the
    two at the top switches explicitly (§2.1).
  - Free, empty: "연속으로 찍은 사진을 더해 동작을 겹쳐 보세요" + 사진 추가.
  - Thumbnails of the added photos in **drawing order** (later = on top); select one; 뒤로 보내기 /
    앞으로 가져오기 / 교체 / 삭제 for the selected one. Import order is not assumed to be capture
    order. The background photo is not replaceable here. At most five (`MAX_SHOTS`), six moments in all.
  - Only the **selected** photo's controls are shown, so the canvas keeps at least half the screen.
- **Adding.** System Photo Picker, image only; no gallery permission. Free layout and 교체: one photo
  per pick (`PickVisualMedia`). Time layout: several at once (`PickMultipleVisualMedia(n)`, n = the
  places left; one place left → the single picker; none → 사진 추가 is disabled). Each launch is a
  request of the session (`requestPick` → id, replace key, limit); an answer for another request, a
  closed sheet or a new session is dropped. A list longer than its limit is refused whole with a
  message, never cut short. The returned order is appended to the timeline as is — not re-sorted or
  de-duplicated by URI, name, EXIF or decode time. Several photos are read one after another; one that
  will not read stays in its place as **불러오지 못함** ("n단계 사진을 불러오지 못했어요…") for 교체 or
  삭제, and the others are read regardless. Cancelling the read drops only the photos not yet read. Read through `ImageLoader` (EXIF-upright, long edge ≤ 4096, `AppError` on unsupported /
  too large / unreadable). A cancelled picker changes nothing.
- **Photo stage** (selected photo not yet a subject): the canvas shows **the photo itself**, not the
  composite. The sheet says where it will be sent ("피사체 추출을 누르면 이 사진이 SAM 3 서버(host)로
  전송돼요") and shows the server's state with 다시 확인 / 서버 설정. Nothing is uploaded until
  **피사체 추출** is pressed (free layout; the time layout sends only through its run, §2.1).
- **Extraction.** 피사체 추출 releases the selection tool's SAM 3 session, opens one on this photo
  (≤1080 px long edge) and asks "사람".
  - 0 answers: "사람을 찾지 못했어요…" — never replaced by the whole photo or a first guess.
  - The concept sent is **`person`** (the UI stays Korean). On the device samples SAM 3 answered
    nothing for "사람" and found the person for "person" (work/RESULT.md); no translation service
    is added and free Korean input is not promised. The phrase field's examples are model inputs
    (`person`, `golf club`); point selection is the fallback.
  - 1 answer: taken. Several: listed as 후보 1…n; the user picks one before 추출 완료 is enabled.
  - Refine: tap = add the region under the finger, long-press = remove it (`byPoints`, merged with
    `MaskOps`). An extra phrase (`golf club`) offers its candidates and the chosen one is **added**
    to the subject.
  - **추출 완료** cuts the subject at working size (§5), writes it (§7), closes the SAM 3 session and
    switches that photo to placement.
- **Placement stage** (selected photo is a subject): the canvas shows the draft composite. One finger
  on the photo drags the selected subject; two fingers stay the canvas's pinch/pan (DESIGN.md §8).
  Sliders: 불투명도 0–100%, 크기 (log scale, 10–400%, double-tap → 100%), 회전 −180…180°; each reads
  its name and value to a screen reader. 위치 초기화 (keeps opacity). **잔상** (free layout only):
  one photo → 50%; more → 35% … 65% evenly in thumbnail order (two: 35 / 65); opacity only,
  adjustable afterwards. Not the time profile (§4.2), and not shown in the time layout.
- **States told apart:** importing / extracting / refining / saving (busy overlay with 취소),
  not-found, failed, server needs settings, server unreachable, settings changed. A failure on one
  photo never drops the other's subject. An unfinished photo is never silently left out: 적용 stays
  disabled until it is finished or deleted.
- **적용** is disabled while working, while any photo is unfinished, and for a new composite with no
  photo or with every opacity at 0. The time layout also needs its hero, an anchor on every shot and
  a confirmed order (§6). Removing every shot of an existing composite is allowed and applies as its
  removal. No mode is changed and no photo is dropped to make 적용 possible.
- **Commit.** 취소 / Back / dismiss: the document is unchanged. 적용: **one history entry** (new,
  edited, or removed composite). Undo/Redo/Reset while the sheet is open end the session.
- **Re-edit.** Opening the tool on a document with a composite restores its layout, shots, order,
  transforms and opacity, and for the time layout its confirmed order, settings, hero and anchors —
  nothing is laid out again. Re-entry, placement, ordering, layout, presets and export never call
  segmentation. Re-extracting a stored subject is done by 교체 (the original added photo is not kept).

### 2.1 The time layout's flow
Pick several → **모두 추출하고 자동 배치** (one press) → laid-out preview → optional corrections →
적용 (D090, replacing D087's separate order check and placing as required steps). Steps already done
are never redone: a stored composite reopens settled, and opening it or switching layouts never
extracts or lays out anything.
- **The run.** Under the timeline, whichever tile or step is shown, one main action (a tertiary pill:
  적용 stays the one accent) with 순서 뒤집기 beside it. Before it, "보이는 순서대로 배치해요… 누르면
  원본(주인공)과 추가 사진이 차례로 SAM 3 서버(host)로 전송돼요" and the server row. Choosing the layout
  and picking send nothing; **the press confirms the order shown and starts sending**.
  - Its label says what is left: 모두 추출하고 자동 배치 (nothing extracted yet) / 남은 사진 추출하고
    자동 배치 / 이 순서로 자동 배치 (all extracted, not laid out) / 자동 다시 배치 (laid out for this order).
  - Disabled while a photo is being read or is 불러오지 못함, while anything is working or a run is
    under way, and — when something is still to extract — while the server needs settings.
  - It takes every not-yet-extracted item: the **hero first** (waiting, as "현재 사진을 준비하는 중", for
    its input canvas if it is still rendering), then the added photos **in the order shown**. Items
    already extracted are reused, not sent again. One photo and one SAM 3 session at a time; the
    session is closed before the next photo.
  - Per photo: upload, "person". Exactly one non-empty answer → taken and saved (§5, §7) without a
    press. None, or several → the run **stops on that photo** (selected, candidates or the not-found
    line, point/phrase refinement), never taking the first or the largest. Its 추출 완료 saves the
    choice and the run **goes on by itself**; the hero follows the same rule.
  - Progress "n/N · 현재 사진을 준비하는 중 / 피사체를 추출하는 중 / 피사체를 저장하는 중 / 대상을 골라 추출
    완료를 누르면 나머지를 이어서 진행해요", with **진행 취소**. While a run is under way (also while it
    waits for a choice) adding, selecting, reordering, deleting, replacing, switching layouts or steps
    and a second press are disabled.
  - A failure (unreachable, settings, timeout, rate limit, extraction, re-read or save) **ends the run
    on that photo** with its reason; nothing after it is sent and nothing is laid out. Finished items
    stay. Pressing again goes on from the failed photo (a saved choice whose save failed is saved again
    without a new upload). 교체 / 삭제 are open again.
  - 진행 취소 (or the overlay's 취소) ends the run: no next upload, a late answer is closed and dropped,
    finished items stay in the draft, nothing is laid out or committed. Sheet 취소/Back, a document
    change, leaving the editor and a **SAM 3 settings change** end it too; after a settings change the
    rest is sent only after a new press.
  - When every item of the run is saved, the shown order is laid out **once, at that transition**:
    the existing `MultiShotLayout` positions (§4.2/§4.3) and the time profile's opacity, the order
    confirmed and settled (`orderConfirmed`, `laidOutOrder`, `keptPositions = false`), and the sheet
    switches to 배치 위치 so spacing, strength and corrections are at hand. 적용 is then enabled.
    Nothing else — a thumbnail arriving, a recomposition, re-entry, every item being ready — lays out.
  - Already all extracted: the press lays out at once, locally, without the network.
- After the layout, drags, size, angle and opacity are the user's until the next explicit press;
  자동 다시 배치 recomputes positions and opacity for the order shown and keeps size and angle; spacing
  moves positions only. Adding, deleting, replacing or reordering says "사진이나 순서가 바뀌어 배치에
  아직 반영되지 않았어요…" and moves nothing; the same action (남은 사진… / 자동 다시 배치) settles it.
- Guide "현재 사진이 마지막 동작이에요. 앞선 동작 사진을 추가해 주세요". The timeline row shows the added
  photos **earliest first**, a step label *under* each thumbnail (1단계, 2단계, …), then the fixed
  **마지막·주인공** tile, then 사진 추가 (several at once, at most five — "총 N장 (원본 포함, 최대 6장)"; a seventh is refused before the picker opens).
- Under it, two steps: **사진별 편집** (the selected tile's own rows) and **배치 위치** (the common
  placement). 배치 위치 is reachable whichever tile is selected; selecting a tile returns to 사진별 편집.
  Showing 배치 위치 ends an open selection (its photo goes back to "picked") and shows the composite.
- Import order is a guess, never a capture time (no file name, URI, gallery order, mtime or EXIF
  time is read); the run notice says the order shown is the one used. Before the press it is fixed
  with **순서 뒤집기** and the selected photo's **이전으로 / 다음으로**; there is no separate order check.
  Adding, removing or replacing a photo clears the confirmation; nothing extracted or placed is lost.
- Three things are kept apart: the order is **confirmed** (by the run's press, or 이 순서로 위치 배치 /
  현재 위치 유지); the positions are **settled for that order** — laid out by the run's end or 이 순서로
  위치 배치, or kept by 현재 위치 유지 (`laidOutOrder`, `keptPositions`, in memory); and the stored
  placements, which are all the renderer uses. Changing the order moves nothing until it is settled.
- **배치 위치** (shown by the run's end; also reachable by hand):
  - What is still missing, named: "앞선 동작 사진을 추가해 주세요", "주인공을 먼저 선택해 주세요",
    "n단계 사진의 피사체를 추출해 주세요". Nothing is placed, confirmed, dropped or switched to free.
  - **이 순서로 위치 배치** (once settled: **위치 다시 배치**): confirms the order shown and puts each
    earlier moment's anchor on its slot for it, with the time profile's opacity; local, no server.
  - **현재 위치 유지** (advanced; not a step of the run): confirms the order shown and keeps every
    position and opacity. Offered only while the positions are not settled for the order shown.
  - **장수별 균등 배치** (a new proposal's arrangement, §4.3) or **기존 경로 배치** (§4.2); a stored
    timeline opens in its own. Even: "총 N장", **이 순서로 N장 균등 배치**, 간격 (0–100%, 0 announced as
    overlap), the hint that the hero stays put and centres the group (an off-centre hero shifts it),
    and for an even N that the hero takes the left of the two middles. No direction arrow: the hero
    is the newest but sits in the middle, so time does not run across the whole picture.
  - Path only — 동작 방향, named start → end: **왼쪽 → 오른쪽 (오래된 → 최신)** (a new proposal's default, 0°),
    오른쪽 → 왼쪽, and the four diagonals (e.g. 오른쪽 위 → 왼쪽 아래, D086's former default). 펼침 거리
    (0 is announced as every moment on the hero's spot), 잔상 강도, and what each overwrites.
  - On the canvas: for the path, the dashed path from the start to the hero; for the even
    arrangement, a dashed baseline through the hero's anchor and a square on the hero's slot. Then a
    numbered target per earlier
    moment, filled while that photo's anchor sits on it and a ring once it was moved off. Drawn by the
    overlay only — never into the preview bitmap or an export.
- Free layout: 뒤로 보내기 / 앞으로 가져오기 change drawing order only. Time layout: earlier moments are
  drawn first, later on top, the hero always in front; those buttons are not shown. Switching to the
  free layout keeps the confirmed order, every subject and transform.
- **Hero tile**: extracted by the run (§2.1); only when the run waits on it, "주인공 선택: …" and the
  refinement rows of §2 on the composite's input canvas (§6). The time layout shows no per-photo
  피사체 추출 — sending is the run's — and a photo's rows show its choice only while the run waits on it. Once selected it stays where it is: "주인공은 원래 자리에 그대로 있어요…", 기준점
  조정, 주인공 다시 선택. "Newest on the right" means the right end of the placed moments, not of the
  canvas; the hero and the background never move.
- **A photo tile** once placed: "끌면 이 피사체가 옮겨져요. 다음 위치 다시 배치가 직접 옮긴 위치를
  덮어써요", the §2 sliders, 위치 초기화 (time layout: back to its slot on the current path at 100% /
  0°, opacity kept; free: contain-centred as before), 기준점 조정 and a shortcut to 배치 위치.
- **기준점 조정**: while on, a one-finger drag moves the selected anchor (shown as a dot) and the
  subject stays where it is ("지금은 기준점만 옮겨져요"); the next layout uses it.
- A subject reaching past the canvas is announced ("일부 피사체가 사진 밖으로 나가 잘려요…"); the canvas
  is never extended, the hero never moved, a subject never shrunk or un-rotated to hide it.
- **적용** in the time layout needs the hero, every photo extracted, a confirmed order and positions
  settled for it — after a run, nothing more. The result is a draft until 적용 (one history entry);
  취소 leaves the document unchanged.
- Overlay description: the path for 기존 경로 배치; for 균등 배치 "원본 발밑 높이의 기준선, 가운데 칸에
  그대로 있는 원본과 시간 순서대로 번호 붙은 N개의 목표 위치".

## 3. Operation
```kotlin
data class ShotPlacement(offsetX: Float = 0f, offsetY: Float = 0f, scale: Float = 1f,
                         rotationDeg: Float = 0f, opacity: Float = 0.6f)
data class Shot(id: String, subjectRef: ImageRef, widthPx: Int, heightPx: Int, placement: ShotPlacement,
                anchor: NormPoint? = null)             // fraction of the photo
data class NormPoint(x: Float, y: Float)               // both 0..1
enum class MultiShotMode { Free, Timeline }
data class HeroMask(ref: ImageRef, widthPx: Int, heightPx: Int, anchor: NormPoint) // canvas fraction
enum class TimelineArrangement { Path, Even }
data class TimelineLayout(directionDeg = 0f, distance = 0.5f, strength = 1f,
                          arrangement = Even, spacing = 1f)                    // new proposals
data class Timeline(order: List<String>, layout: TimelineLayout, hero: HeroMask?, // earliest first
                    orderConfirmed: Boolean = true)
data class Operation.MultiShot(id: String, shots: List<Shot>,           // 1..5 shots, distinct ids
                               mode: MultiShotMode = Free, timeline: Timeline? = null)
```
- `timeline`, when present, orders exactly the shot ids; its layout is in range (direction
  −180…180°, distance 0…2, strength 0…1). `mode = Timeline` also needs `timeline.hero` and an
  anchor on every shot and a confirmed order. The free layout keeps the timeline — hero, settings
  and order — so switching back loses nothing, and ignores it when drawing. A photo added, removed or
  replaced in the free layout clears `orderConfirmed` only: the hero and settings stay, and the time
  layout asks for the order again (REVIEW 2026-10-01 R2). A timeline is written once there is
  something of it to keep (a confirmed order, a hero or non-default settings).
- Final pixels are the stored transforms. Load and render never lay anything out again.
- At most one per document. `withMultiShot(shots)` **appends** a new one, **replaces an existing one
  in place** (same index and id), and removes it for an empty list.
- In-order semantics (render.md): ops before it apply to the background only; ops after it apply to
  the composite. `Crop` still runs last. **Stated limit:** an `Adjust` that existed before the
  composite is updated in place (edit_model.md) and so keeps applying to the background only; this
  feature does not reorder adjustments. Covered by `MultiShotModelTest` / `MultiShotRenderTest`.
- Never stored: bitmaps, URIs, SAM sessions, screen coordinates. No general layer model.
- `canOutpaint` is false while a `MultiShot` exists (one guard, in the model). A composite on top of
  an existing `Outpaint` is allowed. Source, `Crop`, masks and `activeMaskId` are untouched by 적용.

## 4. Coordinates and transform
- Space: the **canonical canvas** — before `Crop`/rotation; the expanded canvas when an `Outpaint`
  exists. Size `W × H` at whatever resolution is being rendered.
- Initial placement: the whole added photo (`widthPx × heightPx`), aspect kept, **contained** and
  centred in the canvas — `s0 = min(W / widthPx, H / heightPx)`. The subject is the photo with a
  transparent background, so a same-size, same-framing photo lands exactly where it was taken.
- Subject pixel `p` → canvas: `C + T + R(rotationDeg) · (s0 · scale) · (p − photoCentre)`, with
  `C = (W/2, H/2)` and `T = (offsetX · W, offsetY · H)`. One implementation (`MultiShotOp.matrix`)
  for preview and export; a subsampled decode is first scaled back to `widthPx × heightPx`.
- Ranges (shared by sheet, JSON check and renderer): `scale` 0.1–4.0, `rotationDeg` −180…180,
  `opacity` 0–1, offsets any finite value (a subject may leave the canvas; output is clipped).

### 4.2 The time layout (`MultiShotLayout`, pure, local, deterministic)
- **Anchor**: per shot, initially the bottom centre of the bounds of every mask pixel with any alpha
  (a one-pixel club counts), as a fraction of the EXIF-upright photo — a placement reference, not a
  joint. The hero's is the same on its mask, as a fraction of the canvas: the fixed end of the path.
- **Path**: direction θ (on screen, x right, y down) is where the motion runs towards the hero; the
  length is `distance × min(W, H)`. With `n` earlier moments in confirmed order, moment `i` (0 =
  earliest) goes to `hero − d · length · (1 − i/n)`: the start, then `1/n`, `2/n`, … of the way; the
  hero's own slot is the end, so no afterimage lands on it. Distance 0 stacks every anchor on the
  hero's. Direction moves positions only; photos are never mirrored or turned.
- **Placement**: the offset that puts the anchor on its slot under the §4 transform, keeping
  `scale`/`rotationDeg` — `offset = target − ½ − R·s·(anchor − centre) / (W, H)`. In fractions of
  the canvas, so preview and export agree, and from scratch every time, so repeating it never
  accumulates.
- **Profile**: earliest → latest, one moment 50%, else 25% → 70% evenly (four: 25/40/55/70%), times
  `strength`. The hero keeps its own pixels (§6). Each opacity stays adjustable afterwards.
- **Default**: a new proposal (a new time layout, or a free composite taken to the time layout for
  the first time) runs left → right, 0°: the start is `hero − (distance·min(W, H), 0)`, so with
  `S = (0.2, 0.8)` and `H = (0.8, 0.8)` two moments sit at x 0.2 / 0.5 and four at 0.2 / 0.35 / 0.5 /
  0.65. A stored timeline keeps its own `directionDeg` (every stored node has one; it is required).
- **Spatial order**: for a positive distance the placed anchors' projections on the direction grow
  from the oldest to the newest and stay short of the hero's — left → right: `x(oldest) < … <
  x(newest) < x(hero)` on the hero's height. This is the anchors' order, not the bodies' outlines.
- **What a control overwrites**: 이 순서로 위치 배치 — positions and opacity; 방향 / 거리 — positions
  only, and only once the order is confirmed (before, they are only chosen); 강도 — opacity only. Size, rotation, masks and refs are never touched. Opening, reordering or
  confirming changes nothing on its own.
- Screen → stored: while the sheet is open the canvas shows the draft **without `Crop`**
  (자르기's T69 rule), so the displayed image is the canonical canvas and a drag delta divided by the
  drawn image's size is the offset change; zoom, pan and letterbox cancel out. Nothing device-pixel is
  stored.

### 4.3 장수별 균등 배치 (`TimelineArrangement.Even`, D088)
- **N** counts the current photo: N = added + 1, 2…6. The hero's slot is `k = ⌊(N − 1) / 2⌋` (the
  left of the two middles for an even N). The added photos take slots 0…N−1 without k, **in the
  confirmed time order**: three `[A, 원본, B]`, five `[A, B, 원본, C, D]`, six `[A, B, 원본, C, D, E]`.
  The hero is still the newest moment; only its place is the middle. Reversing the order swaps the
  added photos' slots; the hero's slot stays.
- Slot j: `x = hx + (j − k) · spacing / N`, `y = hy`, with `(hx, hy)` the hero's anchor. With the
  hero at its column's centre that is the screen's N columns, `(j + 0.5) / N`; otherwise the group is
  those columns shifted by `hx − (k + 0.5) / N` — never "the screen split exactly in N" for an
  off-centre hero or an even N. Examples at `hx = 0.5`: three → 1/6, 1/2, 5/6; five → 0.1 … 0.9; six →
  1/6, 1/3, 1/2, 2/3, 5/6, **1** (the last anchor on the right edge: that subject is cut, announced;
  spacing 0.8 → 0.233, 0.367, 0.5, 0.633, 0.767, 0.9). Nothing is clamped or pulled in.
- **Same height** is the anchors' (feet / ground): every placed anchor is at `hy`. No head alignment,
  no vertical stretch. Anchors stay correctable per photo.
- The offset comes from `placedAt` — the subject's anchor on the slot, through the photo's own
  position, aspect, EXIF-upright size, scale and rotation — not the photo's centre. From scratch each
  time, so repeating it never accumulates. A slot is a position, not a crop: subjects may overlap
  their neighbours' columns.
- Opacity: the same time profile, by time order not by slot (five: 25 / 36.25 / 47.5 / 58.75 / 70%) ×
  strength. Spacing moves positions only; strength opacity only; size, rotation, masks and refs stay.
  Drawing order stays time order; the hero's protection is on top of all.
- 위치 초기화 in the time layout returns the selected photo to its current slot at 100% / 0° and keeps
  its opacity (both arrangements); the next 균등 배치 overwrites hand placements, as the sheet says.

## 5. Alpha and compositing
- Subject PNG = the added photo's RGB with alpha `photoAlpha × feather(mask)`. The SAM mask (binary,
  photo-preview size) is stretched nearest-neighbour to working size and feathered **once** with a
  fixed box of radius 2 working px (`MultiShotSubject`). The binary `SegMask` contract and the
  selection tool's masks are unchanged.
- Render: shots in list order, **Porter–Duff source-over** on premultiplied pixels (Skia), effective
  alpha `subjectAlpha × opacity` applied once by the paint. Correct over a translucent background;
  opacity 0 is no change; opacity 1 is the subject's alpha. Filtering premultiplied pixels avoids
  dark/light halos. `hasAlpha` and export policy are unchanged.

## 6. The hero (time layout)
- **Input**: the composite's own input — every op before it (all of them for a new one), without the
  `Crop`, on the expanded canvas when an `Outpaint` exists (`EditDocument.multiShotBase()`), rendered
  locally at preview size. The hero is selected on that image, which the canvas shows meanwhile;
  it is uploaded only on an explicit 피사체 추출, like a photo.
- **Mask**: 추출 완료 writes `shot_<fileId>.png` at that input's size whose alpha is the mask
  feathered once (radius 2), the subject file format and ownership (§7). `activeMaskId` and the
  selection tool's masks are neither read nor written.
- **Render**: after the shots, `out = lerp(composite, input, heroAlpha)` once, on **premultiplied**
  pixels (`MultiShotOp.protect`, the mask scaled bilinearly to the canvas), so a transparent input adds
  no colour and a soft edge never darkens (REVIEW 2026-10-01 R1). The shared `MaskBlend` is not used.
  Inside the mask the input's RGB and alpha come back exactly; the edge is the
  feather; outside is the plain source-over composite; the background is never removed or moved.
  The hero's RGB is never copied: adjustments before the composite reach it through the input, ones
  after it apply to the whole result, and `Crop` still runs last.
- **States**: not found / cancelled stay unfinished (never the whole picture as a mask); the user
  retries, refines or switches to the free layout explicitly.
- **Guards**: one per document, `canOutpaint`, no re-basing of stored PNGs.

## 7. Files and persistence
- Each extraction writes `shot_<fileId>.png` (fresh id every time, so a replacement gets a new ref and
  caches/undo keep the old pixels) atomically; a failed or cancelled write leaves no file.
- A written subject or hero mask is only **published** to the document on 적용. Files the session wrote and did not
  commit are discarded on close (`discardShotSubjects`, which never deletes a file the saved document
  references). Earlier composites' files are never deleted by a session.
- JSON `{"type":"multiShot","id",…,"shots":[{"id","subjectRef","width","height","offsetX","offsetY",
  "scale","rotationDeg","opacity"[,"anchorX","anchorY"]}][,"mode":"timeline"][,"timeline":{"order":[…],
  "directionDeg","distance","strength"[,"arrangement":"even","spacing"][,"orderConfirmed":false][,"hero":{"ref","width","height","anchorX","anchorY"}]}]}`.
  `mode` is written only for the time layout, `arrangement`/`spacing` only for the even
  arrangement (no field = the path, as every timeline before D088), `orderConfirmed` only when false, and `timeline` only
  once there is something of it to keep, so a
  composite that never used them keeps its shape. **No `mode` = the free layout** (every composite
  saved before this); it keeps its order, placements, opacity and refs, gets no hero protection, and
  its drawing order is only offered as an unconfirmed time order. Root `v` stays 1; unknown ops are
  still dropped. A **known** `multiShot` that has 0 or >5 shots, a duplicate id, a missing
  id/ref/size, a non-finite or out-of-range value, an unknown mode, a timeline that is not an object
  or misses its order, an order that is not exactly the shot ids, half an anchor, an `orderConfirmed`
  that is not a boolean, an `arrangement` other than `"even"`, an even arrangement without a
  spacing in 0..1, or a time layout without its hero, anchors or confirmed order is kept as parsed and fails `referencesResolve` → load `Unsupported`;
  it is never partly restored. A second `multiShot` also fails.
- A subject or hero file that is gone fails the load (`MissingSource`); a hero file whose size is not
  the stored one fails it as `Unsupported`; one that will not decode fails the render
  (`MissingSource`) instead of drawing the composite without it.
- `duplicate` rewrites every reference that lived in the original folder — the source, stored masks
  and results, shot subjects and the hero mask — to the copy's folder, so the copy survives deleting
  the original.
- Undo/Redo keep their PNGs (no GC change).

## 8. Lifecycle, cancellation, memory
- The draft lives in the controller (ViewModel lifetime; survives configuration change). Process
  death returns to the last saved document.
- The run lives in the controller session (one coroutine at a time; `Session.run` is a fresh token
  per run, checked before each next photo, so a step of an ended run goes no further; `runKeys` its
  items). A run that waits for a choice holds no coroutine; 추출 완료 continues it inside its own job,
  so no nested `startWork` cancels it. A Picker answer is taken only for this session's last request.
- Every async step (import, base render, open, query, refine, save) carries the session and a
  per-photo token (the hero has its own); a
  late answer — including one from a provider that ignores cancellation — is dropped. Boundaries: a
  new request for the same photo, delete, replace, overlay 취소, sheet 취소/Back, SAM 3 settings change,
  document change (Undo/Redo/Reset), leaving the editor. `CancellationException` is never reported as
  a failure or retried.
- SAM 3: one session per photo being refined, closed (best effort) on 추출 완료, switch, delete,
  replace, settings change and close. An upload already sent runs to its answer and is then closed.
  `Sam3SegmentationProvider.close(session)` now closes only if that session is still the live one.
- Off main: import, decode, alpha and mask work, bounds/anchors, compositing, file IO. Photos are read
  one at a time; only a ≤1080 px preview of each is kept; the working-size photo is re-read for 추출
  완료 and dropped — five photos never mean five working bitmaps. A switch of photo, an add or a
  replace closes the open selection and returns its photo to "picked" (never Selecting without a
  session). The renderer caches at most `SHOT_CACHE_ENTRIES = MAX_SHOTS` preview-size subject decodes,
  each scaled to the preview size; an export decodes each full-size subject in turn without caching.
  Hero protection costs the mask (one preview-size PNG, scaled to the canvas) and one output buffer
  (`MultiShotOp.protect`); the input is already held as the composite's source.
- Offline: reopen, adjust, undo/redo and export need no network. Extraction needs the configured SAM 3
  server; a failure never falls back to another service. Photos, tokens and base64 are not logged.

## 9. Tests
- `MultiShotModelTest`: position/in-place/removal, adjust-position limit, guards, ranges, 4 vs 5
  shots, time-layout validity, drawing order, input document, JSON round-trip (free and time), legacy
  free load written back unchanged, broken known op refused (free and time).
- `MultiShotLayoutTest` (D088): hero slot and taken slots for 2…6 in all, the contract's 3/5/6
  examples and spacing 0.8 / 0, an off-centre hero unclamped, transformed anchors on their slots and
  the hero's height for different photos/sizes/angles at 3 and 6 in all and two spacings, repeat
  without accumulation, reversed order swaps slots, preview/export sizes.
- `MultiShotLayoutTest` (path): profile 1/2/4 × strength, slots for 1/4, distance 0, directions, anchor on
  slot for other aspects/scales/angles, repeat without accumulation, reversed order, missing anchor,
  preview/export sizes, anchor drag, clip check, bounds with a 1 px club; the contract's 0.2/0.5 and
  0.2/0.35/0.5/0.65 example, placed anchors in path order for six directions with different photos,
  sizes and angles, left → right on the hero's height.
- `MultiShotRenderTest` (production `CpuRenderer`, PNG files): source-over on opaque and translucent
  backgrounds, soft alpha × opacity once, opacity 0, source alpha kept, order swap, same-size
  alignment, 1 px line, contain for another aspect, rotation, off-canvas clip, preview/full position,
  Crop+straighten after, Outpaint canvas, adjust before/after, missing subject fails; hero interior
  equal to the input bit for bit (colour and alpha, translucent background), edge blended once,
  free layout ignores a stored hero, adjust before/after reaches the hero, time order drawing, hero
  missing/other shape fails, preview/export agree; a soft hero edge over transparent and
  translucent inputs, opaque and half afterimages, masks 0/64/128/192/255 — premultiplied RGB and
  alpha checked numerically in preview and full.
- `MultiShotSubjectTest`: RGB kept, photo alpha multiplied, 2 px ramp, mask stretch.
- `MultiShotPersistenceTest`: RGBA write, failed write leaves nothing, discard keeps referenced,
  apply→undo→redo→save→load→export pixel equality with Adjust/Mask/masked Adjust/Crop, missing and
  corrupt subject, broken op on disk, duplicate then delete original; a four-shot time layout through
  save/load/export/duplicate/delete original, discard keeps the referenced hero, hero gone/resized.
- `MultiShotControllerTest` (scripted SAM 3, gated host): nothing sent on entry/add, picker cancel,
  import failure, several candidates, empty answer, phrase add, one photo failing, cancel with late
  session, late import, late save discarded, settings change, document change, preset, order,
  invisible composite, re-open, remove-all, replace; REVIEW R1 (replacement read failure / cancel on
  one and two stored shots and a new subject, then 적용), R2 (add or extract another photo while one
  is selecting), layout required first, fifth photo refused, confirmation required, hero not found,
  hero input without crop, reorder without moving, repeatable layout, what each control overwrites,
  time/free reset, stored time layout reopened unchanged, legacy order unconfirmed, hero file
  discarded, anchor drag; free edits (remove / add / replace) of a new and of a stored time layout
  keep hero, settings and remaining placements with the order unconfirmed, reopen and return to the
  time layout without segmenting, cancel keeps stored files; with an afterimage selected the layout
  step places a reversed order left → right (B < A < hero on actual anchors, slots filled, no
  segmentation, applied as drafted), a named blocker for the hero and for step 2, 현재 위치 유지, a
  hand-moved photo leaves its slot and placing again restores it, cancel after placing, the layout
  step ends an open selection; D088: three then six in all from an afterimage tile ([A, 원본, B]
  then [A, B, 원본, C, D, E] on transformed anchors, the hero's height, nothing moved before placing,
  five-step opacity, applied), remove + reverse + place again with the new count, spacing keeps size/
  angle/opacity, a seventh moment refused.
- `MultiShotControllerTest`, D090 (scripted SAM 3 with per-upload gates/failures, host with per-URI
  sizes/failures; every flow starts from `requestPick`/`onPicked` and `runAll`, never `placeByOrder`):
  three and six in all in one press ([A, 원본, B] / [A, B, 원본, C, D, E] on transformed anchors,
  off-centre hero, other photo sizes, picked order with a repeat kept, opacity, 적용), several people
  pause and the choice resumes, a hero not found resumes after points, a failure stops there and the
  retry sends only the rest, 진행 취소 with a late upload, settings change during a save, close during a
  save, the hero still rendering, all extracted → local layout and hand corrections kept until pressed
  again, a photo added after the layout, picker limits / refused / empty / superseded / late answers,
  free layout one at a time without a run, an unreadable photo in a pick, a cancelled batch read.
- `MultiShotToolTest` (real `EditorViewModel`, `ImageLoader` on a file URI): menu levels, 취소, one
  history step with undo/redo, the time layout's run through the view model (two picked photos, each
  of three pauses resumed by its choice, anchors on their slots, 적용 one step, undo/redo), the Undo/Redo availability right after 적용, selection kept, draft
  without crop, selection session released. `HistoryStackTest`: an immediate collector of the
  document sees that step's availability (REVIEW N1).
- `MultiShotSheetTest` (real clicks; a pill in a horizontal row is reached by scrolling its row into
  the sheet first, since `performScrollTo` scrolls only the nearest scrollable parent): layout step
  from an afterimage tile, the count line and N장 균등 배치, place / keep / arrangement, even-count
  hint, no direction for the even arrangement, path direction and distance, 위치 다시 배치 when settled, named blocker.
- `MultiShotSheetTest` (D090): the run notice and one press without an order check or a per-photo
  extract, the run on offer from any tile or step, a paused run (progress, candidates, 진행 취소, the
  rest disabled), an unreadable photo named by step, a changed composition with 남은 사진….
- Goldens `multishot_sheet_empty` (layout choice), `multishot_sheet_arrange`
  (free), `multishot_sheet_timeline` (time layout, layout step), `multishot_sheet_run` (a run waiting
  for a choice).
- Real golf photos, device and performance: work/RESULT.md.
