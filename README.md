# NextQuest

*(working name — the product will be renamed before going public)*

A local-first Android quest log that keeps exactly one next step in view per
quest, plus an optional self-hostable sync + MCP server so Claude or ChatGPT can
read your quests and **propose** changes that you approve in the app.

## Principles

These decide every judgment call:

1. **One thing visible.** Each tracked quest shows only its single next step.
2. **Capture in two seconds from anywhere**; capture never forces sorting.
3. **Hard cap of 5 tracked quests** (configurable 3–7, default 5).
4. **Every tracked quest always has a next step.** The only exception is a step
   completed from a notification (or the widget); that quest shows a
   "Needs next step" flag until you set one.
5. **AI proposes, you ratify.** No AI or server action ever changes your quests
   directly. It creates proposals; you accept, edit, or reject them.
6. **Re-entry without guilt.** No overdue walls of red. After 2+ days away, a
   resume card helps you pick up again.
7. **Local-first.** Fully functional offline with no account. Sync is optional.

> v1 is under construction on the `v1` branch. The full README (principles,
> self-hosting, connecting Claude and ChatGPT, roadmap) lands with phase P7.

License: [AGPL-3.0-or-later](LICENSE). Contributions require the [CLA](CLA.md).
