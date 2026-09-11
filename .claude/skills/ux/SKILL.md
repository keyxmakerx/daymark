---
name: ux
description: Get a design or copy decision made for a Daymark screen — layout, wording, hierarchy, what a screen says when something is missing or refused. Use when there is a real visible choice to weigh and the answer should not be improvised.
argument-hint: [the screen or question]
disable-model-invocation: true
---

The user wants a design decision made. They are not a designer, so the deliverable is a decision
they can accept, not a menu they have to choose from.

**Your job is the brief, not the design.** The `designer` agent runs on an expensive model with
`Read` and nothing else — no search, no shell — and a hard turn cap. That is deliberate: it cannot
go exploring, so whatever it needs must arrive in the prompt. A vague brief wastes the whole call.

Before spawning it:

1. Work out what is actually being asked. If the user's words are broad ("make the therapist page
   better"), narrow it yourself from the code rather than asking them to be more technical.
2. Read the relevant component and paste the **actual current markup and copy** into the prompt.
   Do not tell the agent where to look — it has no search tools.
3. State the constraint that makes this hard: what the screen must still do, what it cannot say,
   what a person sees at the worst moment.
4. Name the decision you want back, concretely. "What should this say when there is nothing to
   show" beats "review this page".

Then spawn `designer` with that self-contained brief.

When it returns: relay the decision in plain words, say what it rejected and why, and implement it
yourself. Do not ask the user to adjudicate between options — if the agent left a genuine tie, break
it and say which way you went.
