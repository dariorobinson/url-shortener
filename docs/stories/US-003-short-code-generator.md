---
id: US-003
title: Short-code generator
status: Open
plan_task: 3
depends_on: [US-001]
requirements: [FR-10, D6, D29]
requires_design_approval: false
---

# US-003: Short-code generator

## User story
As an engineer, I want a configurable, cryptographically random Base62 short-code generator behind an interface, so that the create-URL flow (US-006) can request candidate codes without depending on a concrete implementation.

## Acceptance criteria
- **AC1:** Given `ShortCodeGenerator` is called with default configuration, when `generate()` is invoked, then it returns a string of length 7 matching `^[A-Za-z0-9]{7}$` (D6 charset).
- **AC2:** Given `shortener.code.length` is configured to a value between 3 and 32 inclusive, when `generate()` is invoked, then the returned code has exactly that length.
- **AC3:** Given `shortener.code.length` is configured outside `[3, 32]` (consistent with the `ck_short_url_code_format` bound in US-002), when the application starts, then startup fails with a validation error rather than silently generating codes the database will reject.
- **AC4:** Given `shortener.code.max-attempts` is configured to a value `< 1`, when the application starts, then startup fails with a validation error.
- **AC5:** Given the default implementation uses `java.security.SecureRandom`, when 10,000 codes are generated, then all match the charset/length pattern and no two are identical (statistical uniqueness check, not a correctness guarantee — the DB unique constraint is the guarantee per US-002).

## Tests required
| Type | Behaviour proven | Owner |
|---|---|---|
| Unit | Generated codes match charset and configured length (AC1, AC2) | mid-engineer |
| Unit | Config validation rejects out-of-bounds length and max-attempts at startup (AC3, AC4) | mid-engineer |
| Unit | Statistical sampling shows no collisions across a large batch and full charset coverage over many samples (AC5) | mid-engineer |

## Out of scope
- The retry-on-collision loop and per-attempt transaction boundary — that is service-layer logic in US-006.
- Custom alias handling — see US-004.
- Treating a generated code that matches a reserved word (D29) as a collision and retrying — the generator itself stays a pure function with no knowledge of reserved words; the retry-on-reserved-word-match behaviour is service-layer logic in US-006, alongside the unique-constraint retry loop.

## Risks
- None significant; this is a self-contained, side-effect-free component.

## Open questions
- None.

## Design note
*(architect)*

## Implementation notes
*(mid-engineer: files changed, decisions, items needing human review, test command and result)*

## QA notes
*(qa-tester: feature files and IT classes, AC-to-test mapping, test results, defects D-<story>-<n>)*

## Review log
*(senior-engineer findings per round, and how each was resolved)*

| Round | ID | Severity | Finding | Resolution |
|---|---|---|---|---|
