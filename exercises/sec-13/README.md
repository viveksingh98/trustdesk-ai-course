# Section 13 Worksheet — Testing and Evaluation

## 1. Three rings (after 13.1)
Sort every test you already have into the three rings — deterministic
code, contracts, judgment. List each test that calls a live model on
the merge path; those move to the scheduled lane.

## 2. Fixtures and slices (after 13.2)
Replace one anonymous fake model with a shared scripted fixture that
exposes tool-calling options and throws when its script runs out. Add
a frozen clock. Write one unit test for a rule and one slice test
through the real tool loop, and assert on the audit trail.

## 3. Contracts (after 13.3)
Pin the shape of your assembled prompt (system message from constants
and references only), the typed outcome of one structured output, and
the parameter names of every tool. Change one tool parameter and
watch the contract fail first.

## 4. Attacks as fixtures (after 13.4)
Move your red-team strings into one fixture file with a gate column.
Replay every row through the structural checks and its named gate.
Add one row from your own logs before you fix anything.

## 5. Score the judgment (after 13.5)
Write four golden cases and a scorer with five checks. Run a good
answerer and a degraded one; assert the drop. Confirm no finding and
no report line contains answer text.

## Recap self-test (after 13.6)
For your newest gate, say which ring its test belongs in and what
would make that test pass vacuously.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
