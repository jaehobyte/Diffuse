# specs/gpu_render.md — AGSL render backend (D03)

Owner task: T66
Module: `core/imaging/render`
Depends on: render.md (the math and the goldens), architecture.md §8 (the budget), imaging.md
Supersedes: Deferred **D03**, promoted with conditions.

architecture.md §6 (Extension points) already anticipated the shape: *"GPU render — replace `Ops.kt` internals with
AGSL; interface unchanged"*, resting on *"v1 op math lives only in `Ops.kt`"*. That premise still
holds after 34 `AdjustKind` entries, and this spec exists to say what has to be true **before**
anyone starts, because the reason D03 was deferred has not been measured away.

## 1. Two prerequisites, both outside the loop

**1. minSdk 26 → 33.** `RuntimeShader` and AGSL arrive at API 33. `gradle/libs.versions.toml` is
frozen by CLAUDE.md, and this is a product decision as much as a technical one: it drops Android 8
through 12. A human makes it and edits the catalog. When it lands, two other statements go stale
and must be corrected in the same change:
- `specs/architecture.md` §2 (Tech stack), "minSdk 26, targetSdk latest stable".
- `specs/imaging.md`, "minSdk is 26 and HEIF decoding only arrives at API 28" — at minSdk 33 the
  HEIF caveat disappears entirely and that sentence becomes wrong, not merely stale.

**2. A benchmark number showing the budget is missed.** render.md sets it: `preview`, 4096px source
→ 1080px target, **< 100 ms p50** on a Pixel 6a-class device. `scripts/bench.sh` exists and is
excluded from `check`. Nobody has run it against a document carrying HSL ops.

Porting a renderer that meets its budget is work with no user-visible result, and it doubles the
surface every future op has to satisfy. **If the bench says the budget is met, this task is closed
as "not needed", not implemented.** That verdict is a perfectly good outcome and should be recorded
in `work/decisions.md` with the numbers.

The likeliest place for a real number is the HSL stack: T54's `HslOps` evaluates eight band weights
per pixel, and adjust_hsl.md §5 explicitly refused to fold consecutive HSL adjusts into one pass
(D14) because doing so changes the maths. Six 혼합 sliders are therefore six full passes over the
bitmap. **Measure that case specifically** — a document with six HSL adjusts at 1080px — because it
is the one this feature would help most.

## 2. What changes, and what must not
- `Renderer`'s interface is **unchanged**. `preview`, `full`, `onProgress`, cancellation between
  operations, the cache keys — none of it moves.
- `Ops.kt` keeps its function signatures. The port replaces internals; a caller cannot tell.
- The masked path stays as it is: `out = lerp(in, op(in), maskAlpha)` (selection_tool.md §8.1) is a
  composite, not op math.
- `GenerativeErase`, `GenerativeFill` and `Outpaint` **stay on the Canvas path**. They composite
  stored PNGs; there is no per-pixel math to move to a shader.
- `Crop` stays where it is. It is a transform and a copy.

With minSdk at 33 there is **no fallback path and no runtime branch**: one implementation, or the
kind stays on the CPU (§3). A `Build.VERSION` check appearing anywhere in this work means
prerequisite 1 was skipped.

## 3. The goldens are the judge
render.md's tolerance is **2/255 per channel over 99.9% of pixels**, and adjust_hsl.md §10 governs
the HSL goldens. Both stand exactly as they are.

- **No golden is re-recorded.** Not one. A port that changes pixels is not a port.
- **The tolerance is not widened.** CLAUDE.md forbids it outright and it is the whole verification
  story here.
- A kind whose shader cannot match its golden inside that tolerance **stays on the CPU
  implementation**. Partial adoption is an acceptable outcome; a re-recorded golden is not.
  Floating-point differences between a Skia shader and a Kotlin loop are expected on some kinds —
  `smoothstep` boundaries and the unsharp mask are the candidates — and the tolerance already has
  room for the ones that are only rounding.

That makes this a task with an unusually strong success criterion: `scripts/check.sh` green with an
untouched `golden_manifest.txt` and an untouched tolerance, plus a bench number that improved.
Record both numbers, before and after, in `work/decisions.md`.

## 4. Scope, if it goes ahead
In one pass, smallest first:
1. The eight scalar kinds (`Exposure` … `Vignette`) — one shader each, or one shader with a uniform
   selecting the branch, whichever reads more simply.
2. The 24 HSL kinds — one shader parameterized by band centre and channel, since `HslOps` is
   already written that way (adjust_hsl.md §3's single nullable `HslTarget` field).
3. Chaining, only if the bench still says it is needed: consecutive shader passes composed without
   a round trip to a `Bitmap`. Note that this is the same maths-changing hazard D14 named for the
   CPU path — composing shaders is safe only where the ops are per-pixel and order-independent
   within the pass. If it is not obviously safe, do not do it; the per-op passes are the spec.

Step 3 is the one that can turn a port into a rewrite. It is separable and should be a separate
task if it happens at all.

## 5. Tests
- Every existing render and HSL golden, unchanged and un-re-recorded (§3).
- `Ops` property tests, unchanged.
- A test asserting the manifest and the tolerance constant are untouched is unnecessary — git does
  that, and the commit diff is the evidence.
- `scripts/bench.sh` before and after, both numbers in the commit message and in
  `work/decisions.md`. The benchmark stays excluded from `check` (render.md).
