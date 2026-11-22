# Project acceptance features

Contracts for a project that uses the Kevin runtime from the plugin:

| Feature | Coverage |
|---|---|
| `init.feature` | `kevin init`: `kevin/project.md`, the shared exclude lines, the hook. |
| `config.feature` | Project configuration against runtime defaults; `kevin home`. |
| `runtime_version.feature` | `kevin version` and the minimum version in `kevin/project.md`. |
| `subcommands.feature` | `kevin cs`, `fs`, `vb`, `js`, `rb`, `java` and `mistral`. |
| `convert.feature` | `kevin init --convert` on a committed install. |

Run `bash test/acceptance.sh` from the repository root. "A Kevin runtime at
version" is a scratch copy of this repository with that version in its
plugin manifests; its `kevin/bin/kevin` is the `kevin` on PATH. The project
is a separate scratch repository. "A committed Kevin install" is the layout
the removed `install.sh <project>` committed: the runtime under `kevin/`,
`kevin/constitution/articles/project.md`, the configuration, the generated
agents, the swarm skills and command, the `kevin/.gitignore` lines, and
`SWARM` (the old swarm line) in `AGENTS.md` with `@AGENTS.md` in `CLAUDE.md`
and the Copilot line. "The shared exclude file" is the file
`git rev-parse --git-path info/exclude` names. Placeholder conventions
follow the [task-store guide](../task_store/README.md).
