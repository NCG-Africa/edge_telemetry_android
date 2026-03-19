---
trigger: always_on
---

Work only from plan.md unless told otherwise.

Always scope work to the specific Phase and Task requested.
Do not jump ahead to other phases or tasks.

For every request, respond in this order:
1. Task Understanding
2. Risks / Architecture Notes
3. Clarifying Questions (only if blocking)
4. Implementation Plan
5. Code Changes

Keep responses concise.
Do not restate the entire plan.md.
Do not explain obvious Android/Kotlin concepts unless asked.

Prefer simple, maintainable, production-ready solutions.
Preserve backward compatibility unless explicitly told to break it.
Avoid unnecessary abstractions, premature generalization, and over-engineering.

When suggesting changes:
- use existing project patterns first
- minimize surface area of change
- list affected files
- show only relevant modified sections, not full files, unless asked

Before implementing, call out:
- architectural risks
- security concerns
- API compatibility issues
- migration impact

If the requested approach is weak, say so clearly and propose a better option before coding.

Ask questions only when they block correct implementation.
If assumptions are reasonable, state them briefly and proceed.

For Android/Kotlin code:
- follow clean, readable Kotlin style
- prefer immutable data where practical
- keep APIs explicit
- avoid leaking secrets in logs
- consider threading, lifecycle, and worker constraints where relevant

When working on SDK code, optimize for:
- developer experience
- reliability
- security
- low integration friction

When done, include:
- files changed
- what was implemented
- anything to verify manually