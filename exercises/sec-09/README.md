# Section 9 Worksheet — MCP with Java and Spring AI

## 1. Capabilities outside your process (after 9.1)
List the capabilities your agent needs that live in other services.
Mark each as a tool, a resource, or a prompt, and name the team that
owns the server.

## 2. Lifecycle decisions (after 9.2)
For your planned MCP server write down the transport, the request
timeout, the origin policy, and the authentication that will sit in
front of it. Note what happens on a protocol-version mismatch.

## 3. One capability, published (after 9.3)
Expose one read capability as an MCP tool. Remove any identity
parameter from its schema; derive the caller from the security
context. Add the no-caller test.

## 4. The client's belt (after 9.4)
Connect with a bearer header on every request. Write the allowlist for
exactly the tools your agent needs, and the test that a tool outside it
is refused before any request is sent. Fence every result.

## 5. Your resource identifier (after 9.5)
Write the resource identifier and the metadata your MCP server should
publish. List every downstream call that currently forwards an inbound
token.

## 6. A door of its own (after 9.6)
Give the MCP endpoint its own filter chain and audience. Publish the
metadata. Write the test that a token for your main API is refused at
the MCP door, and mint downstream credentials instead of forwarding.

## 7. Attack drill (after 9.7)
Find one place your system forwards an inbound token. Write the
naive-downstream test that shows what the downstream sees, then replace
the forward with minted credentials and watch the strict downstream
accept them with the actor named.

## Checkpoint self-test (after 9.8)
Add a sixth strap: a write capability (move a ticket). Prove the remote
belt refuses it until allowlisted, the server refuses it without the
scope, and the audit trail names the actor for a delegated token. Then
delete the audience validator on purpose and watch strap five fail.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
