# Section 7 Worksheet — Production RAG

## 1. Your retrieval boundary (after 7.1)
List every question your assistant answers from documents rather than
from a database. For each: the source, its owner, and who may read it.
Mark every place the model currently sees text with no owner.

## 2. Ingestion with provenance (after 7.2)
Ingest one real document set with slug, owner, title, and chunk number
on every chunk. Write the test that fails when any chunk arrives
without an owner.

## 3. A retriever that filters first (after 7.3)
Put a metadata filter on owner into your search request. Prove with a
test that an empty owner set retrieves nothing and that ranking never
sees an out-of-scope chunk.

## 4. Fenced, cited answers (after 7.4)
Wrap every excerpt in a fence naming slug, owner, and trust level.
Derive citations from the retrieved chunks, never from the model's
text. Test that an excerpt carrying instructions arrives as data.

## 5. Scope from the caller (after 7.5)
Write your scope grammar (prefix:owner). Map two real roles to owner
scopes and validate owner values like slugs. Add the tests proving a
crafted scope value grants nothing and that no scope means no model
call.

## 6. Plant a poisoned document (after 7.6)
Add one poisoned document to your corpus fixture: false policy plus
instructions, under its own owner. Write the four containment tests —
traced by owner, contained by scope, orders fenced, exposed by
citations. Note which layer you previously trusted that was actually
detection.

## Checkpoint self-test (after 7.7)
Add the fifth strap: a two-scope actor gets citations naming both
owners only, and two fenced excerpts with different owner attributes.
Then break scope on purpose — owners from the question instead of the
actor — and record which strap catches it.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
