# Section 5 Worksheet — Tools & Authority

## 1. Capability vs authority (after 5.1)
List every tool (or API wrapper) your assistant exposes. For each: its
capability in one sentence, and who decides authority for it today.
Any "nobody per request" answer goes on the shrink list.

## 2. Three layers (after 5.2)
For one tool of your own write the three layers: description
(guidance), schema (shape from the signature), and the authority check
inside the body.

## 3. The review question (after 5.3)
Trace one tool call across the three spans — authentication, actor
context, tool context. For every value it uses ask: could this have
come from the window? Any "yes" is a bridge repair.

## 4. Least privilege (after 5.4)
Rebuild one tool so it takes the smallest arguments its verb needs and
reads the subject from the actor, never from an argument.

## 5. Bound the loop (after 5.5)
Write down your agent's current stop conditions. Choose a bound from
your deepest legitimate workflow. Name both exits: natural stop, bound
hit.

## 6. Fetch and send audit (after 5.6)
Mark every tool argument that can become a network target. Rewrite one
so the destination is fixed at construction and the model supplies
only a validated identifier.

## 7. Shrink (after 5.7)
Pick your broadest tool. Strike every argument the actor already
implies. Split the rest into single verbs. Record before/after.

## 8. Your checkpoint (after 5.8)
One class, every tool registered together, the model as the only fake,
straps numbered in the test names, exits and refusals asserted.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
