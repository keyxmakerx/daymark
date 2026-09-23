---
name: wrapup
description: Finish a piece of work properly — check it, write down what changed and why, commit, and push. Use when a change is done, or when the user says to save, finish up, or wrap up.
---

Close out the work so a fresh session can pick it up. The container is ephemeral: anything not
pushed is gone.

In order:

1. **Check it.** Run `/tests` (or spawn `verifier`) and wait for the verdict. Do not proceed on a
   failure — fix it, or say plainly what is broken and stop.
2. **Write down what changed — in the right place.** Add a `CHANGELOG.md` entry. Then, on GitHub:
   comment on the issue the work belongs to (what now works, what was deliberately NOT done and
   why — the part a later session cannot reconstruct); open an issue for anything deferred or found
   along the way, under the right tracking issue (the roadmap is #132); and turn any choice that
   belongs to the maintainer into a `needs-decision` issue. Never record status in a document or a
   code comment — no addenda, no to-do lists.
3. **Keep the docs honest.** If the change alters how something works, update the reference document
   that describes it (`docs/README.md` lists them). `companion/web/src/lib/docs.test.ts` resolves
   every backticked path in `docs/` and the root markdown, and every document a code comment names,
   against the tree. A path you write must exist.
4. **Commit** on the current `claude/*` branch — never `main`. Subject in the repository's register:
   lowercase `type(scope): what changed, stated from the product's point of view`. The body explains
   why, for someone who was not here.
5. **Push** with `git push -u origin <branch>`.

Do not open a pull request unless the user explicitly asks.

Then tell the user, in plain words: what now works that did not before, what you left undone, and
anything that needs their decision.
