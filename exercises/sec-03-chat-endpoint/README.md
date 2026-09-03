# Exercise SEC-03 — Ship the TrustDesk chat endpoint

**After lecture:** *Checkpoint: Ship the TrustDesk Chat Endpoint*

## Goal

Assemble the section's four pieces — the ChatClient doorway, keyless
configuration, per-user memory, and the failure guard — into a working
`/chat` endpoint, and prove it with a fully green test suite.

## Steps

1. **Memory** (`memory/ConversationMemory.java`): per-subject turns with a
   hard cap that trims oldest-first; blank subjects rejected. Write tests
   for isolation, trimming, and rejection.
2. **Guard** (`resilience/GuardedModelCall.java`): per-attempt timeout with
   cancellation, retries only for failures marked transient, bounded
   attempts, refusals in fixed safe phrases. Write tests for pass-through,
   bounded retry, first-strike refusal, and the deadline.
3. **Chat service** (`chat/ChatService.java`): one ChatClient, stored turns
   entering as ordinary user/assistant messages ahead of the new prompt.
4. **Endpoint** (`api/ChatEndpoint.java`): identity from Spring Security;
   history read and written only under `authentication.getName()`; the
   model call inside the guard; **only answers are remembered — refusals
   never touch memory**.
5. **Wiring** (`api/ChatConfiguration.java`): declare the memory cap and
   guard limits in one visible place.

## The bar

```bash
./mvnw test
```

Everything green — your own endpoint tests included. That is the section
assessment.

## Safety net

This branch (`checkpoint/sec-03`) holds the reference implementation.
Compare with your work:

```bash
git diff checkpoint/sec-03 -- src/
```

## Security checklist (defend each line)

- The subject is taken **once** and keys every stateful decision.
- History enters the prompt as conversation, never as instructions.
- A refusal is a first-class outcome — safe words, no provider internals,
  and no residue in memory for the next request to inherit.
