# Onboarding output template

Use only the sections that improve the requested artifact.

## Index

- Purpose and intended audience
- Page list or section links
- Analysis date and scope
- Safety limitations: skipped sensitive files, symlinks, unavailable history, or truncated scan

## Content page

### TL;DR

Summarize the subsystem in two or three evidence-backed sentences.

### Relevant source files

| File | Role | Evidence |
|---|---|---|
| `path/to/file` | Concise responsibility | `path/to/file:L1-L20` |

### Architecture or flow

Use prose for a simple relationship. Use Mermaid only when at least three components interact.

### Key concepts

| Concept | Meaning | Source |
|---|---|---|
| Name | Verified explanation | `path/to/file:L10-L25` |

### Detailed walkthrough

Trace one representative path through real entry points and downstream code. Put citations immediately after their claims.

### Development and verification

List commands documented by the repository, but label them **not executed** unless the user separately authorized execution. State prerequisites and external services.

### First-change map

Map common developer intentions to the best starting files.

### Unknowns and risks

Separate unverified architecture, missing evidence, security-sensitive areas, and scan limitations.

### Cross-references

Link only to pages that exist.
