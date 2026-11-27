# Rebrand compatibility features

These features cover what still works under the old SwarmForge names after
the rename to Kevin: task records with `SwarmForge-*` trailers, the commit-msg
hook, `SWARMFORGE_*` variables and the `swarmforge/metrics` branch.

Run `bash test/acceptance.sh` from the repository root. A "legacy" fixture is
made with git and plain files, as an old `sf` wrote them, not by running an
old `sf`. Placeholder conventions follow the
[task-store guide](../task_store/README.md).
