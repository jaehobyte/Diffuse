# specs/topaz_enhancement.md — Topaz Labs photo enhancement

Owner task: work/tasks.md (Topaz Labs API → Vibe Editing UI, 2026-10-13)
Modules: `core/ai/topaz`, `core/data` (`TopazWorkspace`, create cleanup), `feature/editor/tools/enhance`,
`feature/export` (`MediaStoreFileStore`), `app` (DI, navigation)
Depends on: ai_provider.md, imaging.md, export.md, tool_groups.md, persistence.md, DESIGN.md §4, §7
Decisions: work/decisions.md D098

## 1. Entry points and purposes
One shared sheet, one controller (`TopazController`), one provider (`TopazProvider`). Where it is
opened from only decides its title and which purposes it offers (`TopazGroup`).

| Entry | Sheet title | Purposes (default first) |
|---|---|---|
| AI → 화질 개선, 디테일 → "AI 화질 개선" | 화질 개선 | 해상도 높이기 · 흐림 개선 · 노이즈 제거 · 사진 복원 · 창의적 디테일 · 먼지·스크래치 제거 |
| 라이트 → "AI 조명 보정" | AI 조명 보정 | AI 조명 보정 |
| 색상 → "AI 색 보정" | AI 색 보정 | AI 색 균형 · 흑백 사진 컬러화 |
| 선택 → 배경 제거 / 대상 추출 | — | **Not offered** (T4 blocked, §9) |

- The AI level order is 선택 · 지우기 · 채우기 · 확대 · **화질 개선** · 스타일 · 멀티샷 · 지시. The root level is
  unchanged (tool_groups.md §2).
- A shortcut from a manual sheet whose document differs from the sheet's baseline (or 선택 with a
  mask or points) is refused with "현재 조정을 적용하거나 취소한 뒤 이용하세요". Nothing is applied,
  discarded or uploaded. An untouched sheet is closed and the Topaz sheet opens on the purpose.
- 확대 keeps its meaning (new area outside the frame); 해상도 높이기 is "more pixels, same frame". The
  디테일 선명도 slider and the remote 흐림 개선 are different things and say so.
