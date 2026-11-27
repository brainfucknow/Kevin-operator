# Handoff Protocol

A task is a Git branch, `swarm/<id>`, containing the request, role work, and
record commits. `kevin` is the only writer of task state; never
write `Kevin-*` trailers by hand. There are no task files.

## Task

| Part | Purpose |
|---|---|
| `<id>` | Title slug plus seven random hex digits, e.g. `add-login-3f9a2c1`. Commands also accept the suffix or a unique prefix. |
| `created` record | The branch's first commit: the title and the operator's request in its message. |
| `finished` record | The `kevin finish` merge on a branch that is not protected. A task is finished when the main checkout's HEAD reaches it through any parent, or when the task has no local branch and its newest record that HEAD reaches is at `done` (a pull request's merge commit). |
| `.worktrees/<id>` | Role checkout, created by `kevin start` and removed by `kevin finish`. |
| `kevin/verify.conf` | Optional project verification commands, one per line; blank lines and `#` comments are ignored. |

## Record commits

The newest record in first-parent history supplies stage, route, gates, and needs.
Needs is `none`, `operator` while a question awaits an answer, or `approval`
while a reviewed specification awaits a decision. `kevin show <id>` prints the
records; use it rather than reconstructing them from trailers.

Records change no files. `finished` is the `--no-ff` merge commit of
`kevin finish` on a branch that is not protected. kevin reads only local
`swarm/*` branches; a remote one never lists or reopens a task. A task made before task files were dropped keeps its request
in the file its `created` commit adds, and its handoff to `done` deletes it.
kevin also reads the records of a task begun under the old name SwarmForge:
each `SwarmForge-<name>` trailer reads as `Kevin-<name>`. The hook rejects
reserved `Kevin-*` and `SwarmForge-*` trailers in role commits. Git's
`user.name` and `user.email` supply the author, with `KEVIN_USER` and
`KEVIN_EMAIL` as fallbacks. kevin reads each `KEVIN_<name>` variable, else the
old `SWARMFORGE_<name>`.

### Backend

kevin stores on each record the backend detected in the command environment, in two
kevin-only trailers: `Kevin-Backend` (`claude`, `codex`, `copilot` or
`mistral`) and `Kevin-Dispatched` (`dispatched` or `session`). A record
has both or neither. The operator's records carry the detected backend too.

| Environment of the `kevin` command | Record carries |
|---|---|
| `KEVIN_DISPATCHED` not blank, `KEVIN_BACKEND` a backend name | that backend, `dispatched` |
| `KEVIN_DISPATCHED` not blank, `KEVIN_BACKEND` anything else | nothing |
| `KEVIN_DISPATCHED` blank or unset, `CLAUDECODE` is `1`, `CODEX_THREAD_ID` blank or unset | `claude`, `session` |
| `KEVIN_DISPATCHED` blank or unset, `CODEX_THREAD_ID` not blank, `CLAUDECODE` is not `1` | `codex`, `session` |
| `KEVIN_DISPATCHED` blank or unset, both session markers active or neither active | nothing |

- `kevin dispatch` and `kevin run` start each role and gate with
  `KEVIN_BACKEND` set to the backend they run. Outside dispatch kevin does
  not read `KEVIN_BACKEND`. Under dispatch it ignores session markers.
- A Copilot or Vibe session and a plain terminal without session markers
  carry nothing.
- Child processes can inherit `CLAUDECODE` and `CODEX_THREAD_ID`. These
  markers identify the command environment, not the immediate parent
  harness. Both active markers omit session metadata.
- The handoff record that `kevin approve` writes for the role carries what the
  review record it approves carries, not the backend of the `kevin approve`
  command. The approval record carries the backend of the command.
- A `kevin finish` merge that stops on conflicts carries the backend of the
  `kevin finish` run that wrote its message, whoever commits it.
- kevin reads a record with one trailer missing, or with a value not listed
  above, as carrying nothing. Records written before the trailers existed
  carry nothing.

