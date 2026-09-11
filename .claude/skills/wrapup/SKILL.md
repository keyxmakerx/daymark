---
name: wrapup
description: Finish a piece of work properly — check it, write down what changed and why, commit, and push. Use when a change is done, or when the user says to save, finish up, or wrap up.
---

Close out the work so a fresh session can pick it up. The container is ephemeral: anything not
pushed is gone.

In order:

1. **Check it.** Run `/verify` (or spawn `verifier`) and wait for the verdict. Do not proceed on a
   failure — fix it, or say plainly what is broken and stop.
2. **Write down what changed.** Add a `CHANGELOG.md` entry. If the work shifted where the project
   stands — something finished, something deferred, a decision made — append a dated addendum to
   `docs/SESSION_STATE_2026-08-28.md`, newest last. Record what was deliberately NOT done and why;
   that is the part a later session cannot reconstruct.
3. **Keep the docs honest.** `companion/web/src/lib/docs.test.ts` resolves every backticked path in
   `docs/` and the root markdown against the tree. A path you write must exist.
4. **Commit** on the current `claude/*` branch — never `main`. Subject in the repository's register:
   lowercase `type(scope): what changed, stated from the product's point of view`. The body explains
   why, for someone who was not here.
5. **Push** with `git push -u origin <branch>`.

Do not open a pull request unless the user explicitly asks.

Then tell the user, in plain words: what now works that did not before, what you left undone, and
anything that needs their decision.