- 2026-10-14 (D101, vibe_edit.md §19, supersedes D100's hand-off): a plan's Topaz item runs **inside
  the 지시 workflow** — one `선택한 보정 실행` runs the local steps into an uncommitted candidate, then
  submits the Topaz step once on that candidate's full render through the same processing
  (`TopazProcessor`: render, upload, poll, cancel, download, validate, sampled decode) this sheet uses.
  This sheet is not opened and its 실행 is not needed; the result is compared on the 지시 canvas and
  kept by 고해상도 저장 / 새 편집본으로 열기 exactly as below (D098). Never started by a plan arriving.
- 2026-10-14 (D100): the plan's Topaz purpose may follow local steps and be a default suggestion; it
  is a toggleable item. (Its "commit first, then open this sheet" hand-off is superseded by D101.)
- 2026-10-13 (D099): a Gemini plan may propose **one** Topaz purpose (vibe_edit.md §16). Options a
  purpose does not offer (`TopazJob.problem()`) run as its defaults. Background removal / subject
  extraction (T4) is still not offered to the planner. (D099's "never runs from the plan" is
  superseded by D101.)

## 2. Wire contract (official docs, checked 2026-10-13)
Sources: Image API OpenAPI 1.2.0 (`openapi.gitbook.com/.../image-yaml.yaml`), the per-endpoint
reference pages and model pages under `developer.topazlabs.com`. Where they disagree the OpenAPI
schema wins; the camelCase model settings come from the model pages, which the schema passes
through as extra multipart fields (`additionalProperties`).

- Base `https://api.topazlabs.com/image/v1`; header `X-API-Key: <user key>`. Only requests to this
  base carry it; redirects on them are not followed (a 3xx is `BadResponse`).
- Submit: `POST <endpoint>`, `multipart/form-data`, `image` (JPEG) plus the fields below. Response
  `{process_id, source_id, eta}`. **Never retried.** A connect failure is `Offline`; any other IO
  failure after sending is `SubmitUnknown` ("요청이 접수됐는지 알 수 없어요").
- Status: `GET /status/{id}` → `status` ∈ Pending, Processing, Completed, Cancelled, Failed;
  `progress` 0–100; `credits`. Anything else, or JSON that does not decode, is never completion.
- Download: `GET /download/{id}` → `{download_url, head_url, expiry}` (the quickstart's `url` field is
  not the schema's and is refused). The signed URL is fetched with **no key** and never stored.
- Estimate: `POST /estimate` or `/estimate-gen` with `input_width`, `input_height`, `category`,
  `model`, `output_format`, and `output_width`/`output_height` for enhance endpoints → `{credits, duration}`.
- Cancel: `DELETE /cancel/{id}` → 204, or 409 when no longer cancellable. FAQ: cancelled at 0%
  progress is refunded; otherwise billed `1.1 × progress%`. The app never promises a refund.

| Purpose | Endpoint | `model` | Output size | Settings sent | Limits in/out (MP) |
|---|---|---|---|---|---|
| 해상도 높이기 (T1) | `/enhance/async` | `Standard V2` | 2×, 4× via `output_width` | `faceEnhancement` | 512 / 1024 |
| 흐림 개선 (T1) | `/sharpen/async` | `Standard` | input size | `faceEnhancement`, optional `sharpenStrength` 0–1 | 512 / 512 |
| 노이즈 제거 (T1) | `/denoise/async` | `Normal` | input size | `faceEnhancement` (Autopilot only) | 512 / 512 |
| 사진 복원 (T2) | `/enhance-gen/async` | `Wonder` | 1×, 2×, 4× | `faceEnhancement` | 128 / 128 |
| 창의적 디테일 (T2) | `/enhance-gen/async` | `Redefine` | 1×, 2×, 4× | `faceEnhancement` | 256 / 256 |
| AI 조명 보정 (T3) | `/lighting/async` | `Adjust V2` | input size | none (Autopilot) | 512 / 512 |
| AI 색 균형 (T3) | `/lighting/async` | `White Balance` | input size | none | 512 / 512 |
| 흑백 사진 컬러화 (T3) | `/lighting/async` | `Colorize` | input size | none | 512 / 512 |
| 먼지·스크래치 제거 (T3) | `/restore-gen/async` | `Dust-Scratch V2` | input size | none | 256 / 256 |

All requests send `output_format=jpeg`. Only `output_width` is sent; the API scales the height
proportionally, so it never letterboxes. Face recovery is sent as `faceEnhancement=false` when off,
because unprovided settings are filled by Topaz's Autopilot; when on it sends
`faceEnhancementStrength=0.5` and `faceEnhancementCreativity=0.5` (required, no documented default;
D098). `sharpenStrength` is sent only in 직접 조절; 노이즈 제거 offers Autopilot only because its page
lists two competing strength fields.

## 3. Options and limits
- Scales are enabled only when the output fits the model's output MP, the 32000px side bound and the
  app's ceiling of **256 MP**; the sheet shows the actual input and output W×H.
- A document with alpha (`.png` source or a cut-out) is refused before anything is sent: no model the
  app uses is documented to accept alpha, and it is never flattened silently.
- Generative purposes (사진 복원, 창의적 디테일, 먼지·스크래치 제거) carry "세부 모양·글자·얼굴이 다시
  그려질 수 있어요". 해상도 높이기 is never promoted to a generative model. One purpose per run.
- Option changes, purpose changes, opening the sheet and comparing send nothing. The estimate is
  fetched only on "예상 크레딧 확인", is dropped on any option change, and a failed estimate reads
  "예상 크레딧 확인 불가, 실행 시 크레딧 사용" — never 0.

## 4. Settings and errors
- 서버 설정 gains a masked "Topaz API 키" field (BYOK), stored in its own preferences file
  (`topaz_settings`). Saving an unchanged key keeps its generation; changing or clearing it bumps it,
  and a run in flight is abandoned ("API 키가 바뀌어 진행 중인 작업을 중단했어요"). No key ships in the
  APK, Gradle or the repository. Keys, photo bytes and signed URLs are not logged.
- Without a key the sheet still opens, explains the purpose, shows "서버 설정" and keeps 실행 off.
- Polling: first wait 2s, ×1.5 up to 10s, deadline 30 min, `Retry-After` honoured when present (the
  docs do not promise one), at most 5 consecutive transient failures (429/425, 5xx, timeout,
  offline). A timeout, an unknown status or a give-up cancels the job remotely, best effort.
- Errors (`TopazFailure`) map one-to-one to Korean lines: key (401/403), credits (402), rate (429/425),
  too large (413), rejected (400/404/409/412/415/422), offline, timeout, server (5xx), disk full,
  submit unknown, job failed, job cancelled, bad output.
- Cancel (overlay 취소, 닫기, Back, document change, key change) stops polling and download at once;
  the provider then sends `DELETE /cancel/{id}` under the key the run started with, from its own
  scope, bounded to 15s. The message says credits may already be used.

## 5. Input, files and the new edit copy
- Input: the committed document through `Renderer.full` (≤ 4096px long edge), JPEG q95, no EXIF,
  sRGB, upright. In a 지시 workflow (D101) the input is instead the reviewed, **uncommitted** local
  candidate (its crop and every local edit included) through the same `Renderer.full` — never the
  history's document and never the screen preview; nothing is committed and no project is made for it. Editor overlays and uncommitted sheet values are not in it. It is rendered when the
  sheet opens (local only) and again when the document changes under the sheet.
- Run identity: a run token plus the snapshot document plus the key generation. A late answer from a
  superseded run deletes its file and changes nothing.
- Output: streamed to `filesDir/topaz_work/<session>/output_<run>.jpg.part`, byte-capped at 400 MB
  whatever `Content-Length` says, checked by bounds only (MIME `image/jpeg`, width exact, height ±1),
  then renamed. The compare frame is decoded with `inSampleSize` at the input preview's size.
- **Compare** is this run's input vs its result (not the project's original): the canvas shows the
  result with a "후" label, and "누르는 동안 이전 보기" shows the input with "전". TalkBack's click flips it.
- **고해상도 저장** copies the result file byte for byte to MediaStore (export.md §고해상도 결과 저장).
  A retry copies again; it never calls Topaz. Nothing is saved automatically.
- **새 편집본으로 열기** loads the result through `ImageLoader` (working size, ≤ 4096px) and calls
  `ProjectRepository.create`; the app navigates to the new project over the current one. The sheet
  warns beforehand when the output exceeds 4096px and states that earlier adjustments are baked in.
  Creation is not cancellable once started; if nobody wants it any more it is deleted. A failed or
  cancelled create leaves no folder. The original project, its history and its files are untouched.
- Files: a session folder belongs to the open sheet — or, for a 지시 workflow, to its proposal
  session (D101); it is removed on close / session end after any save is cancelled and any create has
  finished. A result kept by a cancelled-but-answering provider is deleted. The first session in a process removes leftovers from
  earlier processes. Nothing is resubmitted after process death.

## 6. Sheet
Title → purpose chips and one-line description → options (배율, 얼굴 복원, 강도) → input/output size,
scope ("사진 전체", and "선택 영역이 있어도 사진 전체를 처리해요" when a mask is active), the 4096px
note, "Topaz Labs에서 사진을 처리하며 API 크레딧을 사용합니다", estimate → status (phase, the API's own
percentage, failure + retry hint, 서버 설정) → result (size, stale note, compare, 고해상도 저장, copy note)
→ pinned row. Setup: [취소 | 실행]. Current result: [닫기 | 새 편집본으로 열기]. A result whose options
have since changed is labelled "이전 설정의 결과예요" and the row returns to [취소 | 실행].
The canvas overlay (40% scrim, spinner, phase line, 취소) is shown for every non-idle phase.

## 7. Not in this spec
Video, frame interpolation, RAW, batch, purchase UI, a model browser, a Topaz step run by the plan
runner (`PlanRunner`; the 지시 workflow runs it after the runner, D101), several Topaz purposes chained,
T4 in plans, and editing the same document at the high resolution.

## 8. Tests
- `TopazHttpProviderTest` (MockWebServer, loopback only): fields and auth per purpose, poll states,
  429/Retry-After, give-up and deadline with remote cancel, POST never re-sent, lost submit, redirect
  refused, offline, wrong-size/corrupt output, schema vs quickstart download field, byte cap,
  caller cancellation under the starting key, estimate routes and failure.
- `TopazSettingsTest`: no key, persistence, generation, isolation from Gemini/SAM 3; job limits.
- `TopazToolTest` (real `EditorViewModel`, fake provider): zero submits without 실행, input is the
  full committed render, estimate only on request, one job per run, compare frames, document and
  history untouched, failure/retry, cancel, A-then-B, undo and key change mid-run, close cleanup,
  no key, alpha refusal, save retry without resubmit, one new edit copy at ≤ 4096, failed copy,
  shortcut refusal/acceptance.
- `TopazSheetTest`: actions per state, no wire names, scale limits, no key, failure, selection note,
  progress, stale result, TalkBack compare, large font, shortcut wiring; goldens `topaz_sheet_setup`,
  `topaz_sheet_result`, `topaz_sheet_no_key`.
- `MediaStoreFileStoreTest`, `TopazWorkspaceTest`, `ProjectPersistenceTest` (create cleanup).

## 9. Blocked: T4 (배경 제거 · 대상 정밀 추출)
Not implemented. The docs checked on 2026-10-13 do not fix the wire contract: `RemoveBG` is on the
model page but not in the schema's matting enum; `mode` ("Alpha or segmentation") has no literal
spelling; Object Matting's `box` ("relative left/top/right/top", default [0,0,1,1]) has no
documented multipart encoding and is absent from the schema. Shipping a guessed field is forbidden by
the task. Unblocking needs a confirmed request (e.g. from Topaz support or a paid test call) for
`model`, `mode` and `box`.

## Compare (review R1, 2026-10-13)
The compare control is `core/ui`'s `HoldPill`: it owns its pointer input (a `clickable` underneath
consumed the down, so a real press did nothing). Down → 전, up or a cancelled gesture → 후, never left
on 전; TalkBack's click toggles and announces the state. `TopazSheetTest` drives real
down / hold / up / cancel and the canvas label through state.
