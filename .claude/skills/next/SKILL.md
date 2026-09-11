---
name: next
description: Take the next item of work off the backlog and do it alone — pick a ready issue, carry it out, check it, push it to a branch, and report back in plain English. Use when asked what is next, told to get on with it, or woken on a schedule with nobody watching.
argument-hint: [optional issue number]
---

You are working unattended. The person who owns this project is not a programmer and is probably
not reading this as it happens. Everything below assumes that.

## The one rule that matters

**One issue per session. Then stop.**

Not one issue and then a quick look at something else. Not "while I was in there I also fixed…".
Every extra turn re-sends this whole conversation to the model, so a session that wanders costs
many times what a session that finishes costs. Finishing and stopping is the single largest thing
you control about this project's running cost.

## Pick the work

If the invocation named an issue number, use it. Otherwise list open issues in `keyxmakerx/daymark`
labelled `claude-ready`, and take the **lowest-numbered** one that has no branch already pushed for
it. If two look equally ready, the smaller one wins — a finished small thing beats a stalled large
one.

Nothing labelled `claude-ready`? Then the backlog is empty. Say so in one line and stop. Do not
invent work. Do not go looking for something to improve. An empty queue is a correct outcome.

## Carry it out

Read the issue completely, including the "Watch out" section — it exists because someone already
knew where this goes wrong.

Work on a branch named `claude/issue-<number>-<short-slug>`. Create it from the current `main`:

```
git fetch origin main && git checkout -B claude/issue-<n>-<slug> origin/main
```

Delegate the noisy parts rather than doing them in this conversation — that is what they are for,
and their output would otherwise fill this session's context and end it early:

- `/ux` when the issue involves what a screen looks like or says.
- `/challenge` when the issue asks you to establish something, especially about security.
- `/walkthrough` when a person would have to *look* at the result to know it worked.
- `/tests` or the `verifier` agent for the suites. Never run them inline.

## Check it before you believe it

Run `/tests`. A failure stops the work — fix it, or push nothing and report what broke.

The house rule applies to your own work too: a passing test proves nothing unless it would fail
when the thing it tests is broken. If you wrote a test for something load-bearing, break the thing
on purpose and confirm that exact test reddens, then restore it.

## Push, and stop there

```
git push -u origin claude/issue-<n>-<slug>
```

**Never push to `main`. Never open a pull request. Never merge anything.** The maintainer merges
their own work; that is deliberate and is not a step you were forgotten from.

## Report

Comment on the issue with:

- What now works that did not before, in plain words. No file paths, no token counts, no commit
  hashes, no jargon — write it for someone who has not opened the repository.
- The branch name, so they can find it.
- What you did not finish, and why.
- Anything you decided that they might have decided differently. Be specific enough that they can
  overrule you.

Then leave the issue **open**. They close it when they have looked. Saying you are done is not the
same as being done, and only they can tell the difference.

## When to stop early instead

Stop and say so, rather than pressing on, if:

- The issue needs a decision only the owner can make — a trade-off about their product, their
  users, or their money. Write the options into an issue comment and stop. Guessing is worse than
  waiting.
- The work turns out much larger than the issue suggested. Push what is genuinely finished, and
  write down where you got to and what you learned. A half-finished piece with honest notes is a
  good session. A rushed whole one is not.
- Something looks wrong in a way the issue did not anticipate, and acting on it would go beyond
  what was asked.

Stopping early with a clear explanation is a successful run. There is no credit here for having
touched more files.
