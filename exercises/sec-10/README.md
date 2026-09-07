# Section 10 Worksheet — Secure Agent Authorization

## 1. Three inputs (after 10.1)
For one sensitive tool in your system write the subject source, the
required grant, and the intent you would record. Mark which of the six
deny-by-default steps is currently missing.

## 2. The policy table (after 10.2)
Write the table for every tool your agent can call: name, required
scope, sensitive or not. Wrap the belt with the decorator and add the
test that an unlisted tool is refused for your most privileged actor.

## 3. Standing and task grants (after 10.3)
Classify your scopes into standing and task grants. Give every task
grant a granter and a lifetime, and write the expiry test with a
controlled clock.

## 4. The chain in the evidence (after 10.4)
Read the delegation chain from your agent's token and write it into
every decision's evidence. Confirm the chain gains a hop when your
service mints onward.

## 5. Attack drill (after 10.5)
Write the five escalation moves against your own agent as tests:
invent a tool, ask for admin, self-approve, retry after expiry, forge
the subject. Record which layer stops each — and which move has no
layer yet.

## Recap self-test (after 10.6)
Draw your five-slot map — subject, grants, table, intent, evidence —
and mark the slot that is still empty in your system.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
