# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

## Scope

These labels define the **triage state of regular issues**: who should act next, and whether the issue is ready to be worked on. In this repo's local-markdown tracker, a regular issue records its label on its `Status:` line. When the issue's work and verification are finished, that line becomes `Status: done`, the final state; `done` is not a triage label (see "Completion" in `issue-tracker.md`).

They do **not** describe wayfinding progress. The wayfinding values `claimed` and `resolved` are work-progress states, not triage labels; see the "Wayfinding operations" section in `issue-tracker.md`. A wayfinding ticket may optionally carry one of these labels on a separate `Triage:` line, never on its `Status:` line.

## Label mapping

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the "Label in our tracker" column to match whatever vocabulary you actually use.
