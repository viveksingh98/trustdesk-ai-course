# Section 14 Worksheet — Observability and Production Operations

## 1. Four questions (after 14.1)
For one request through your agent, write down how you would answer
today: what happened, what it cost, how long, what was refused. Mark
every answer that would require reading a prompt or a tool result.

## 2. Wire the registry (after 14.2)
Give your chat client and tool calling manager one observation
registry. Add the redacting filter. Write the test that a password in
the user message never appears in any observation value.

## 3. Ask the trail (after 14.3)
Implement timeline, story, refusals by outcome, and actors over a
threshold over your evidence rows. Keep the refusal vocabulary in one
place and list every outcome your gates can produce.

## 4. Refuse before the spend (after 14.4)
Add a per-actor daily token budget and a per-minute rate as your
outermost advisor. Write the burst test and the next-day test on a
frozen clock. Pick the limits from a week of your token histogram.

## 5. The switch (after 14.5)
Add running, read-only, and halted modes at the model boundary and the
tool boundary. Record every flip with a bounded reason. Write the
runbook in five verbs and name who may pull the switch.

## 6. Investigate (after 14.6)
Simulate a morning with one probing actor and answer the five moves
from the trail alone. If a refusal is missing from your vocabulary,
add it in the same commit.

## Recap self-test (after 14.7)
Which of your telemetry values would an attacker be happy to read?
Remove them, then re-run the no-content test.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
