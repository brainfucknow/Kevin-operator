# Kevin operator

A harness-integrated Kevin swarm.
Run its roles in Kilo, Mistral Vibe, Claude Code, Codex CLI, or Copilot CLI. Roles pass work
through Git task branches; the operator handles questions and approvals in chat.

## Prerequisites

- `git`, and `bash` for `install.sh`, the commit-msg hook, and the tool wrappers
- [Babashka](https://babashka.org) (`bb`) for `kevin`, which needs 1.12.216 or newer
  and refuses an older `bb`, and for the C#, F#, VB.NET, JavaScript, Ruby and
  Java tool wrappers and the Clojure constitution tools. `install.sh` checks `bb` before
  it changes the project.
- One of Kilo, Mistral Vibe, Claude Code, Codex CLI, or Copilot CLI

## Install into a project

```sh
git clone https://github.com/brainfucknow/Kevin-operator
cd your-project
../Kevin-operator/install.sh
```

The installer copies the Kevin runtime, guidance, and `swarm` skill into
the project, adds instructions to `AGENTS.md`, `CLAUDE.md`, and
`.github/copilot-instructions.md`, generates the agents, and installs the
commit-msg hook. It also writes `kevin/.gitignore` so that a project
rule ignoring every `bin/` directory, such as Visual Studio's `[Bb]in/`, does
not drop `kevin/bin`. Commit the result.
Development assets stay in this source checkout.

Then set the project language in `kevin/constitution/articles/project.md`.

To update, rerun this source checkout's `install.sh`. Runtime, guidance and
skills are refreshed. The project owns `kevin/constitution/articles/project.md`
and the shipped top-level `kevin/*.conf` files: existing files are kept,
and only missing files receive defaults. `verify.conf` is never installed or
changed. This also applies to older installs without ownership metadata.

An install under the old name SwarmForge keeps the harness in `swarmforge/`.
The installer moves it to `kevin/`, rewrites the old names (`swarmforge/`
paths, `sf-*` command names, `SwarmForge-*` trailers, `SWARMFORGE_*`
variables) in the project's own files there, prints each file it rewrote, and replaces
the old `AGENTS.md` line. It refuses a project that has both directories.
Review the diff and commit it. Tasks begun before the move keep working.

A runtime update can require configuration changes. Compare those files with
the defaults in the source checkout, merge the changes your project needs,
then run `kevin/bin/kevin adapters` to regenerate agents. Invalid preserved
settings fail validation instead of being reset. The installer lists unshipped
payload files for manual review; they may be user files, so it never deletes
them or assumes they were retired upstream.

## Run

Open the project in your harness and ask:

```text
/swarm Users can log in with email and password; lock the account after five failures.
```

For the default `feature` route, the session orchestrates:

1. The specifier commits criteria and independent QA, then stops for your
   approval. You can request changes or adjust the route before approving.
2. Roles implement, review, and verify on the task branch, committing and
   handing off in sequence. Questions stop the task until you answer in chat.
3. `kevin finish` verifies the completed merge, merges into the base branch,
   and removes the task branch and worktree.

`kevin ls` shows open tasks and what they need from you; `kevin show <id>` shows
history. See the [installed guide](kevin/README.md) for commands and the
[handoff protocol](kevin/handoff-protocol.md) for approval, recovery,
and sharing rules.

## Harness integration

| Harness | Session entry | Subagent roles | Model tier |
|---|---|---|---|
| Kilo | `/swarm` from `.kilo/command/` | `.kilo/agent/<role>.md` | `model:` `provider/model`, or the session model when `inherit` |
| Mistral Vibe | `/swarm` from `.vibe/skills/swarm/` | `.vibe/agents/<role>.toml` | `active_model` alias, or the session model when `inherit` |
| Claude Code | `/swarm` from `.claude/skills/swarm/` | `.claude/agents/<role>.md` | `model:` `inherit`, `opus`, or `sonnet` |
| Codex CLI | `$swarm` from `.agents/skills/swarm/` | `.codex/agents/<role>.toml` | `model_reasoning_effort` high / medium / low |
| Copilot CLI | `/swarm` from `.agents/skills/swarm/` | `.github/agents/<role>.agent.md` | `model: gpt-6.1-sol` or `gpt-6.1-terra` |

`kevin adapters` generates agent files from `kevin/roles.conf` and
`kevin/tiers.conf`. Each agent reads the shared constitution and its role
prompt under `kevin/roles/`. Vibe loads `./.vibe/agents/` subagents and
the `./.vibe/skills/` skill only when the project folder is trusted, and it
spawns the role subagents itself; its subagents cannot use MCP tools, which the
roles do not need.

Codex: custom agent roles need the multi-agent feature. If `codex` does not
offer the roles, enable it in `~/.codex/config.toml` per the Codex subagents
documentation, or fall back to `kevin run <id>` below.

If a harness cannot delegate, `kevin dispatch <role> <id>` runs the role in a
headless `claude -p`, `codex exec`, or `copilot -p` session started in the
task worktree `.worktrees/<id>` (created when missing, refused when it is on
another branch). With none of these CLIs on `PATH` and `MISTRAL_API_KEY` set,
it runs the role on [Mistral Workflows](kevin/workflows/README.md).
`KEVIN_BACKEND` picks the backend explicitly. and `kevin run <id>` loops through the stages until the task
is done or waiting on you. Headless runs need tool permissions you grant
yourself; pass them in `KEVIN_CLAUDE_ARGS`, `KEVIN_CODEX_ARGS`, or
`KEVIN_COPILOT_ARGS`. For ordinary stages, Claude uses
`--permission-mode acceptEdits` and Codex uses `--sandbox workspace-write`.
Codex gates use `--sandbox read-only`. Set Copilot tool permissions through its
extra arguments, for example
`KEVIN_COPILOT_ARGS="--allow-all-tools"`.

## Roles, routes, and model tiers

Each task follows a named route from `kevin/routes.conf`, or an explicit
subset of the catalogue in `kevin/roles.conf` order:

| Route | Roles |
|---|---|
| `feature` (default) | specifier, coder, cleaner, architect, hardener, security-reviewer, qa |
| `bugfix` | specifier, coder, cleaner, qa |
| `refactor` | cleaner, architect, hardener |
| `harden` | hardener, qa |
| `spike` | coder, qa: one technical question, answered with throwaway code that is never merged; title starts with `Spike:` |
| `spec-code-qa` | specifier, coder, qa |
| `code-qa` | coder, qa |

The orchestrator suggests `spike`, `spec-code-qa` and `code-qa`; you choose.

The [role catalogue](kevin/roles.conf) assigns tiers and supports
`subagent`, `session`, `audit`, `optional`, and `send-back` settings. See
[configuration](kevin/README.md#configuration) for these settings and
the optional reviewers. Model mappings live in
[`kevin/tiers.conf`](kevin/tiers.conf); regenerate agents with
`kevin adapters` after changing roles, routes, or tiers.

## Reference

- [Installed guide](kevin/README.md): commands and configuration.
- [Handoff protocol](kevin/handoff-protocol.md): task branches and records.
- Language tools: [Clojure](kevin/languages/clojure.md),
  [C#](kevin/languages/csharp.md), [F#](kevin/languages/fsharp.md),
  [VB.NET](kevin/languages/vbnet.md),
  [JavaScript/TypeScript](kevin/languages/javascript.md),
  [Ruby](kevin/languages/ruby.md), [Java](kevin/languages/java.md).
- [Semgrep](kevin/languages/semgrep.md): shared setup for the language guides.

## Tests

Run these from the source checkout; fresh consumer installs omit harness tests and fixtures.

Choose checks for the affected behavior and dependencies. Reuse passing
evidence for the same relevant revision and targets; the full CI matrix is
not a checklist for every handoff. Required source-quality gates still apply
to the affected targets, and property checks remain separate and role-owned.

| Changed area | Focused checks |
|---|---|
| Shared wrapper dependency or IO boundary | `bb --config kevin/tools/core/bb.edn architecture` (also retained in the JavaScript unit suite). |
| Shared wrapper behavior | `bb --config kevin/tools/core/bb.edn test`, then affected language-wrapper unit suites. |
| .NET wrapper | `bb --config kevin/tools/csharp/bb.edn test`, plus the shared architecture check; use combined coverage below when coverage evidence needs refreshing. |
| Task state or CLI behavior | Affected kevin specs and public-CLI checks in `test/sf_test.sh` or named `test/features/task_store/` features. |
| Installer | Installer specs, named `test/features/install/` features and `test/features/adapters/` preservation/collision checks. |
| Tool installation or argument rewriting | Relevant kevin specs, `test/sf_tool_test.sh`, and affected tool compatibility checks. |
| Acceptance fixtures | `bash test/acceptance.sh <feature>...`; for identity changes include `test/features/task_store/init.feature`. |
| Documentation only | Manual review and existing configuration/link checks relevant to the edit. |

The commands below list the available suites. CI remains the broader
integration check, including unaffected language suites.

```sh
bash test/sf_test.sh
bash test/sf_tool_test.sh
bash test/no_manifests_test.sh
bb --config kevin/tools/core/bb.edn architecture
bb --config kevin/tools/core/bb.edn test
bb --config kevin/tools/sf/bb.edn spec
bb --config kevin/tools/sf/bb.edn hardening
bb --config kevin/tools/sf/bb.edn property
kevin/bin/kevin tool ensure gherkin-parser
bash test/acceptance.sh
bb --config kevin/tools/csharp/bb.edn test
bb --config kevin/tools/csharp/bb.edn property
bb --config kevin/tools/javascript/bb.edn test
bb --config kevin/tools/javascript/bb.edn property
bb --config kevin/tools/javascript/bb.edn acceptance
bb --config kevin/tools/ruby/bb.edn test
bb --config kevin/tools/ruby/bb.edn property
bash test/sf_rb_e2e_test.sh  # needs Ruby and Bundler
bb --config kevin/tools/java/bb.edn spec
bb --config kevin/tools/java/bb.edn hardening
bb --config kevin/tools/java/bb.edn acceptance
bb --config kevin/tools/java/bb.edn property
(cd kevin/workflows && uv run --frozen pytest)
```

These suites exercise the CLI with scratch Git repositories and test the
language wrappers against fixture reports and fake external tools.

The .NET module has both Speclj and `clojure.test` tests. Its maintained
`test/combined-coverage.clj` adapter registers both runners with genuine
Cloverage; it computes no coverage or quality metrics. From this source
checkout, run both populations under the pinned tool's coverage configuration:

```sh
kevin/bin/kevin tool ensure crap4clj
export PATH="$(kevin/bin/kevin tool bin):$PATH"
source_root="$PWD"
coverage_config="$(kevin/bin/kevin tool bin)/../tools/crap4clj-coverage/bb.edn"
cd kevin/tools/csharp
bb --config "$coverage_config" "$source_root/test/combined-coverage.clj" \
  -r combined -p src -s test -s spec -s ../core/test --lcov
crap4clj --use-existing-coverage
```

Confirm both frameworks
report nonzero passing tests and the intended source namespaces appear in
LCOV before citing the result. Either framework's failure fails the combined
run. The explicit test roots omit property tests; do not add property paths
to ordinary coverage. A fixture-backed wrapper suite does not establish
compatibility with a real external tool.
