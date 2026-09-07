# TrustDesk — Secure AI Agents with Java & Spring Boot

Course project for **Secure AI Agents with Java & Spring Boot: Spring AI, MCP,
RAG & Identity** (Prompt Vidya, Udemy).

TrustDesk is an internal help-desk assistant that you build and harden across
the course: a Spring Boot application that lets an AI agent read tickets,
search a knowledge base, and raise access requests — with every trust boundary
made explicit and enforced in code.

## Stack

- Java 25
- Spring Boot 4.1
- Spring Security
- Spring AI 2.0 (OpenAI starter)
- Maven (wrapper included — no global Maven install needed)

## Getting started

You need a **JDK 25** installed (IntelliJ IDEA can download one for you —
any distribution such as Eclipse Temurin works). Everything else comes with
the repository.

```bash
git clone https://github.com/viveksingh98/trustdesk-ai-course.git
cd trustdesk-ai-course
./mvnw test
```

If `contextLoads` passes, your toolchain is ready. On Windows use
`mvnw.cmd test`.

An OpenAI API key is **not** required to build or run the tests. When the
course reaches the first live agent call, set it in your environment:

```bash
export OPENAI_API_KEY=sk-your-key
```

Never commit an API key to the repository.

## How this repository is organised

- `src/` — the TrustDesk application. It starts as a minimal skeleton and
  grows lecture by lecture; you write the code with the videos.
- `exercises/` — one folder per exercise, with instructions and templates.
  Start with [exercises/sec-01-boundary-map](exercises/sec-01-boundary-map).
- **Checkpoint branches** — one branch per section, containing the completed
  state at the end of that section:

  ```bash
  git branch -r          # list checkpoints
  git switch checkpoint/sec-01
  ```

  If you fall behind or want to compare with the reference implementation,
  switch to the checkpoint for the section you just finished, or diff it
  against your own work:

  ```bash
  git diff checkpoint/sec-01 -- src/
  ```

Stay on `main` for your own work; use checkpoints for reference and recovery.

## Section map

| Section | Focus | Checkpoint | Worksheet |
| --- | --- | --- | --- |
| SEC-01 | Threat model & the TrustDesk boundary map | `checkpoint/sec-01` | `exercises/sec-01-boundary-map` |
| SEC-02 | GenAI foundations for Java developers | `checkpoint/sec-02` | — |
| SEC-03 | First Spring AI application | `checkpoint/sec-03` | `exercises/sec-03-chat-endpoint` |
| SEC-04 | Prompt templates, typed replies, fail-closed parsing | `checkpoint/sec-04` | `exercises/sec-04` |
| SEC-05 | Tools and deterministic agent workflows | `checkpoint/sec-05` | `exercises/sec-05` |
| SEC-06 | Domain records, knowledge base, audit trail | `checkpoint/sec-06` | `exercises/sec-06` |
| SEC-07 | Production RAG: ingestion, scoped retrieval, cited answers | `checkpoint/sec-07` | `exercises/sec-07` |
| SEC-08 | Identity with Spring Security: sessions, JWT, delegation | `checkpoint/sec-08` | `exercises/sec-08` |
| SEC-09 | MCP with Java and Spring AI | `checkpoint/sec-09` | `exercises/sec-09` |
| SEC-10 | Secure agent authorization: policy, decisions, grants | `checkpoint/sec-10` | `exercises/sec-10` |
| SEC-11 | Prompt-injection defence | `checkpoint/sec-11` | `exercises/sec-11` |
| SEC-12 | Human approval and safe execution | `checkpoint/sec-12` | `exercises/sec-12` |
| SEC-13 | Testing and evaluation | `checkpoint/sec-13` | `exercises/sec-13` |
| SEC-14 | Observability and production operations | `checkpoint/sec-14` | `exercises/sec-14` |
| SEC-15 | Deployment and final security review | `checkpoint/sec-15` | `exercises/sec-15` |

Each checkpoint contains the completed application at the end of that
section, with its full offline test suite green (`./mvnw test` needs no API
key and no network). From SEC-13 the suite includes the shared test fixtures
(`testing/`), from SEC-14 the observability package, and from SEC-15 the
production profile, deployment descriptors, and dependency lock under
`deploy/`.

— Vivek Singh · [Prompt Vidya AI](https://www.youtube.com/@PromptVidyaAI)
