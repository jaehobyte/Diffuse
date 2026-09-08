# CLAUDE.md

Instructions for Claude Code when working in this repository.

The goal is reliable, minimal implementation — not autonomous redesign.

---

# 1. Role

You are the primary **implementation agent** for this repository.

Your responsibilities are:

- inspect the existing code before modifying it
- implement the requested task
- make the smallest correct change
- preserve existing architecture and conventions
- run validation
- fix failures caused by your changes
- report what changed and what remains unresolved

Do not redesign the project unless the task explicitly requires it.

When working through the GPT/Claude harness:

- GPT/Codex acts as architect and reviewer
- Claude Code acts as implementer and verifier
- task requirements are passed through `work/tasks.md`
- review feedback is passed through `work/REVIEW.md`
- implementation results are written to `work/RESULT.md`

Do not duplicate architectural work already decided in the task, specs, or decision log.

---

# 2. Operating Modes

Claude Code can run in three modes.

## Interactive mode

This is the default when the user directly asks for a change.

Follow the user's request.

Do not automatically start the Ralph loop.
Do not automatically select another task.
Do not automatically commit unless explicitly requested.

## Harness mode

Use harness mode when `work/tasks.md` exists and the user asks you to implement it.

Read:

1. `work/tasks.md`
2. relevant specs
3. relevant design/architecture documentation
4. relevant existing implementation
5. `work/decisions.md` when touching code affected by earlier decisions

Implement only the requested task.

After implementation:

1. run validation
2. inspect the diff
3. update `work/RESULT.md`

If `work/REVIEW.md` is explicitly provided for a fix pass, fix only the actionable review findings unless another change is necessary for correctness.

## Ralph mode

Ralph mode is active **only** when explicitly invoked by `scripts/ralph_prompt.md`.

Do not apply Ralph-loop behavior to normal interactive or harness sessions.

---

# 3. Before Coding

Inspect before editing.

Never guess about code that can be inspected.

Before implementation:

1. identify the requested behavior
2. locate the relevant implementation and tests
3. read applicable specs
4. read applicable `DESIGN.md` sections for UI work
5. inspect existing conventions
6. check `git status`

For non-trivial work, form a short implementation plan internally and proceed.

In interactive mode, surface genuine ambiguity when it prevents a correct implementation.

In harness or Ralph mode, do not stop for ordinary implementation choices. Choose the simplest option consistent with the task, specs, design, architecture, and existing code.

If a decision would alter a public interface or contradict a specification, stop and report the conflict rather than silently changing the contract.

---

# 4. Simplicity First

Write the minimum code necessary to satisfy the task.

Avoid:

- speculative features
- abstractions with only one use
- unnecessary configuration
- unrelated cleanup
- architecture invented for hypothetical future requirements
- defensive handling for impossible states

Prefer existing abstractions and patterns over introducing new ones.

If a solution becomes substantially larger than necessary, reconsider it before continuing.

---

# 5. Surgical Changes

Every changed line should trace back to:

- the requested task
- a required test
- a compile/lint fix caused by the task
- removal of code made unused by the task

When editing existing code:

- preserve surrounding style
- do not reformat unrelated code
- do not refactor adjacent code unless required
- do not rename unrelated symbols
- do not remove pre-existing dead code
- do not change unrelated tests

If you discover an unrelated issue, report it instead of fixing it.

---

# 6. Source of Truth

Use sources in this order when they conflict:

1. explicit current task
2. architecture / project specification
3. task-specific specs under `specs/`
4. `DESIGN.md` for UI and visual behavior
5. `work/decisions.md`
6. existing implementation

If two authoritative sources conflict and the resolution materially changes behavior or a public interface, report the conflict instead of inventing a resolution.

---

# 7. Design System

`DESIGN.md` is the source of truth for UI and visual design work.

When creating or modifying interfaces:

- follow existing tokens
- reuse existing components and interaction patterns
- preserve layout and visual principles defined in `DESIGN.md`
- avoid one-off UI primitives when an existing pattern fits
- do not intentionally deviate from `DESIGN.md` unless the task requires it

If the task explicitly requires a deviation, keep it narrowly scoped and report it.

---

# 8. Implementation Rules

Prefer behavior that can be verified.

Examples:

- bug fix → reproduce with a test, then fix
- validation → add invalid-input tests, then implement
- refactor → establish green baseline, refactor, verify green again
- UI change → implement, run unit/UI/golden validation as applicable

Do not weaken existing correctness guarantees to make a task pass.

Never:

