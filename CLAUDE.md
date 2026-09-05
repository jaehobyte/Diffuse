# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## 5. Design system

Use @DESIGN.md as the source of truth for all UI and visual design work.

When creating or modifying interfaces:
- follow existing tokens and component patterns
- preserve the visual principles described in DESIGN.md
- reuse existing patterns before introducing new ones
- flag intentional deviations

## 6. Ralph loop rules

You are running unattended in a loop. No one will answer questions. Follow these rules exactly.

### Each iteration
1. Read `progress.md`, then `tasks.md`. Pick the first `[ ]` task whose `deps` are all `[x]`. Never pick a `[!]` task.
2. Read every file under `spec:` for that task and the DESIGN.md sections it references. Do not start coding before reading them.
3. Write a 3–6 line plan at the top of `progress.md` under `## Current`.
4. Implement. Modify only the paths listed under `touches`.
5. Run `scripts/check.sh`. Fix failures. Repeat until green or you have made 3 fix attempts.
6. If green: mark the task `[x]` in `tasks.md`, commit as `T<NN>: <title>`, update `progress.md` (`## Done` + `## Next`).
7. If not green after 3 attempts: `git checkout . && git clean -fd`, append a failure note to `progress.md` under `## Attempts`, end the iteration.
8. If the same task has 3 failed iterations: mark it `[!]`, describe the blocker in `blocked.md` (what you tried, what error, what decision a human needs to make), and move on.

### Hard limits
- Never modify: `.github/`, `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `local.properties`, `scripts/check.sh`, any `*.md` under `specs/`, `DESIGN.md`, this file.
- Never add a dependency not already in `libs.versions.toml`. If a task seems to need one, block it.
- Never make a network call from code or tests. `FakeAiProvider` only. `USE_REMOTE_AI` stays `false`.
- Never delete or weaken an existing test to make `check` pass. Never widen a golden tolerance. Never add `@Ignore`.
- Never regenerate screenshot goldens unless the task's `done when` names that golden.
- Never commit with a red `check`. Never force-push. Never touch branches other than the current one.
- Never work on two tasks in one iteration, even if the second looks trivial.

### When the spec is silent
- Choose the simplest option consistent with the spec and DESIGN.md, implement it, and record the decision in `progress.md` under `## Decisions` with the task id. Do not ask.
- If the choice would change a public interface another task depends on, do not choose — block the task instead.

### progress.md format
Keep `progress.md` under 150 lines; trim `## Done` entries older than 10 tasks.

### Definition of green
`scripts/check.sh` exits 0: lint, detekt, unit tests, Roborazzi verify. Nothing else counts.

