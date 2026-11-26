# Kevin operator

A harness-integrated Kevin swarm.
Run its roles in Kilo, Mistral Vibe, Claude Code, Codex CLI, or Copilot CLI. Roles pass work
through Git task branches; the operator handles questions and approvals in chat.

## Prerequisites

- `git`, and `bash` for `install.sh`, the commit-msg hook, and the tool wrappers
- [Babashka](https://babashka.org) (`bb`) for `kevin`, which needs 1.12.216 or newer
  and refuses an older `bb`, and for the C#, F#, VB.NET, JavaScript, Ruby,
  Java and C/C++ tool wrappers and the Clojure constitution tools. `install.sh` checks `bb`
  before it writes anything.
- One of Claude Code, Copilot CLI, Codex CLI, Kilo, or Mistral Vibe

## Install

Kevin is a harness plugin. The plugin directory is the Kevin runtime: the
`kevin` command, the role prompts, the constitution, the language guides and
the tool sources. A project commits only its configuration.

1. Install the plugin.

   Claude Code:

   ```text
   /plugin marketplace add brainfucknow/Kevin-operator
   /plugin install kevin@kevin-operator
   ```

   Codex CLI:

   ```sh
   codex plugin marketplace add brainfucknow/Kevin-operator
   codex plugin add kevin@kevin-operator
   ```

   Copilot CLI:

   ```sh
   copilot plugin install brainfucknow/Kevin-operator
   ```

   Mistral Vibe: copy or clone this repository into a plugins directory, so
   the plugin root is this repository's root:

   ```sh
   git clone https://github.com/brainfucknow/Kevin-operator ~/.vibe/plugins/kevin
   # or one trusted project: git clone ... .vibe/plugins/kevin
   ```

2. Put `kevin` on PATH once per user. Run `install.sh --user` in the
   plugin directory, or in a clone of this repository. It links
   `~/.local/bin/kevin` to the runtime's `kevin/bin/kevin` and writes nothing
   else. Run it again after a plugin update to move the link. The `swarm`
   skill offers this step when `kevin` is missing.

3. Set up each project: run `kevin init` in it. It writes `kevin/project.md`,
   adds `.worktrees/`, `.kevin/`, `tmp/` and `.metrics/` to
   `.git/info/exclude`, and installs the commit-msg hook. Commit
   `kevin/project.md`, then set the project language there.

| Harness | Plugin manifest | Marketplace |
|---|---|---|
| Claude Code | `.claude-plugin/plugin.json` | `.claude-plugin/marketplace.json` |
| Codex CLI | `.codex-plugin/plugin.json` | `.agents/plugins/marketplace.json` |
| Copilot CLI | `.github/plugin/plugin.json` | `.github/plugin/marketplace.json` |
| Mistral Vibe | `plugin.json` (Agent Plugins 1.0, at the plugin root) | `~/.vibe/plugins/kevin` or `./.vibe/plugins/kevin` |

All four load `skills/swarm/SKILL.md` and `ai.mistral.vibe/agents` is the Vibe
extension. Start with `/kevin:swarm <feature request>` in Claude Code or
Mistral Vibe, `$swarm` in Codex, or `/swarm` in Copilot. In Vibe run
`/reload-plugins` after installing or updating the plugin.

### Project files

| File | Required | Purpose |
|---|---|---|
| `kevin/project.md` | yes | The project article: language, verification, ownership, and `Minimum Kevin version:` |
| `kevin/roles.conf`, `routes.conf`, `tiers.conf`, `tools.conf` | no | Override the runtime's copy of the same file |
| `kevin/roles/<role>.md` | no | The prompt of a role that the project adds in its `roles.conf` |
| `kevin/verify.conf` | no | The commands `kevin finish` runs; without it, none run |

`kevin version` prints the runtime version, and a runtime older than the
project's `Minimum Kevin version:` is refused. The [runtime
guide](kevin/README.md) lists the commands, including `kevin cs`, `kevin fs`,
`kevin vb`, `kevin js`, `kevin rb`, `kevin java`, `kevin cpp` and `kevin mistral`.

### A project with a committed install

An older Kevin copied the runtime into the project with `install.sh
<project>`. That install is gone. Run `kevin init --convert` in such a
project with a clean working tree. It moves
`kevin/constitution/articles/project.md` to `kevin/project.md`, removes the
committed runtime, Kevin's generated agents, the swarm skills and the Kevin
instruction lines, rewrites `kevin/bin/kevin-<name>` in the kept
configuration to `kevin <name>`, then does what `kevin init` does. It stages
the change and does not commit. Review the diff and commit it. Tasks begun
before the conversion still finish.

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
3. The task branch goes to the base branch through a pull request. After
   it merges with a merge commit and the main checkout pulls it,
   `kevin finish` verifies HEAD and removes the task branch and worktree.
   It never commits on a protected branch: main, master, develop, release
   or the default branch.

`kevin ls` shows open tasks and what they need from you; `kevin show <id>` shows
history. See the [runtime guide](kevin/README.md) for commands and the
[handoff protocol](kevin/handoff-protocol.md) for approval, recovery,
and sharing rules.

## Harness integration

