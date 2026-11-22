# Kevin Constitution

This file is in the Kevin runtime directory that `kevin home` prints. It
takes precedence over article files. Read and obey every file in the
`constitution/articles/` directory next to it:

- `engineering.md`: language defaults, testability, acceptance pipeline, verification.
- `workflow.md`: task worktrees, commits, scratch files, failure conditions.
- `handoffs.md`: how roles pass work through task branches.

Then read and obey `project.md`: the project's own `kevin/project.md` in
the main checkout. It gives the project's language, verification and
ownership boundaries.

Read and obey [the existing-code refactoring policy](constitution/existing-code.md)
only when `project.md` says `Existing code: ask before refactoring`. Otherwise
skip that policy.

Then read `roles/<your role>.md` in the runtime directory.
