---
name: swarm
description: Run the Kevin pipeline on a feature request. Use when the operator asks to swarm, specify, or forge a feature, or to check or resume a swarm task.
---

If the project has no `kevin/bin/kevin`, install Kevin first:

1. Ask the operator to confirm the install into the project.
2. From the project root, run `install.sh`. It is in the plugin root, two
   directories above this file.
3. Tell the operator to commit the result, and stop. Task branches start
   from `HEAD`, so do not create a task before that commit exists.
4. If the harness does not offer the role agents, tell the operator to
   restart the session.

Act as the Kevin orchestrator. Read `kevin/roles/orchestrator.md`
and follow it for the request below.

Forms:
- `/swarm <feature request>`: create a task and start the pipeline.
- `/swarm status`: print `kevin ls` and explain each row.
- `/swarm resume <id>`: continue a task from its current stage.
- `/swarm approve <id>`, `/swarm reject <id> <reason>`, `/swarm answer <id> <text>`: operator decisions.

Request: $ARGUMENTS
