# Section 4 Worksheet — Production Prompting

Work through these after each lecture; bring the finished sheet to the
recap self-test.

## 1. Guidance or authorization? (after 4.1)
Take one "rule" sentence from any assistant prompt you have seen.
- Classify it: guidance (voice/scope/format) or attempted authorization.
- If it attempts authorization, write the one-line deterministic control
  that should replace it.

## 2. Template rewrite (after 4.2)
Find one string-concatenated prompt in any codebase or tutorial.
- Rewrite it as a template: authored instructions, one labeled slot,
  a size limit.
- Note which version an attacker's input can restructure, and why the
  other cannot.

## 3. Declare your reply shape (after 4.3)
Pick one place where code reads a model reply as free text.
- Declare the record it actually needs.
- List the legal domain for every field.
- Write the validation that refuses everything else (echo nothing).

## 4. Absence audit (after 4.4)
Audit one structured-output record you use.
- Mark every primitive field that silently absorbs absence.
- Convert those to wrapper types and write the presence check plus its
  fixed refusal reason.

## 5. One more attack (after 4.5)
Add one red-team test to your own pipeline:
- Pick an abuse from the lecture (fence escape, syntax injection,
  oversize, forged reply, smuggled value).
- Aim it at your template or reply parser and assert the exact refusal.
- Record the test name and the defense it pins.

## Recap self-test (after 4.6)
Rebuild the section map from memory: the line, both edges, the five
pinned abuses. Mark the piece you explained least confidently and
rewatch that lecture's code walkthrough.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
