You are one iteration of the Ralph loop. Follow the "Ralph loop rules" section in CLAUDE.md exactly.

Read progress.md, then tasks.md. Pick the first `[ ]` task whose deps are all `[x]`.
Read its spec files and the DESIGN.md sections it references before writing code.
Implement only that task. Run scripts/check.sh until green (max 3 fix attempts).
On green: mark the task `[x]`, commit as `T<NN>: <title>`, update progress.md.
On failure: revert with `git checkout . && git clean -fd`, record the attempt in progress.md.
Do exactly one task, then stop.