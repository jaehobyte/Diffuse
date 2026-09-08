# specs/tool_groups.md — the tool strip has two levels

Owner task: T78
Modules: `feature/editor`
Depends on: editor_shell.md, canvas.md, DESIGN.md §1, §4, §5, §7
Amends: editor_shell.md ("The tool strip is driven by this list")

## 1. The problem, stated as a number
The strip holds ten tools today: 라이트 · 색상 · 혼합 · 자르기 · 디테일 · 선택 · 지우기 · 채우기 ·
확대 · 지시. style_match.md and auto_enhance.md add two more, and every one of the new ones is an
AI tool.

DESIGN.md §4 already makes the strip "horizontally scrollable", so nothing is *broken*. What is
wrong is different: at ten items a phone shows about five, so **half the app is behind a scroll
gesture with no affordance**, and the half that is hidden is the half a user came for. Six of those
ten carry the 6dp accent dot, which stops meaning "this one is special" when it is on most of them.

## 2. The shape: one strip, two levels
Tapping **AI** replaces the strip's contents with the AI tools and a leading ← item. Tapping ←, or
committing any tool, comes back.

```
rest      [라이트][색상][혼합][자르기][디테일][ AI ]
AI open   [  ←  ][선택][지우기][채우기][확대][스타일][자동][지시]
```

- **The strip's height, item size and scrolling are unchanged.** DESIGN.md §4's 72dp / 64dp / 24dp
  icon + `label` all still hold; what changes is which list is bound to it.
- **One surface, so still one accent.** DESIGN.md §1 counts the strip as one surface and allows it
  one accent at rest: the selected tool's indicator. A second row would have been a second surface
  and a second accent, which is the rule this design exists to keep.
- The AI parent carries the 6dp accent dot (§4's AI marker). **Its children stop carrying it** —
  inside the AI level every tool is an AI tool, so the dot marks nothing. The dot moves up a level
  rather than being repeated six times.
- The ← item is `editInk`, not accent, and is a tool-shaped item so the row stays one rhythm.

### The two designs this rejects
**A second row that expands above the strip.** It is the obvious answer and it is what most editors
do. It costs 72dp of canvas the moment it opens — DESIGN.md §5 gives the canvas "all remaining
space" and §4 caps sheets at 45% precisely so the photo stays visible — and it puts a second
accent-bearing surface on screen. Rejected on both.

**A bottom sheet of AI tools.** Sheets are for committing an edit (DESIGN.md §4: title → controls →
[취소 | 적용]); a menu has nothing to apply. It would also cover the canvas to show a list of
things that then each open their own sheet.

## 3. The model
`Tool` stops being a flat enum and gains a parent:

```kotlin
enum class ToolGroup { Root, Ai }

enum class Tool(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val group: ToolGroup = ToolGroup.Root,
)
```
- `isAi` is **deleted**, replaced by `group == ToolGroup.Ai`. It existed to drive the accent dot,
  and §2 moved the dot; keeping both would be two spellings of one fact.
- The strip binds `Tool.entries.filter { it.group == level }`, so adding a tool is still one enum
  entry — editor_shell.md's rule, unchanged.
- `Tool` is still serialized nowhere, so nothing migrates.
- The open level is UI state (`EditorUiState.toolLevel`), not document state. It resets to `Root`
  on every entry to the screen: a user returning to a photo should see the whole app.

## 4. Behaviour
- Tapping **AI** opens the level and selects nothing. It is navigation, not a tool.
- Tapping **←** closes it. So does committing or cancelling any tool sheet: 적용 on 지우기 returns
  the user to the top level, because the next thing they do is usually not another AI call.
- **A disabled child does not close the level.** 지우기 greyed for a missing selection is exactly
  when a user needs 선택, which is one item away.
- **The AI parent is never itself disabled**, even when every child is. A parent that cannot be
  tapped hides the reason its children cannot be, and generative_erase.md §9's greyed-but-tappable
  rule exists so the user can find out why.
- System back closes the level before it leaves the screen.
- The transition is a fade of the row's contents. No slide, no reorder animation: DESIGN.md §7's
  "no gradients, neon, glow" is about restraint, and a strip that shuffles is the motion version of
  that.

## 5. Landscape and tablets
DESIGN.md §8 moves the strip to the right edge in landscape and turns Edit sheets into a 320dp
right panel at 600dp+. Neither changes here: the level is what the strip is bound to, not where the
strip is. At 600dp+ the root level fits without scrolling, and the AI level still earns its
grouping — six items among eleven is a grouping question, not a width question.

## 6. Strings
| Key | Value |
|---|---|
| `editor_tool_ai` | AI |
| `editor_tool_back` | 뒤로 |

`editor_tool_ai` is two Latin letters in a Korean UI, deliberately: DESIGN.md §7 forbids
"✨ AI Magic" marketing, not the term itself, and 인공지능 is longer than the 64dp item and reads
like a textbook.

## 7. Tests
- `ToolStripLevelTest`: the root level shows exactly the root tools plus AI; tapping AI shows
  exactly the AI tools plus ←; tapping ← returns; the AI parent selects no tool.
- Every `Tool` belongs to exactly one group and appears at exactly one level — the test that fails
  when a tool is added to the enum and forgotten here.
- Committing a sheet returns to the root level; cancelling does too; a disabled child does not.
- The accent dot is on the AI parent and on **no** child.
- The AI parent is enabled when every child is disabled.
- System back closes the level rather than the screen, then the screen.
- Goldens: `editor_shell_default` **re-recorded** — the strip now ends at AI, which is the whole
  point — and one new `editor_shell_ai_open`.

## 8. What this does not do
- **No third level.** Two is a grouping; three is a menu.
- **No reordering, no favourites, no "recently used".** The order is the enum's, and the enum's
  order is a design decision, not a usage statistic.
- **No badge counts, no "new" markers.**

## 9. Open decisions — a human answers these
1. **Which tools are AI.** This spec puts 선택 · 지우기 · 채우기 · 확대 · 스타일 · 자동 · 지시 at
   the AI level and leaves the five adjust-and-crop tools at the root. 선택 is the arguable one: it
   calls SAM 3, so it is AI by mechanism, but it is a *prerequisite* for three of the others, and
   burying it one level down is what makes 지우기's "먼저 영역을 선택해주세요" a longer trip. The
   alternative is 선택 at the root, where it reads as a manual tool it is not.
2. **스타일 is not an AI tool** (style_match.md §4) but sits at the AI level here, because its
   컬러 매칭 half is. Either it moves to the root and 컬러 매칭 becomes hard to find, or the level
   is named for where things are rather than for what they cost.
