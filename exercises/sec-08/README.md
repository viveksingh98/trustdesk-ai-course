# Section 8 Worksheet — Identity with Spring Security

## 1. Your token inventory (after 8.1)
For your own system, name the access token and the ID token, where
each is validated, and which token your agent currently holds when it
calls a tool. Mark any place an ID token is used as an API credential.

## 2. The front door (after 8.2)
Write URL rules for your two most sensitive paths. Define a
RoleGrants-style translation for two roles. Add the 403-before-404
ordering test that proves authorization ran before routing.

## 3. The inner gate (after 8.3)
Annotate your most sensitive service method with a PreAuthorize rule
that takes the actor from the security context. Write the two tests —
wrong identity and no identity — and confirm they raise different
exceptions.

## 4. The bearer door (after 8.4)
Add an audience validator to your resource server. Mint a token for a
different audience in a test and confirm it is refused before any
controller runs. Note what changes for your provider's JWK Set URI.

## 5. The three-question test (after 8.5)
Take one real action your agent performs. Can the downstream service
tell a human from an agent acting for them? Which agent? Is there
anything a fully compromised agent could not do that the user could?
Record which identity is invisible today.

## 6. The delegation shape (after 8.6)
Write your delegated token: subject the person, actor the agent, scope
the intersection. Name one action in your domain that must be refused
whenever the actor claim is present.

## Attack drill (after 8.7)
Mint a token for someone else's API against your own resource server
and record whether it is accepted. Add the regression test that keeps
the audience validator from being deleted in a refactor.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
