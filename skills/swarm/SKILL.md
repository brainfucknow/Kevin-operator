---
name: swarm
description: Run the Kevin pipeline on a feature request. Use when the operator asks to swarm, specify, or forge a feature, or to check or resume a swarm task.
---

The plugin root is two directories above this file. Before the request:

1. Run `kevin home`. When `kevin` is not on PATH, or it prints another
   directory than `kevin/` in the plugin root, ask the operator to confirm
   the user-wide install. Then run `install.sh --user` from the plugin
   root. When it prints `add <dir> to PATH`, put that directory on PATH
   for this session and tell the operator to add it for good.
2. When the project index holds `kevin/bin/kevin` and
   `kevin/constitution/articles/project.md`, it has an old committed
   install. Offer `kevin init --convert`. Run it only when the operator
   agrees. Then tell the operator to commit the staged change, and stop.
3. When the project has no `kevin/project.md`, run `kevin init`. Then tell
   the operator to commit `kevin/project.md`, and stop. Task branches start
   from `HEAD`, so do not create a task before that commit exists.
4. Before creating a task, require `kevin/project.md` in `HEAD` with
   `git cat-file -e HEAD:kevin/project.md`, and require
   `git status --porcelain -- kevin/` to be empty. If either check fails,
   tell the operator to commit the project setup and stop.

Then act as the Kevin orchestrator. Read `<home>/roles/orchestrator.md`,
where `<home>` is the directory `kevin home` prints, and follow it for the
request below.

Forms:
- `/swarm <feature request>`: create a task and start the pipeline.
- `/swarm status`: print `kevin ls` and explain each row.
- `/swarm resume <id>`: continue a task from its current stage.
- `/swarm approve <id>`, `/swarm reject <id> <reason>`, `/swarm answer <id> <text>`: operator decisions.

Request: $ARGUMENTS
