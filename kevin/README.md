# Kevin

This directory is the Kevin runtime; `kevin home` prints its path. It runs
AI roles as harness agents in Claude Code or Copilot CLI, and as headless
runs from Codex, Kilo or Mistral Vibe sessions. Each task takes a
route through the role catalogue, and roles pass work through task branches in
this repository.

| File | Purpose |
|---|---|
| `roles.conf` | The role catalogue in canonical order, each with a model tier. All run as subagents unless marked `session`. |
| `routes.conf` | Named routes: which roles a task visits. `feature` is the default. |
| `tiers.conf` | Model per tier per harness. |
| `tools.conf` | The constitution tools and the commits they are pinned at. |
| `constitution.md`, `constitution/articles/` | Rules every role obeys. |
| `constitution/existing-code.md` | Refactoring policy read only when the project opts into `Existing code: ask before refactoring`. |
| `roles/*.md` | What each role owns, verifies, and hands off. `orchestrator.md` is the session's own role. |
| `bin/kevin`, `tools/kevin/` | The only writer of task state. `kevin --help` lists the commands. Only `kevin` goes on PATH. Language and backend commands run the other `bin/kevin-*` files. |
| `bin/kevin-mistral`, `workflows/` | The mistral backend: runs tasks and roles on Mistral Workflows when no backend CLI is on PATH. See [workflows/README.md](workflows/README.md). |
| `bin/kevin-cs`, `languages/csharp.md` | C# tool wrapper: tests, coverage, CRAP, DRY, mutation, each scoped to the task's changes. |
| `bin/kevin-fs`, `languages/fsharp.md` | F# .NET tests, coverage, compiled-method CRAP and DRY; mutation unsupported. |
| `bin/kevin-vb`, `languages/vbnet.md` | VB.NET tests, coverage, compiled-method CRAP and DRY; mutation unsupported. |
| `languages/clojure.md` | How to run clj-mutate, crap4clj and dry4clj on Babashka code. |
| `bin/kevin-js`, `languages/javascript.md` | JavaScript and TypeScript tool wrapper: the same commands for Vitest or Jest, oxlint or ESLint complexity with Istanbul coverage, jscpd, and StrykerJS. |
| `bin/kevin-rb`, `languages/ruby.md` | Ruby tool wrapper: SimpleCov coverage, CRAP from RuboCop or flog, DRY from jscpd or flay, and optional mutant. |
| `bin/kevin-cpp`, `languages/cpp.md` | C and C++ tool wrapper: CMake or MSBuild tests, gcov or OpenCppCoverage coverage, CRAP from lizard, DRY from jscpd, and Mull mutation. |
| `bin/kevin-java`, `languages/java.md` | Maven Java 17+ / JUnit 5 tests, JaCoCo coverage, pinned Java CRAP, DRY and differential mutation. |
| `bin/kevin-mojo`, [Mojo guide](languages/mojo.md) | Mojo TestSuite tests, Linux line coverage with kcov, and DRY with jscpd. |
| `bin/kevin-rust`, [Rust tools](languages/rust.md) | Cargo tests, property tests, coverage, CRAP, DRY, and differential mutation. |
| `languages/semgrep.md` | Shared scanner setup used by language guides, which define the scan scope and required/optional status. |
| `tools/core/` | Report readers shared by the language tool wrappers: changed lines, CRAP, jscpd, Stryker. |
| `handoff-protocol.md` | The task branch, record commits, and lifecycle. |
| `project.md` | This repository's own project article, not a default for other projects. |

The [handoff protocol](handoff-protocol.md) covers task state, approval, merge
recovery, and sharing the mutation cache on `kevin/metrics`.

