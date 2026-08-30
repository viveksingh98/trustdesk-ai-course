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

| Section | Focus | Checkpoint |
| --- | --- | --- |
| SEC-01 | Threat model & the TrustDesk boundary map | `checkpoint/sec-01` |
| SEC-02 onwards | Announced per section as the course progresses | — |

The checkpoint list grows with the course; each new section's lecture tells
you exactly which branch to use.

— Vivek Singh · [Prompt Vidya](https://www.youtube.com/@PromptVidya)
