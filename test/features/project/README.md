# Project acceptance features

Contracts for a project that uses the Kevin runtime from the plugin:

| Feature | Coverage |
|---|---|
| `init.feature` | `kevin init`: `kevin/project.md`, the shared exclude lines, the hook. |
| `config.feature` | Project configuration against runtime defaults; `kevin home`. |
| `project_agents.feature` | `kevin agent`, and the project agents that `kevin adapters` and `kevin init` write. |
| `project_roles.feature` | Roles that a project adds: `kevin check`, routes, gates, `kevin dispatch`, agents. |
| `runtime_version.feature` | `kevin version` and the minimum version in `kevin/project.md`. |
| `subcommands.feature` | `kevin cs`, `fs`, `vb`, `js`, `rb`, `java` and `mistral`. |
| `convert.feature` | `kevin init --convert` on a committed install. |

Run `bash test/acceptance.sh` from the repository root. "A Kevin runtime at
version" is a scratch copy of this repository with that version in its
plugin manifests; its `kevin/bin/kevin` is the `kevin` on PATH. The project
is a separate scratch repository. "A committed Kevin install" is the layout
the removed `install.sh <project>` committed: the runtime under `kevin/`,
`kevin/constitution/articles/project.md` without a
`Minimum Kevin version` line, the configuration, the generated
agents, the swarm skills and command, the `kevin/.gitignore` lines, and
`SWARM` (the old swarm line) in `AGENTS.md` with `@AGENTS.md` in `CLAUDE.md`
and the Copilot line. "The shared exclude file" is the file
`git rev-parse --git-path info/exclude` names. "The project agents" are
the files under `.claude/agents`, `.github/agents` and `.codex/agents` of
the main checkout that hold the `kevin-generated:` line, or `none`. "Shows no project agent" means that
`git status --porcelain` lists none of them. "The description of the
project agent" is the `description` that a YAML parser reads from the
front matter of a Markdown agent, or a TOML parser from a `.toml` agent.
"A claude on PATH that records its arguments" is a script named `claude`
that writes its arguments to a file and exits 0; "the recorded prompt" is
its last argument. "The output is <text> and the absolute path of <path>
in the repository" (or "in the runtime") means that the output, with each
run of white space as one space, is the text and then the absolute path of
that file under the main checkout (or under the runtime). In an error
output line, `<kevin home>` is the runtime's `kevin/` directory, the path
that `kevin home` prints. Other placeholder conventions
follow the [task-store guide](../task_store/README.md).