Prerequisites: `git`, `bash`, and [Babashka](https://babashka.org) (`bb`). `kevin`
needs `bb` 1.12.216 or newer and refuses an older one.
Run `install.sh --user` in the plugin directory once per user, then
`kevin init` once per clone of a project.

A project keeps its configuration in its own `kevin/`:

| File | Purpose |
|---|---|
| `project.md` | Required. The project article that the constitution reads, and `Minimum Kevin version: <major>.<minor>.<patch>`. `kevin init` writes a starter. Every command but `help`, `version` and `home` refuses a runtime older than that version. |
| `roles.conf`, `routes.conf`, `tiers.conf`, `tools.conf` | Optional. Each replaces the runtime's file of the same name. A project `roles.conf` may drop roles, change their tiers and flags, and add roles. |
| `roles/<role>.md` | The prompt of each role that the project's `roles.conf` adds: a title line, then a summary line. An added role is a stage or a gate, not a spec-judge. The Mistral Workflow backend also reads a file here for a runtime role, in place of the runtime's prompt. |
| `verify.conf` | Optional: one shell command per line that `kevin finish` runs on the merge before it merges, for example `make test`. Without it, `kevin finish` runs none. |

The prompts of the runtime's roles, the constitution, the language guides
and the tool sources come only from the runtime; a project file with the
name of a runtime role's prompt is never read. `kevin init` adds
`.worktrees/`, `.kevin/`, `tmp/` and `.metrics/` to `.git/info/exclude`. `kevin init --convert` turns a
project with an old committed install into this layout.

Start a task from the harness with `/swarm <feature request>`, or by hand:

```sh
kevin new "Add login" -m "Users log in with email and password." --route feature   # prints the id
kevin start <id>      # worktree and task text, run by the role that owns the stage
kevin review <id>     # specifier stops for approval
kevin route <id> spec-code-qa   # operator narrows the route at the gate
kevin new "Spike: can X do Y?" -m "..." --route spike   # coder answers, qa checks; never merged
kevin approve <id>    # operator; moves the task to the next role on the route
kevin gates <id> falsifier   # operator turns the falsifier on for the task
kevin ls              # id, stage, needs, route, gates, title
kevin finish <id>     # at stage done, after the pull request's merge is pulled: verify, remove the branch
```

Without harness subagents, `kevin run <id>` dispatches each stage to a headless
`claude`, `codex`, or `copilot` process until the task is done or needs the
operator. When none of those CLIs is on PATH but `MISTRAL_API_KEY` is set, the
`mistral` backend serves the same role instead: one Mistral Workflow
execution per task or role (see [workflows/](workflows/README.md)), with one
agent per role on its tier's model, all `kevin` bookkeeping on the task branch,
and the gate and spec-judge roles running as workflow steps that record
nothing. A session with neither a harness, a backend CLI, nor a Mistral API
key runs single-session by design: the operator plays every role and the
gates and spec-judge are skipped.

## Configuration

Run `kevin adapters` after editing `roles.conf` or `tiers.conf`. It writes
the project agents into the main checkout, from any worktree. A role has a
project agent for a harness when the agent generated from the project's
configuration differs from the one generated from the runtime's:
`.claude/agents/kevin-<role>.md` for Claude Code,
`.github/agents/<role>.agent.md` for Copilot CLI and
`.codex/agents/<role>.toml` for Codex. A Claude Code model change to
`sonnet`, `opus`, `haiku` or `fable` alone needs no file. Each project agent
has a `kevin-generated: <version>` line. `kevin adapters` lists each one in
`.git/info/exclude`, removes those no longer needed, and refuses to write over
a file without that line. `kevin init` does the same. In the Kevin source
checkout, `kevin adapters` also regenerates the plugin agents:
`agents/<role>.md` for Claude Code and `.github/plugin/agents/<role>.agent.md`
for Copilot CLI.

`kevin agent <role> <harness>` prints how the orchestrator runs a role in
`claude`, `copilot`, `codex` or `vibe`: `AGENT: <name>`, then `MODEL: <alias>`
when the delegation call takes the model, then a `WARNING:` when the project
agent is missing or stale. `AGENT: none` means `kevin dispatch`.
`AGENT: session` means the orchestrator plays the role; the next line,
`PROMPT: <path>`, names the prompt to read: the runtime's for a runtime role,
the main checkout's `kevin/roles/<role>.md` for an added role. In `vibe` a
runtime role prints its plugin agent `kevin:<role>` and an added role prints
`AGENT: none`; Vibe has no project agents. A project that relies on project
agents or adds a role sets `Minimum Kevin version: 0.3.0`.

`kevin check` validates the configuration files and the role prompts, and
refuses a `tiers.conf` value that does not start with a letter or a digit or
holds a character other than letters, digits and `. _ : / @ [ ] -`.
`kevin init`, `kevin adapters`, `kevin agent` and `kevin dispatch` run it
first and refuse a broken setup. `kevin check`, `kevin adapters`,
`kevin roles`, `kevin routes` and `kevin tool` read the checkout they run in;
`kevin init` and task bookkeeping read the main checkout's.

Catalogue settings:

- `session` makes the orchestrator play a role in chat; `subagent` delegates it.
- `audit` makes the first handoff or review at each commit request self-review
  without recording anything. Complete that review before retrying.
- `gate` makes a read-only subagent that audits a specification after
  `kevin review` and before approval; it is not a stage and cannot be on a route.
  It runs only on a task that chose it with `kevin new --gates` or
  `kevin gates <id> <gates>`; a task has gates `none` by default.
  The orchestrator shows its report at the gate. `kevin run` dispatches the
  task's gates when a specification it produced waits for approval.
  `kevin dispatch` starts a gate without edit permissions: claude in its default permission mode with
  Edit, Write and NotebookEdit disallowed, codex in its read-only sandbox,
  copilot with the write tool denied. The Claude and Copilot gate agents
  list only reading, search and shell tools. The shell can still write, so
  `kevin run` also stops when a gate records, commits or changes the task
  worktree. The shipped [falsifier](roles/falsifier.md) is a gate.
- `spec-judge` is the one read-only subagent that rules on each spec
  revision's gate findings: converged, continue, needs-operator or stuck. It
  is neither a stage nor a gate and cannot be on a route. The orchestrator
  records its spec verdict with `kevin spec-verdict`. Continue sends the
  specification back automatically, within the caps in
  [spec revisions](handoff-protocol.md#spec-revisions). The operator still
  approves. It runs only on a spec revision that a gate audited. The shipped
  [spec-judge](roles/spec-judge.md) turns the loop on. Delete its line to
  turn it off.
- `optional` excludes a role from fallback routes. Include it explicitly in a
  named route, `--route`, or `KEVIN_ROUTE` to enable it; it can also use `audit`.
- `send-back` lets a role run `kevin handoff <id> --to <role>` with an earlier
  role on the route, other than the specifier. The task then moves forward
  along the route again. `kevin` refuses the third send-back of a task. It
  combines with `audit` and `optional`; a gate or a spec-judge cannot have it.

Optional reviewers keep the usual approval and handoff flow. The `feature`
route includes the security reviewer; drop it per task with `kevin route`:

| Role | When to include it | Position |
|---|---|---|
| [Architecture design reviewer](roles/architecture-design-reviewer.md) | Migrations, public APIs, major dependency changes. | After specification approval, before coding; architect still reviews implementation. |
| [Security reviewer](roles/security-reviewer.md) | Authentication, authorization, secrets, untrusted input, external execution. | After hardener, before QA; hardener retains mutation and quality gates. |
| [Pull request reviewer](roles/pr-reviewer.md) | The online review bots are out of credits. Say "review pull request #<N>" on its head branch, or add the role to a task's route. | Alone, or before QA. It reports findings and changes no code; it sends obvious findings back to the coder. |

```sh
kevin new "Migrate public API" -m "..." --route specifier,architecture-design-reviewer,coder,architect,qa
kevin route <id> specifier,coder,cleaner,architect,hardener,qa
```