`kevin show` and `kevin start` print `backend: <name> dispatched` or
`backend: <name> session` as the last line of a record that carries a
backend, and no line for one that carries nothing.

## Lore lines

`kevin show` prints every [Lore trailer](constitution/articles/workflow.md#lore-trailers),
including evidence, from role commits on the task's first-parent history:

```text
LORE: 3e1f0a2 coder Constraint: keep the byline format
LORE: 3e1f0a2 coder Rejected: byline as a trailer | breaks old commits
```

Lines appear oldest commit first, then trailer order. Base-branch merges add
no Lore lines. The SHA is abbreviated; the role is the commit's last
`By <role>.` line, or `-`. Only Git-parsed trailer blocks count; folded values
print on one line. After finishing, `kevin show` reads the merge's second parent,
or, for a task closed by a pull request's merge, the history of its done record.
A task squash-merged or rebased stays open at `done`: its branch remains, and
`kevin finish` on a protected branch refuses it. One squash-merged after
`kevin finish` leaves no record in the base: `kevin` no longer knows it; its
history stays on the pushed branch.

## What kevin start leaves out

`kevin start` prints the request and a brief for the receiving role:

- Every question, answer, and changes-requested record, plus the last ten
  other records, oldest first. `RECORDS:` reports how many were omitted.
- Decision Lore, excluding `Tested`, `Not-tested`, and `Lore-id`.
- No lines from a commit named by a `Supersedes: <Lore-id>` trailer. The
  superseding trailer stays unless its own commit is superseded; unmatched
  IDs remove nothing. `LORE-OMITTED:` counts omitted evidence and superseded lines.

Use `kevin show <id>` when the brief omits needed history or evidence.

## Lifecycle

| Command | Transition or requirement |
|---|---|
| `kevin new` | Creates a route and starts its first role. Route precedence: `--route`, `KEVIN_ROUTE`, named `feature`, then all non-optional catalogue roles. `--fixes <target>` names what the task repairs: a task id, stored as the full id, or a pull request, quoted: `--fixes '#115'`. `--found-by` needs `--fixes` and says how the defect was found: `pr-review` (a pull request review after the task left the swarm), `ci` (a CI run) or `operator` (the operator reports it). The created record keeps them; `kevin show` prints them as `fixes:` and `found-by:`, and `kevin start` as `FIXES:` and `FOUND-BY:` after `ROUTE:`. |
| `kevin review` | Specifier requests operator approval of its committed specification. |
| `kevin approve` / `kevin reject` | Approve advances the specifier; reject requests changes. Approval must match the reviewed role commit; a new specification needs review again. An answer or rejection is not approval. Each `--rejected "<finding> \| <reason>"` on approve records a gate finding the operator approved over as a `Rejected:` trailer of the approval record. |
| `kevin spec-verdict <id> <spec-verdict> <text>` | Records the spec-judge's spec verdict on the newest spec revision ([rules](#spec-revisions)). |
| `kevin ask` / `kevin answer` | Block for an operator decision, then resume the same role. |
| `kevin handoff` | Requires a clean task worktree and needs `none`; records HEAD and advances to the next route role, then `done`. Use `--to` only as the role prompt directs; it names the current stage or a later route role, and `done` only after the route's last role. When the stage's role has the `send-back` flag, `--to` may also name an earlier route role other than the specifier; the task then moves forward along the route again. A send-back is a handoff record whose target comes before its sender on the route. `kevin` refuses the third send-back of a task. |
| `kevin route <id> <route>` | Changes an open task's route, retaining the current stage. Roles must follow catalogue order. |
| `kevin new --gates <gates>` / `kevin gates <id> [<gates>]` | `<gates>` is a comma list of gate roles (`kevin gates` lists them), or `none`. kevin stores each role once, in `roles.conf` order, and refuses an empty value or a role that is not a gate. A task without the option has gates `none`, also a task created before gates existed. `kevin gates <id>` prints the task's gates, one per line, and nothing for `none`. `kevin gates <id> <gates>` changes an open task's gates with a `gates` record that keeps stage, route and needs; it is not a spec revision. `kevin start` and `kevin show` print `GATES:` after `ROUTE:`; `kevin ls` prints the gates as the fifth column. |
| `kevin next <role>` | Lists open tasks at that stage needing nothing; blocked tasks stay hidden. |

`kevin new`, `kevin approve`, `kevin reject`, `kevin spec-verdict`, `kevin answer`, `kevin finish`,
a route change and a gates change are the operator's. `kevin dispatch` and `kevin run` start each role and gate with
`KEVIN_DISPATCHED` set to its name, and those commands refuse to run
under it. `kevin run` also stops when a stage it dispatched added an operator
record. Subagents that the session starts share the session's environment,
so for them this rule rests on the role prompts.

`kevin handoff` and `kevin review` read every `Not-tested` trailer (key in
any case) of the role's commits since the newest created, review or handoff
record. A trailer names a tool when the name of a non-local
`tools.conf` row appears in it, ignoring case, with no letter, digit, `-` or
`_` next to it. When `kevin tool require` would exit 1 for a named tool, the
command exits 4 and records nothing. It prints a `MISSING_TOOL` line and a
`Run: kevin tool ensure <tool>` line per missing tool, then what to do. The check
catches tool names only; the
[engineering rule](constitution/articles/engineering.md#startup-tools) is the
main control.

`--allow-missing-tool`, anywhere in the arguments and left out of the summary,
lets the command record over a missing tool only when a question record since
that milestone names the tool and an operator answer follows the question.
Otherwise the command exits 4 with an `ALLOW_REFUSED` line for each tool not
accepted. A record made over missing tools carries the kevin-only
`Kevin-Missing-Tools` trailer, which `kevin show` and `kevin start` print as
`missing-tools: <tools>`. `kevin approve` does not run the check; its review did.
`kevin answer` refuses a role only under `kevin dispatch`, so kevin enforces the
operator's part only there.

### Finishing and recovery

`kevin finish` refuses staged, unstaged, or untracked worktree changes without
changing anything. It verifies a merge candidate in the task worktree, using
the base branch as the current side of the merge. Commands run sequentially
with `bash -c` from that checkout's root. `VERIFY:` reports each pass or
`none configured`. A failed command exits 1, preserving both branches and the
original worktree.

On success, `kevin finish` merges with `--no-ff`, removes the worktree without
force, then removes the branch. Rerun it to complete interrupted cleanup.
Finished tasks refuse `kevin start` and further records.

`kevin finish` never commits on a protected branch: main, master, develop,
release, and the default branch. The default branch is the one that the
first remote's HEAD names (`refs/remotes/<remote>/HEAD`), else a non-blank
`init.defaultBranch`, else master. `--into` a protected branch is refused.
When the checked-out branch is protected and HEAD lacks the task tip,
`kevin finish` is refused with the pull-request path: push `swarm/<id>`, open
a pull request, merge it with a merge commit, pull, then run `kevin finish
<id>`. When HEAD contains the tip, `kevin finish` verifies HEAD, removes the
worktree and the branch, and writes no commit. The task is then finished
through its done record.

If the merge conflicts, resolve and commit it in the main checkout, then
rerun `kevin finish`. It verifies that committed merge in the task worktree,
detached at HEAD, before recording and cleaning up, so an uncommitted fix in
the main checkout does not count. If verification fails, commit the fix and
retry. On a branch that is not protected, when HEAD contains the branch tip
without the finished record (a merge committed with another message, or a
host's merge, with or without commits after it), `kevin finish` commits the
finished record on top of HEAD as a merge of the tip. It never rewrites a
commit HEAD already has. Nobody writes the finished-record trailers manually.

## Spec revisions

With a `spec-judge` row in `roles.conf`, the specification gate of a task with gates loops. After the task's gates run on each spec revision, the spec-judge gives a spec verdict and the orchestrator records it with `kevin spec-verdict`. Remove the spec-judge line to turn the loop off. `kevin spec-verdict` then refuses.

- A spec revision is one `review` record made at stage specifier, numbered from 1. It includes the `spec-verdict` and `changes-requested` records before the next review. A review of another stage is not a spec revision.
- `kevin spec-verdict <id> converged|needs-operator|stuck <text>` writes a `spec-verdict` record from the spec-judge. The task keeps stage, route and needs, and waits for approval.
- `kevin spec-verdict <id> continue <text>` writes a `changes-requested` record from the spec-judge, an automatic send-back. Like `kevin reject`, it keeps the stage and clears needs.
- Both carry the `Kevin-Spec-Verdict` trailer. A record with a spec verdict prints a `spec-verdict:` line after `from:`.
- kevin reads a record of an older kevin the same way: type `verdict` as `spec-verdict`, and the trailer `SwarmForge-Verdict` as `Kevin-Spec-Verdict`. Its `from:` stays as stored.
- kevin records nothing and refuses a spec verdict when:
  - `KEVIN_DISPATCHED` is set;
  - there is no spec-judge;
  - the stage is not specifier;
  - the task is not waiting for approval;
  - the spec revision already has a spec verdict.
- kevin refuses `continue` when a limit holds. It checks the cap first.
  - Cap: the task has 2 automatic send-backs. `kevin reject` never counts.
  - Growth: the spec revision is over the growth limit.
- Counted commits of a spec revision: the first-parent commits after the created record, up to the spec revision's reviewed commit, that are not records or merges.
- Size is the sum of:
  - the nonblank message lines, without the trailer block, of each counted commit that no counted commit supersedes (`Supersedes: <Lore-id>`);
  - the lines added minus removed over all counted commits (`git show --numstat`), at least 0 in total.
- A merge of the base branch adds nothing.
- The baseline of a spec revision is the first spec revision after the newest `kevin reject` before it, else spec revision 1. B is its size.
- Growth is `(size - B) * 100 / B`, rounded down, or `-` when B is 0.
- A spec revision is over the growth limit when B is not 0 and `(size - B) * 100 > 50 * B`.
- Total is the growth of a spec revision's size over the size of spec revision 1, S1, by the same formula: `-` when S1 is 0, `0%` in spec revision 1.
- `kevin spec-revisions <id> [--full]` only reads. It runs for any role on any task `kevin show` reads, and refuses any other argument. It prints `NO_SPEC_REVISIONS`, or:
  - per spec revision, `SPEC-REVISION <n> <sha> size:<size> growth:<growth> total:<total> spec-verdict:<spec-verdict or ->`; with `--full`, the text of its spec-verdict and changes-requested records under it;
  - one line per limit that holds for the newest spec revision: `LIMIT: cap 2 of 2 automatic send-backs used` and `LIMIT: growth size <size> is more than half over <B>`;
  - `WARNING: total growth <total> over spec revision 1 (<S1>); more than half over across operator send-backs`, when the newest spec revision's baseline is not spec revision 1, S1 is not 0 and `(size - S1) * 100 > 50 * S1`;
  - `NOTE: total growth <total> over spec revision 1 (<S1>); not a limit`, under the same condition as the WARNING. With baseline spec revision 1, `LIMIT: growth` says it.
- The WARNING and the NOTE are not limits: `kevin spec-verdict continue` is not refused for them; only the LIMIT lines refuse it.
- `kevin reject <id> <text>` prints the WARNING line after `REJECTED: <id> stays at stage:<stage>` when the condition holds for the newest spec revision, the one it sends back. The reject still exits 0 and writes the changes-requested record; it is never refused.
- `kevin show <id>` prints the record texts with or without `--full`.

## Sharing

Push `swarm/<id>` to share its work and records; fetch it in another clone.
Record commits change no files, so a rebase replays them without conflicts,
but onto other role commits: a record then sits on a commit its role never
saw. Merge a fetched task branch rather than rebasing it. A `review` record
names the commit it reviewed, so `kevin approve` refuses a review that a rebase
moved; run `kevin review` again.

Mutation manifests live on the orphan branch `kevin/metrics`, mirroring
`.metrics/mutate/` and `.metrics/gherkin/`. `kevin start` loads missing files;
`kevin handoff` saves new and changed ones without checking out that branch.
Push `kevin/metrics` explicitly to share it; `kevin` does not push it.
While `kevin/metrics` does not exist, `kevin start` loads from the old branch
`swarmforge/metrics`, and the first save starts from its tree. kevin never
changes `swarmforge/metrics`.

## Harvest

`kevin harvest` takes no arguments and prints one JSON line per task for
mining role failure patterns: finished tasks first, ordered by the author
date of the record that closes them (the finish merge or the done record),
oldest first, then open tasks by id, then unclosed histories by id. It reads
only: no ref, worktree, or file changes, and any role may run it. A `swarm/*`
branch with no record is skipped with a `kevin: ` line on stderr.

Each line has `id`, `title`, `request`, `status` (`open`, `finished`, or `unclosed`),
`truncated`, `stage`, `needs`, `route` and `gates` (arrays; `gates` is empty for `none`) from the newest record,
`fixes` and `found_by` from the created record (null when it has none, or
the task is truncated), plus `records` and `lore`. A `fixes` value that
starts with `#` is a pull request; any other is a task id.

- `records`, oldest first, as `kevin show` prints them. Every element has
  `type`, `from`, `stage`, `needs`, `route`, `commit` (created, review and
  handoff only), `text` (the free text of any record but created),
  `merged_into` (finished), `rejected` (approval) and `spec_verdict` (a
  spec-verdict record or an automatic send-back), each null where it does not
  apply.
  `rejected` holds one `{finding, reason, kind, severity}` per `Rejected:`
  trailer, split at the last ` | `; `kind` and `severity` come from a
  `DEFECT|PREMISE, CRITICAL|HIGH|MEDIUM|LOW: ` prefix, else null. `files:`
  are not exported.
  Every element also has `time` and `recorded_by`, never null: the author
  date of the record commit in UTC, as `YYYY-MM-DDTHH:MM:SSZ`, and the git
  author name of that commit. The name is the backend (`Claude`, `Codex`)
  only where each backend has its own git identity. For a finished record
  the record commit is the `kevin finish` merge.
  Every element ends with `backend` and `dispatched`, from the record's
  [backend](#backend): `backend` is the name and `dispatched` is `true` for
  a dispatched record and `false` for a session record. Both are null for
  a record that carries nothing.
- `lore`: every Lore trailer `kevin show` prints, evidence and superseded
  commits included, except `Lore-id`, as `{commit, role, key, value,
  lore_id}`, `role` null for `-`. `lore[].commit` is the commit that carries
  the trailer; it equals a `records[].commit` only when a review or handoff
  recorded that commit, so it is not a join key.

An `unclosed` history has recognized records reachable from HEAD but no local
task branch or `kevin finish` record, and its newest recognized record is not
at `done`. Recognition requires a nonblank task id and
a known record type; incidental or unknown-type trailers do not establish a
record. Its newest recognized record anchors the history; later unrecorded
commits cannot be attributed to it. `kevin show` and `kevin harvest` expose that
history. `kevin stage` and `kevin route <id>` return its last recorded values, or fail
explicitly if the requested value is absent. These values do not prove that
verification or lifecycle closure ran. Execution and lifecycle commands,
including `run`, `dispatch` and `finish`, refuse this read-only history without
recreating its branch. It does not appear in `kevin ls` and is not a finished task.
Discovery cannot recover records removed by a squash or rebase.

A task whose readable history lacks its `created` record (a shallow clone)
is `truncated`: `title` and `request` are empty, its readable records are
listed, a commit outside the history is null, and its Lore starts after its
oldest readable record.

In a shallow repository `kevin harvest` first prints this line on stderr and
changes nothing else:
`kevin: shallow repository: older tasks are truncated or missing; run git fetch --unshallow`.

## Audit

`kevin show <id>` gives the complete request and record history. Inspect role
work with `git log --first-parent swarm/<id>` before finishing, or the merge's
second parent afterwards.