| Harness | Session entry | Subagent roles |
|---|---|---|
| Claude Code | `/kevin:swarm` | plugin agents `kevin:<role>` from `agents/<role>.md` |
| Copilot CLI | `/swarm` | plugin agents from `.github/plugin/agents/<role>.agent.md` |
| Codex CLI | `$swarm` | project agents only; other roles run through `kevin dispatch` |
| Mistral Vibe with the plugin | ask the session to follow the orchestrator in `kevin home` | plugin agents `kevin:<role>` from `ai.mistral.vibe/agents/<role>.toml` for runtime roles; added roles run through `kevin dispatch` |
| Kilo, Mistral Vibe without the plugin | ask the session to follow the orchestrator in `kevin home` | none; roles run through `kevin dispatch` |

`kevin adapters`, run in this source checkout, generates the plugin agents
from `kevin/roles.conf` and `kevin/tiers.conf`. Each agent reads the
constitution and its role prompt in the directory `kevin home` prints.

A project changes its role agents through its own `roles.conf` and
`tiers.conf`. `kevin agent <role> <harness>` tells the orchestrator which
agent runs the role. When only a Claude Code model changes to `sonnet`,
`opus`, `haiku` or `fable`, the orchestrator passes it on the delegation
call. Any other change, and every role the project adds, needs a project
agent: `.claude/agents/kevin-<role>.md`, `.github/agents/<role>.agent.md`
or `.codex/agents/<role>.toml`. Mistral Vibe has no project agents: it
always uses the plugin agent of a runtime role, and runs an added role
through `kevin dispatch`. `kevin init` and `kevin adapters` write
these files, list them in `.git/info/exclude`, and remove those no longer
needed. Run `kevin adapters` again in each clone and after each change to
the configuration. Such a project sets `Minimum Kevin version: 0.3.0`.

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
[`kevin/tiers.conf`](kevin/tiers.conf). Run `kevin adapters` after changing
roles or tiers: in this source checkout it also regenerates the plugin
agents.

## Reference

- [Runtime guide](kevin/README.md): commands and configuration.
- [Handoff protocol](kevin/handoff-protocol.md): task branches and records.
- Language tools: [Clojure](kevin/languages/clojure.md),
  [C#](kevin/languages/csharp.md), [F#](kevin/languages/fsharp.md),
  [VB.NET](kevin/languages/vbnet.md),
  [JavaScript/TypeScript](kevin/languages/javascript.md),
  [Ruby](kevin/languages/ruby.md), [Java](kevin/languages/java.md),
  [C/C++](kevin/languages/cpp.md).
- [Semgrep](kevin/languages/semgrep.md): shared setup for the language guides.

## Tests

Run these from the source checkout.

Choose checks for the affected behavior and dependencies. Reuse passing
evidence for the same relevant revision and targets; the full CI matrix is
not a checklist for every handoff. Required source-quality gates still apply
to the affected targets, and property checks remain separate and role-owned.

| Changed area | Focused checks |
|---|---|
| Shared wrapper dependency or IO boundary | `bb --config kevin/tools/core/bb.edn architecture` (also retained in the JavaScript unit suite). |
| Shared wrapper behavior | `bb --config kevin/tools/core/bb.edn test`, then affected language-wrapper unit suites. |
| .NET wrapper | `bb --config kevin/tools/csharp/bb.edn test`, plus the shared architecture check; use combined coverage below when coverage evidence needs refreshing. |
| Task state or CLI behavior | Affected kevin specs and public-CLI checks in `test/kevin_test.sh` or named `test/features/task_store/` features. |
| Installer and project setup | `test/features/install/` and `test/features/project/` features, and the init and convert specs. |
| Tool installation or argument rewriting | Relevant kevin specs, `test/kevin_tool_test.sh`, and affected tool compatibility checks. |
| Acceptance fixtures | `bash test/acceptance.sh <feature>...`; for identity changes include `test/features/task_store/init.feature`. |
| Documentation only | Manual review and existing configuration/link checks relevant to the edit. |

The commands below list the available suites. CI remains the broader
integration check, including unaffected language suites.

```sh
bash test/kevin_test.sh
bash test/kevin_tool_test.sh
bash test/no_manifests_test.sh
bb --config kevin/tools/core/bb.edn architecture
bb --config kevin/tools/core/bb.edn test
bb --config kevin/tools/kevin/bb.edn spec
bb --config kevin/tools/kevin/bb.edn hardening
bb --config kevin/tools/kevin/bb.edn property
kevin/bin/kevin tool ensure gherkin-parser
bash test/acceptance.sh
bb --config kevin/tools/csharp/bb.edn test
bb --config kevin/tools/csharp/bb.edn property
bb --config kevin/tools/javascript/bb.edn test
bb --config kevin/tools/javascript/bb.edn property
bb --config kevin/tools/javascript/bb.edn acceptance
bb --config kevin/tools/ruby/bb.edn test
bb --config kevin/tools/ruby/bb.edn property
bash test/kevin_rb_e2e_test.sh  # needs Ruby and Bundler
bb --config kevin/tools/cpp/bb.edn test
bash test/kevin_cpp_e2e_test.sh  # needs cmake 3.21+, a C++ compiler, uv and Node
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
