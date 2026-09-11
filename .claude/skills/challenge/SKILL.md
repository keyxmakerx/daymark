---
name: challenge
description: Have a claim attacked before it is believed — a test called proof, a security property, a design decision about to be built on. Use when being wrong would be expensive.
argument-hint: [the claim to attack]
disable-model-invocation: true
---

Something is about to be treated as settled. Before it is, have it attacked.

Build a self-contained brief for the `skeptic` agent:

1. **State the claim as a falsifiable sentence.** Not "the pairing code is secure" but "a person
   holding only the invitation link cannot obtain an enrolment ticket". A claim that cannot fail
   cannot be checked.
2. Name the files and tests that supposedly establish it, and paste the key excerpt.
3. Say what has already been tried, so the agent does not repeat it.
4. Tell it explicitly that "the claim survives" is an acceptable and useful answer.

Then spawn `skeptic`.

The agent will break the property on purpose and re-run the test that names it — that is this
repository's standard of proof, and the most common finding is that a test passes because its
detector is blind rather than because the property holds. Make sure the brief says which test is
supposed to be load-bearing.

When it returns: if the claim was refuted, fix it before anything else and say so plainly. If it
survived, record what was tried, so the next person does not pay for it twice.
