# Section 15 Worksheet — Deployment and Final Security Review

## 1. The production build (after 15.1)
Add a production profile with no fallback secrets and content logging
written off. Write a readiness gate that names failed checks — never
values — and a test that a placeholder key stops the context.

## 2. Secrets as files (after 15.2)
Deliver your model key and signing keys as owner-only files in one
directory. Add the permission-checking reader. Rotate a signing key by
adding its public key to the bundle before switching the signer, and
prove the old token still verifies.

## 3. The hardened container (after 15.3)
Write a three-stage image and a compose file with one way in and one
way out. Pin the facts with a contract test: unprivileged, read-only,
capability-free, unexposed, secrets as files, internal network marked
internal, egress through a proxy with one allowed host.

## 4. Lock the supply chain (after 15.4)
Generate a digest lock for your resolved jars. Bump one dependency,
read the diff, re-lock. Confirm the image's runtime flags open no
debug or management port.

## 5. The final red team (after 15.5)
Run all six moves against your own stack on a frozen clock: poisoned
document, invented tool and forged subject, raced and replayed
approval, flood, containment under pressure, the deployment wall and
the trail. Fix anything that fails in a commit of its own.

## 6. Sign off (after 15.6)
Fill the six-column checklist with a test name per line. Generate the
evidence pack. Answer the questions a test cannot, and write down two
residual risks with owners.

## Finale (after 15.7)
Write the five sentences you will carry, in your own words, at the
top of your repository's README.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
