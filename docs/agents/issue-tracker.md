# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

`.scratch/` is a local working directory excluded by `.gitignore`, so it is not part of the published repository. The paths below describe where the tracker creates files on a developer's machine; a fresh clone has no `.scratch/` until a skill creates it.

## Two kinds of files, two status systems

This tracker holds two kinds of ticket files. Each uses the `Status:` line for a **different purpose**, so never mix their values.

| File kind | Where | `Status:` means | Allowed values |
| --- | --- | --- | --- |
| **Regular issue** | `.scratch/<feature-slug>/issues/` | **Triage state**: who should act on it, and whether it is ready; finally whether it is done | The triage labels in `triage-labels.md`, plus `done` as the final state |
| **Wayfinding ticket** | `.scratch/<effort>/issues/` under an effort that has a `map.md` | **Work progress**: whether someone has picked it up or answered it | `claimed`, `resolved` (or no `Status:` line while open) |

A directory with a `map.md` is a wayfinding effort; its tickets follow the wayfinding rules below. Any other `issues/` directory holds regular issues.

## Conventions (regular issues)

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file, using one of the triage labels in `triage-labels.md`
- **Completion**: when the work and its verification are both finished, set `Status: done`. `done` is the final state of a regular issue and is not a triage label. Before setting it, tick the acceptance-criteria checkboxes to match the verified results and record the verification under `## Comments`. Regular issues never use `resolved` (wayfinding only).
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

In wayfinding tickets, `Status:` tracks **work progress only**. `claimed` and `resolved` are not triage labels, and triage labels never go on a wayfinding ticket's `Status:` line.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`).
- **Progress**: a `Status:` line records `claimed` or `resolved`. A ticket with no `Status:` line is open.
- **Optional triage**: if a wayfinding ticket also needs a triage state, record it on a separate `Triage:` line, using a label from `triage-labels.md`. This field is optional; omit it when not needed. It never replaces or changes the `Status:` line.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins. Frontier is decided by `Status:` and `Blocked by:` only; a `Triage:` line does not affect it.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