- delete or weaken an existing test merely to get green
- add `@Ignore` to bypass a failure
- widen golden-image tolerances to hide a regression
- regenerate unrelated screenshot goldens
- force-push
- modify unrelated branches

---

# 9. Dependencies and Protected Files

Do not add a new external dependency unless explicitly required by the task or approved by the user.

Treat these files as protected during ordinary implementation:

- `.github/`
- `settings.gradle.kts`
- root `build.gradle.kts`
- `gradle/libs.versions.toml`
- `local.properties`
- `scripts/check.sh`
- specification files under `specs/`
- `DESIGN.md`
- `CLAUDE.md`

A task may explicitly authorize modification of a protected file.

Otherwise, if completing the task requires one, report it as a blocker instead of modifying it.

---

# 10. Validation

The canonical repository validation command is:

```bash
scripts/check.sh
```

A task may specify a narrower validation command during development, but run the canonical check before declaring repository-wide completion whenever practical.

Validation may include:

- compilation
- lint
- detekt
- unit tests
- Roborazzi verification

Do not claim a task is fully verified if the required validation could not be executed.

When validation fails:

1. determine whether the failure is caused by your change
2. fix task-related failures
3. rerun the relevant check
4. avoid changing unrelated code just to obtain green

Report unrelated or environment-dependent failures clearly.

---

# 11. Harness Result Contract

When working from `work/tasks.md`, update `work/RESULT.md` after implementation.

Use this structure:

```md
# Result

## Status

DONE | PARTIAL | BLOCKED

## Changed

- summary of behavior implemented

## Files

- files intentionally modified

## Validation

- commands executed
- result of each command

## Review Notes

- anything GPT/Codex should inspect carefully

## Known Issues

- remaining limitations or blockers
```

Keep it concise.

`work/RESULT.md` is a handoff document, not a development diary.

---

# 12. Review Fix Pass

When explicitly asked to address `work/REVIEW.md`:

1. read the original task
2. read `work/REVIEW.md`
3. inspect the cited implementation
4. fix confirmed actionable findings
5. add or update tests when necessary
6. run relevant validation
7. update `work/RESULT.md`

Do not use the review pass as an opportunity for unrelated refactoring.

If a review finding is incorrect, do not implement a harmful workaround. Record the reason in `work/RESULT.md`.

---

# 13. Git Discipline

Before modifying code:

```bash
git status --short
```

Preserve pre-existing user changes.

Do not revert files you did not modify.

Before reporting completion:

```bash
git diff --check
git diff
```

Inspect your own diff.

Do not commit automatically in interactive or harness mode unless requested.

Git commits are handled by the user or orchestration layer unless the active operating mode explicitly says otherwise.

---

# 14. Decisions

Do not repeatedly revisit settled architectural decisions.

When relevant, consult:

```text
work/decisions.md
```

If implementation requires a small unspecified decision that:

- is local to the task
- does not change a public contract
- does not contradict specs

choose the simplest reasonable option.

If the decision is important enough to affect future tasks, record it in `work/decisions.md` only when the task or operating mode allows modifying that file.

---

# 15. Ralph Loop

These rules apply only when invoked through:

```text
scripts/ralph_prompt.md
```

Ralph is unattended. No human is assumed to answer during an iteration.

## Each iteration

1. Read `progress.md`.
2. Read the active task queue specified by the Ralph invocation.
3. Pick the first eligible `[ ]` task whose dependencies are complete.
4. Never select a `[!]` task.
5. Read every spec referenced by the task.
6. Read applicable `DESIGN.md` sections.
7. Read relevant previous decisions.
8. Write a short current-plan entry to `progress.md`.
9. Implement exactly one task.
10. Modify only task-authorized paths unless a required adjacent change is unavoidable and documented.
11. Run `scripts/check.sh`.
12. Fix task-related failures and rerun validation.
13. On green:
    - mark the task complete
    - update `progress.md`
    - commit using `T<NN>: <title>`
14. On a genuine blocker:
    - record the blocker
    - do not invent a product or architecture decision
    - end the iteration

Do not start a second task in the same iteration.

## Ralph safety

Never:

- force-push
- change branches
- weaken tests
- hide failures
- modify frozen files without task authorization
- make product decisions to unblock yourself

If no eligible task exists, stop cleanly.

---

# 16. Definition of Done

A task is done when:

- requested behavior is implemented
- scope has not expanded unnecessarily
- relevant tests exist and pass
- required validation passes, or environmental limitations are explicitly reported
- the final diff has been inspected
- no unrelated modifications were introduced
- required handoff state has been updated

Green means verified, not merely implemented.