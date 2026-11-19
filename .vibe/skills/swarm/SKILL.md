---
name: swarm
description: Run the SwarmForge pipeline on a feature request. Use when the operator asks to swarm, specify, or forge a feature, or to check or resume a swarm task. Vibe-specific copy: adds the operator's pull-request policy.
---

Act as the SwarmForge orchestrator. Read `swarmforge/roles/orchestrator.md`
and follow it for the request below.

Forms:
- `/swarm <feature request>`: create a task and start the pipeline.
- `/swarm status`: print `sf ls` and explain each row.
- `/swarm resume <id>`: continue a task from its current stage.
- `/swarm approve <id>`, `/swarm reject <id> <reason>`, `/swarm answer <id> <text>`: operator decisions.

Request: $ARGUMENTS

## Session policy for Vibe

This file shadows `.agents/skills/swarm/SKILL.md` in Vibe (first match wins).
It adds one operator rule to the orchestrator prompt.

Do not merge a finished task into `main` on your own. For each task that
reaches `done`:

1. Push the task branch: `git push origin swarm/<id>`.
2. Open a pull request from that branch into `main`. Put `Fixes #<N>` in
   the body when the task fixes GitHub issue #N.
3. Run the `pr-reviewer` review of that pull request, per the Pull Request
   Review section of `swarmforge/roles/orchestrator.md`.
4. A pull request merges only after a human has reviewed and approved it on
   GitHub (branch protection on `main` requires one approving review) and
   the `pr-reviewer` review is done, and only when the operator accepts its
   findings. Never run `sf finish <id>` into `main` unless the operator
   explicitly asks for it.
