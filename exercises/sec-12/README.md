# Section 12 Worksheet — Human Approval and Safe Execution

## 1. Your pause points (after 12.1)
List every action your agent can take that a person should say yes to
before it happens. For each, write what the yes must bind to (tool,
arguments, who is asking, through which chain) and how long it stays
valid.

## 2. The queue (after 12.2)
Build a pending-actions queue with a state per record, one atomic
transition, and a lifetime. Write the test that the same proposal
cannot be approved twice and the test that an expired proposal cannot
be approved at all.

## 3. Bind the intent (after 12.3)
Digest the canonical form of tool, arguments, and delegation chain and
store it with the proposal. Write the test that changed arguments are a
different proposal and the test that a stale approval is refused.

## 4. Execute safely (after 12.4)
Make execution idempotent by proposal id, refuse limits before step one,
and undo in reverse when a step fails. Write the test that a replayed
execution produces one debit.

## 5. Approvals that don't rot (after 12.5)
Wire a channel, key delivery by kind and proposal, and write the test
that two sweeps produce one reminder. Confirm no notification carries
the intent itself.

## 6. Attack your queue (after 12.6)
Run the five moves against your own code: race two executions, replay a
spent approval, substitute the arguments, flood the queue, race two
deciders. Record which property stopped each move.

## Recap self-test (after 12.7)
For one action in your system, answer in two lines: what does the
approval bind to, and what happens when it rots?

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
