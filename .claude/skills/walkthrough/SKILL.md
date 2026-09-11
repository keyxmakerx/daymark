---
name: walkthrough
description: Run the Daymark consoles in a real browser and report what a person actually sees. Use when a screen needs checking for real rather than by unit test, or before calling a user-facing change done.
argument-hint: [which screen or ceremony]
disable-model-invocation: true
---

The node suites never render anything, so nothing in this repository has been *seen* until this
runs. It is also where the last two real bugs were found.

Brief the `browser-pilot` agent with:

1. Which screen or ceremony to walk, and the path a person takes to reach it.
2. What is supposed to happen, in the product's terms — not selectors.
3. Anything recently changed, so it looks there hardest.
4. Where to put screenshots.

The agent knows the server command, the browser path, and the traps that have cost time before.

When it returns: relay what a person sees. Take seriously anything it flags as reading oddly even
when nothing is broken — a heading printed twice, a sentence promising something the product no
longer does. Those are the findings unit tests structurally cannot make.
