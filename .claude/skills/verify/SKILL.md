---
name: verify
description: Run the Daymark test suites and report a verdict — web tests, type-check, build, and the Companion server tests. Use before committing, before calling a change done, and whenever someone asks whether the tests pass.
argument-hint: [web | server | all]
context: fork
agent: verifier
---

Run the Daymark checks and report a verdict. If the invocation named `web` or `server`, run only
that side; otherwise run everything.

```
cd companion/web && pnpm test && pnpm check && pnpm build
cd companion/server && ./gradlew test
```

Then count the server's real results from the XML rather than believing the Gradle banner:

```
cd companion/server && grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' build/test-results/test/*.xml
```

Report: passed or failed; the totals; how many XML files you found; and, only on a failure, the
failing test names with the assertion message. No logs.
