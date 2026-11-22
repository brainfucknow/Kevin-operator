#!/usr/bin/env bash
# Behavioral tests for kevin/bin/kevin in a scratch repo. Tasks live in git
# branches; git-bug is not used.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected '$3', got '$2'"; fi; }
assert_match() { if grep -Eq "$3" <<<"$2"; then pass "$1"; else fail "$1: '$2' does not match /$3/"; fi; }
assert_fails() { local name="$1"; shift; if "$@" >/dev/null 2>&1; then fail "$name should fail"; else pass "$name fails"; fi; }

scratch="$(mktemp -d)"
trap 'rc=$?; [ "$rc" -eq 0 ] || diagnose "exit status $rc"; rm -rf "$scratch"' EXIT
export HOME="$scratch/home"; mkdir -p "$HOME"
# These decide the backend on a record; this script may run inside a harness.
unset CLAUDECODE CODEX_THREAD_ID KEVIN_BACKEND KEVIN_DISPATCHED

# On a non-zero exit, dump the task branches so that the CI log shows the cause.
diagnose() {
  echo "=== diagnostics after failure ($1) ===" >&2
  [ -d "$repo/.git" ] || return 0
  cd "$repo"
  for b in $(git for-each-ref --format='%(refname)' refs/heads/swarm/); do
    echo "--- $b" >&2
    git log --first-parent --format='%h %s%n%(trailers)' "$b" >&2 || true
  done
}
git config --global user.name tester
git config --global user.email tester@example.com
git config --global init.defaultBranch main

# A copy of this checkout's kevin/ without verify.conf, whose commands
# (this script among them) must not run on every kevin finish in the scratch repo.
copy_kevin() {
  rm -rf "$1/kevin" && cp -R "$HERE/kevin" "$1/kevin" && rm -f "$1/kevin/verify.conf"
}

repo="$scratch/project"
mkdir -p "$repo" && cd "$repo"
git init -q .
copy_kevin .
echo "hello" > README.md
git add -A && git commit -q -m "seed"
export PATH="$repo/kevin/bin:$PATH"

# init
out="$(kevin init)"
assert_match "init prints the identity of record commits" "$out" "identity: tester <tester@example.com>"
assert_match "init reports the catalogue" "$out" "roles: specifier architecture-design-reviewer coder cleaner architect hardener security-reviewer pr-reviewer qa"
assert_match "init reports the routes" "$out" "routes: feature bugfix refactor harden spike spec-code-qa code-qa$"
assert_eq "init is idempotent" "$(kevin init | head -n 1)" "identity: tester <tester@example.com>"
assert_eq "init writes no git-bug data" "$(git for-each-ref refs/bugs refs/identities)" ""

# new
id="$(kevin new "Add login" -m "Users can log in with email and password.")"
assert_match "new prints a slug and hex id" "$id" '^add-login-[0-9a-f]{7}$'
assert_eq "new puts the task at the first stage" "$(kevin stage "$id")" "specifier"
full="specifier,coder,cleaner,architect,hardener,security-reviewer,qa"
fallback="specifier,coder,cleaner,architect,hardener,qa"
assert_eq "new defaults to the feature route" "$(kevin route "$id")" "$full"
assert_eq "new creates the task branch" "$(git branch --list "swarm/$id" | tr -d ' *')" "swarm/$id"
assert_match "show prints the body" "$(kevin show "$id")" "email and password"
assert_match "new records the branch point" "$(kevin show "$id")" "type: created"
assert_match "created names its own commit" "$(kevin show "$id")" "commit: $(git rev-parse --short "swarm/$id")"
assert_eq "the created commit sits on main" "$(git rev-parse "swarm/$id^")" "$(git rev-parse main)"
assert_eq "the created commit changes no file" "$(git diff-tree --no-commit-id -r "swarm/$id")" ""
assert_match "the created commit holds the request" "$(git log -1 --format=%B "swarm/$id")" "^Users can log in with email and password\.$"

# next
assert_eq "next finds the task for its stage" "$(kevin next specifier)" "$id"
assert_eq "next prints NO_TASK for other stages" "$(kevin next coder)" "NO_TASK"
assert_fails "next with unknown role" kevin next janitor
assert_match "ls lists the task with its route" "$(kevin ls)" "^$id	specifier		$full	none	Add login$"

# start
out="$(kevin start "$id")"
assert_match "start prints the task id" "$out" "^TASK: $id$"
assert_match "start prints the worktree" "$out" "^DIR: $repo/.worktrees/$id$"
assert_match "start prints the route" "$out" "^ROUTE: $full$"
assert_eq "start checks out the task branch" "$(git -C ".worktrees/$id" rev-parse --abbrev-ref HEAD)" "swarm/$id"
assert_match "start is idempotent" "$(kevin start "$id")" "^DIR: "

# commit byline hook
hook="$repo/.git/hooks/commit-msg"
assert_eq "init installs the commit-msg hook" "$(test -x "$hook" && echo installed)" "installed"
echo "Feature: login" > ".worktrees/$id/login.feature"
assert_fails "handoff with uncommitted work" kevin handoff "$id"
git -C ".worktrees/$id" add -A && git -C ".worktrees/$id" commit -q -m "Specify login"
sha="$(git -C ".worktrees/$id" rev-parse --short HEAD)"
assert_eq "hook appends the stage byline" "$(git -C ".worktrees/$id" log -1 --format=%B | grep -v "^$" | tail -1)" "By specifier."
git -C ".worktrees/$id" commit -q --allow-empty -m "$(printf 'Already signed\n\nBy specifier.')"
assert_eq "hook does not duplicate a byline" "$(git -C ".worktrees/$id" log -1 --format=%B | grep -c '^By specifier\.$')" "1"
KEVIN_ROLE=coder git -C ".worktrees/$id" commit -q --allow-empty -m "Forced role"
assert_eq "KEVIN_ROLE overrides the stage" "$(git -C ".worktrees/$id" log -1 --format=%B | grep -v "^$" | tail -1)" "By coder."
printf '#!/bin/sh\nprintf "$1" | cat - "$2" > "$2.new" && mv "$2.new" "$2"\n' > "$scratch/type-above-template"
chmod +x "$scratch/type-above-template"
GIT_EDITOR="'$scratch/type-above-template' 'Edited\n\nTested: x\n'" git -C ".worktrees/$id" commit -q --allow-empty
assert_eq "editor commit keeps the trailer block last" "$(git -C ".worktrees/$id" log -1 --format='%(trailers:key=Tested,valueonly)' | head -1)" "x"
assert_eq "editor commit gets the byline" "$(git -C ".worktrees/$id" log -1 --format=%B | grep -c '^By specifier\.$')" "1"
assert_fails "an empty editor message still aborts the commit" env GIT_EDITOR="'$scratch/type-above-template' ''" git -C ".worktrees/$id" commit -q --allow-empty
git commit -q --allow-empty -m "On main checkout"
assert_eq "hook leaves commits outside task worktrees alone" "$(git log -1 --format=%B)" "On main checkout"
KEVIN_ROLE=coder git commit -q --allow-empty -m "Forced role outside"
assert_eq "KEVIN_ROLE does not reach outside task worktrees" "$(git log -1 --format=%B)" "Forced role outside"
git -C ".worktrees/$id" commit -q --allow-empty -m "$(printf 'Prose\n\nKevin-Stage: done\n\nmore prose')"
assert_eq "hook allows a Kevin line outside the trailer block" "$(git -C ".worktrees/$id" log -1 --format=%s)" "Prose"
assert_fails "hook refuses a Kevin trailer" git -C ".worktrees/$id" commit -q --allow-empty -m "$(printf 'Forged\n\nKevin-Stage: done')"
mv "$repo/kevin/bin/kevin" "$scratch/kevin.moved"
assert_fails "task commit is refused when the role cannot be found" git -C ".worktrees/$id" commit -q --allow-empty -m "No role"
mv "$scratch/kevin.moved" "$repo/kevin/bin/kevin"
cp "$hook" "$scratch/our-hook" && printf '#!/bin/sh\nexit 0\n' > "$hook"
assert_match "start leaves a foreign hook alone" "$(kevin start "$id")" "^HOOK: existing commit-msg hook left alone"
assert_eq "foreign hook is unchanged" "$(cat "$hook")" "$(printf '#!/bin/sh\nexit 0')"
cp "$scratch/our-hook" "$hook"
git -C ".worktrees/$id" reset -q --hard "$sha"

# review and approval gate
out="$(kevin review "$id" -m "spec ready")"
assert_match "review asks for approval" "$out" "waits for operator approval"
assert_match "review lists the files since the branch point" "$(kevin show "$id")" "^ *files: login.feature$"
assert_match "review waits for approval" "$(kevin ls --needs)" "^$id	specifier	approval	"
assert_fails "handoff while waiting for approval" kevin handoff "$id"
assert_eq "next skips tasks waiting on the operator" "$(kevin next specifier)" "NO_TASK"
assert_match "a dispatched role cannot approve" "$(KEVIN_DISPATCHED=specifier kevin approve "$id" 2>&1 || true)" "^kevin: approve is the operator's; the dispatched specifier cannot run it$"
assert_fails "a dispatched role cannot reject" env KEVIN_DISPATCHED=specifier kevin reject "$id" "no"
assert_fails "a dispatched role cannot change the route" env KEVIN_DISPATCHED=specifier kevin route "$id" specifier,coder
assert_fails "a dispatched role cannot answer" env KEVIN_DISPATCHED=specifier kevin answer "$id" "yes"
assert_fails "a dispatched role cannot finish" env KEVIN_DISPATCHED=specifier kevin finish "$id"
branches="$(git for-each-ref --format='%(refname)' refs/heads/swarm/)"
assert_match "a dispatched gate cannot create a task" "$(KEVIN_DISPATCHED=falsifier kevin new "Gate task" -m x 2>&1 || true)" "^kevin: new is the operator's; the dispatched falsifier cannot run it$"
assert_eq "the refused new makes no task branch" "$(git for-each-ref --format='%(refname)' refs/heads/swarm/)" "$branches"
assert_match "a dispatched role can still read the route" "$(KEVIN_DISPATCHED=specifier kevin route "$id")" "^specifier,"
assert_match "the refused commands leave the task waiting for approval" "$(kevin ls --needs)" "^$id	specifier	approval	"
assert_match "reject keeps the stage" "$(kevin reject "$id" "split the scenarios")" "stays at stage:specifier"
assert_match "reject records the reason" "$(kevin show "$id")" "reason: split the scenarios"
assert_eq "reject clears needs" "$(kevin ls --needs)" ""
kevin review "$id" >/dev/null
out="$(kevin approve "$id")"
assert_match "approve hands off to the next stage" "$out" "^HANDOFF: $id specifier -> coder at $sha$"
assert_eq "approve moves the stage" "$(kevin stage "$id")" "coder"
assert_match "handoff record names the role's commit" "$(kevin show "$id")" "commit: $sha"
assert_match "handoff with no new commits lists no files" "$(kevin show "$id")" "^ *files: -$"
echo "Feature: logout" > ".worktrees/$id/logout.feature"
echo "Feature: login v2" > ".worktrees/$id/login.feature"
git -C ".worktrees/$id" add -A && git -C ".worktrees/$id" commit -q -m "Add logout"
KEVIN_ROLE=coder kevin handoff "$id" --to coder >/dev/null
assert_match "handoff lists files since the previous record" "$(kevin show "$id")" "^ *files: login.feature,logout.feature$"
kevin ask "$id" "$(printf 'which hash?\ncommit: deadbee looked fine')" >/dev/null
assert_match "free text keeps its lines, indented" "$(kevin show "$id")" "^  commit: deadbee looked fine$"
kevin answer "$id" "argon2id" >/dev/null
echo "Feature: reset" > ".worktrees/$id/reset.feature"
git -C ".worktrees/$id" add -A && git -C ".worktrees/$id" commit -q -m "Add reset"
KEVIN_ROLE=coder kevin handoff "$id" --to coder >/dev/null
assert_match "files ignore commit mentions outside records" "$(kevin show "$id")" "^ *files: reset.feature$"
assert_fails "approve when not waiting" kevin approve "$id"

# questions
out="$(kevin ask "$id" "Which hash algorithm?")"
assert_match "ask stops for the operator" "$out" "waits for the operator"
assert_match "ask records the asking role" "$(kevin show "$id")" "from: coder"
assert_fails "handoff while a question is open" kevin handoff "$id"
assert_eq "next skips a blocked task" "$(kevin next coder)" "NO_TASK"
assert_match "answer resumes the stage" "$(kevin answer "$id" "bcrypt")" "resumes at stage:coder"
assert_eq "next finds the resumed task" "$(kevin next coder)" "$id"

# explicit role and explicit target
out="$(KEVIN_ROLE=coder kevin handoff "$id" --to qa -m "skip ahead")"
assert_match "handoff honors --to" "$out" "coder -> qa"
assert_match "handoff honors -m" "$(kevin show "$id")" "summary: skip ahead"
out="$(kevin handoff "$id")"
assert_match "last stage hands off to done" "$out" "qa -> done"
assert_eq "done is the terminal stage" "$(kevin stage "$id")" "done"
assert_eq "the handoff to done changes no file" "$(git diff-tree --no-commit-id -r "swarm/$id")" ""

# finish
assert_fails "finish an unknown task" kevin finish "zzzzzzz"
idn="$(kevin new "Not done" -m x --route coder)"
assert_fails "finish from a non-done task" kevin finish "$idn"
out="$(kevin finish "$id")"
assert_match "finish merges into the current branch" "$out" "merged into main"
assert_match "finish without verification commands says so" "$out" "^VERIFY: none configured$"
assert_match "main contains the task work" "$(git log --oneline main)" "Specify login"
assert_eq "finish removes the worktree" "$(test -d ".worktrees/$id" && echo present || echo gone)" "gone"
assert_eq "finish deletes the branch" "$(git branch --list "swarm/$id")" ""
assert_eq "finish leaves only unfinished tasks" "$(kevin ls | cut -f1)" "$idn"
assert_match "a finished task is still shown" "$(kevin show "$id")" "^STATUS: finished$"
assert_match "a finished task shows its request" "$(kevin show "$id")" "^Users can log in with email and password\.$"
assert_eq "no path in HEAD names the finished task" "$(git ls-tree -r --name-only HEAD | grep -c "$id" || true)" "0"
assert_fails "a finished task cannot be started" kevin start "$id"

# approval over gate findings
reviewed_task() {
  local t
  t="$(kevin new "$1" -m x --route specifier,coder)"
  kevin start "$t" >/dev/null
  git -C ".worktrees/$t" commit -q --allow-empty -m "Spec"
  kevin review "$t" >/dev/null
  echo "$t"
}
assert_match "help lists approve --rejected" "$(kevin help)" '^  approve <id> \[--rejected "<finding> \| <reason>"\]\.\.\.$'
ida="$(reviewed_task "Approve over findings")"
spec_sha="$(git rev-parse --short "swarm/$ida^")"
out="$(kevin approve "$ida" --rejected "DEFECT, LOW: a | b | accepted by the operator at the gate" --rejected "  C | d  ")"
assert_match "approve --rejected hands off as a plain approve" "$out" "^HANDOFF: $ida specifier -> coder at $spec_sha$"
approval="$(git rev-parse --short "swarm/$ida^")"
assert_match "the approval record is the commit under the handoff" "$(git log -1 --format=%s "$approval")" "^kevin approval: $ida$"
assert_eq "the approval record holds one Rejected trailer per option, trimmed, in order" \
  "$(git log -1 --format='%(trailers:key=Rejected,valueonly)' "$approval")" \
  "$(printf 'DEFECT, LOW: a | b | accepted by the operator at the gate\nC | d')"
assert_match "the approval record holds one Lore-id" "$(git log -1 --format='%(trailers:key=Lore-id,valueonly)' "$approval")" '^[0-9a-f]{8}$'
assert_match "show prints each Rejected trailer as a LORE line with role -" "$(kevin show "$ida")" \
  "^LORE: $approval - Rejected: DEFECT, LOW: a \| b \| accepted by the operator at the gate$"
assert_match "start prints them in the next stage" "$(kevin start "$ida")" "^LORE: $approval - Rejected: C \| d$"
idp="$(reviewed_task "Plain approve")"
kevin approve "$idp" >/dev/null
assert_eq "a plain approve writes no Lore trailer" "$(git log -1 --format='%(trailers:key=Rejected,key=Lore-id)' "swarm/$idp^")" ""
idb="$(reviewed_task "Bad rejected")"
tip="$(git rev-parse "swarm/$idb")"
refused_approve() {
  local name="$1"; shift
  assert_match "approve refuses $name" "$(kevin approve "$idb" "$@" 2>&1 || true)" "^kevin: "
}
refused_approve "--rejected with no value" --rejected
refused_approve "a value without ' | '" --rejected "A|b"
refused_approve "a value blank after its last ' | '" --rejected "A | "
refused_approve "a value blank before its last ' | '" --rejected " | b"
refused_approve "a value with a newline" --rejected "$(printf 'A | b\nc')"
refused_approve "a mistyped option" --rejectd "A | b"
refused_approve "an extra argument" --rejected "A | b" extra
assert_eq "the refused approvals write no record" "$(git rev-parse "swarm/$idb")" "$tip"
assert_match "the refused approvals leave the task waiting for approval" "$(kevin ls --needs)" "^$idb	specifier	approval	"
assert_match "a dispatched role cannot approve with --rejected" "$(KEVIN_DISPATCHED=specifier kevin approve "$idb" --rejected "A | b" 2>&1 || true)" "^kevin: approve is the operator's"
git -C ".worktrees/$idb" commit -q --allow-empty -m "After review"
tip="$(git rev-parse "swarm/$idb")"
assert_match "approve --rejected refuses a moved review commit" "$(kevin approve "$idb" --rejected "A | b" 2>&1 || true)" "changed since review"
assert_eq "the moved-commit refusal writes no record" "$(git rev-parse "swarm/$idb")" "$tip"
assert_match "approve --rejected refuses a task not waiting for approval" "$(kevin approve "$ida" --rejected "A | b" 2>&1 || true)" "not waiting for approval"

# routes
assert_eq "routes lists the presets" "$(kevin routes | awk '{print $1}' | paste -sd ' ' -)" "feature bugfix refactor harden spike spec-code-qa code-qa"
assert_eq "new without --route records the feature route" "$(kevin new "Default" -m x | xargs kevin route)" "specifier,coder,cleaner,architect,hardener,security-reviewer,qa"
assert_eq "spec-code-qa is recorded" "$(kevin new "Spec code" -m x --route spec-code-qa | xargs kevin route)" "specifier,coder,qa"
assert_eq "code-qa is recorded" "$(kevin new "Code" -m x --route code-qa | xargs kevin route)" "coder,qa"
id2="$(kevin new "Fix rounding" -m "x" --route bugfix)"
assert_eq "named route is recorded" "$(kevin route "$id2")" "specifier,coder,cleaner,qa"
kevin start "$id2" >/dev/null
git -C ".worktrees/$id2" commit -q --allow-empty -m "Spec. By specifier."
kevin review "$id2" >/dev/null && kevin approve "$id2" >/dev/null
assert_fails "route change must contain the current stage" kevin route "$id2" refactor
assert_match "handoff follows the route" "$(kevin handoff "$id2")" "coder -> cleaner"
assert_match "route change at the current stage" "$(kevin route "$id2" "coder,cleaner,qa")" "^ROUTE: $id2 coder,cleaner,qa$"
assert_match "route change is recorded" "$(kevin show "$id2")" "route: coder,cleaner,qa"
assert_match "handoff follows the changed route" "$(kevin handoff "$id2")" "cleaner -> qa"
assert_fails "route in the wrong order" kevin new "Bad" -m x --route "qa,coder"
assert_fails "route with an unknown role" kevin new "Bad" -m x --route "janitor"
id3="$(kevin new "Tidy" -m "x" --route "cleaner,architect")"
assert_eq "explicit route starts at its first role" "$(kevin stage "$id3")" "cleaner"
ids="$(KEVIN_ROUTE=spike kevin new "Spike: idea" -m x)"
assert_eq "KEVIN_ROUTE sets the default" "$(kevin route "$ids")" "coder,qa"
assert_eq "a spike starts at the coder" "$(kevin stage "$ids")" "coder"
ids="$(kevin new "Spike: can X do Y?" -m x --route spike)"
kevin start "$ids" >/dev/null
echo "echo yes" > ".worktrees/$ids/probe.sh"
git -C ".worktrees/$ids" add probe.sh && git -C ".worktrees/$ids" commit -q -m "yes"
assert_match "spike coder hands off to qa" "$(kevin handoff "$ids" -m "yes")" "coder -> qa"
kevin start "$ids" >/dev/null
git -C ".worktrees/$ids" commit -q --allow-empty -m "Confirmed: yes"
assert_match "spike qa hands off to done" "$(kevin handoff "$ids" -m "Confirmed: yes")" "qa -> done"
assert_match "spike verdict is in the history" "$(kevin show "$ids")" "summary: Confirmed: yes"

# optional roles are selected through existing routes and lifecycle
review_route="specifier,architecture-design-reviewer,coder,architect,qa"
idr="$(kevin new "Migrate interface" -m x --route "$review_route")"
assert_eq "explicit opt-in route is recorded" "$(kevin route "$idr")" "$review_route"
kevin start "$idr" >/dev/null
git -C ".worktrees/$idr" commit -q --allow-empty -m "Approve migration criteria"
kevin review "$idr" >/dev/null
assert_match "approval sends an opted-in task to design review" "$(kevin approve "$idr")" "specifier -> architecture-design-reviewer"
assert_fails "design review cannot be placed after coder" kevin new Bad -m x --route "coder,architecture-design-reviewer,qa"
kevin ask "$idr" "Which compatibility window?" >/dev/null
assert_fails "reviewer cannot hand off a blocker" kevin handoff "$idr"
kevin answer "$idr" "Keep both interfaces for one release" >/dev/null
assert_match "reviewer uses ordinary handoff without approval" "$(kevin handoff "$idr")" "architecture-design-reviewer -> coder"
assert_match "coder still hands off to existing architect" "$(kevin handoff "$idr")" "coder -> architect"
assert_match "existing architect still hands off to QA" "$(kevin handoff "$idr")" "architect -> qa"
assert_eq "environment route can opt in" "$(KEVIN_ROUTE=architecture-design-reviewer,coder kevin new "Review migration" -m x | xargs kevin route)" "architecture-design-reviewer,coder"
cp kevin/routes.conf "$scratch/routes.conf"
rm kevin/routes.conf
assert_eq "implicit catalogue fallback skips optional reviewer" "$(kevin new "Legacy fallback" -m x | xargs kevin route)" "$fallback"
printf 'quick coder\n' > kevin/routes.conf
assert_eq "fallback without feature also skips optional reviewer" "$(kevin new "Missing feature fallback" -m x | xargs kevin route)" "$fallback"
mv "$scratch/routes.conf" kevin/routes.conf

# Optional review uses the existing route and handoff machinery.
review_id="$(kevin new "Review execution boundary" -m x --route coder,hardener,security-reviewer,qa)"
assert_eq "optional security review is explicit" "$(kevin route "$review_id")" "coder,hardener,security-reviewer,qa"
kevin start "$review_id" >/dev/null
kevin handoff "$review_id" >/dev/null
assert_eq "hardener still precedes optional security review" "$(kevin stage "$review_id")" "hardener"
kevin handoff "$review_id" >/dev/null
assert_eq "security review follows hardener" "$(kevin stage "$review_id")" "security-reviewer"
kevin ask "$review_id" "Confirmed defect needs a scoped correction" >/dev/null
assert_fails "blocking security findings prevent handoff" kevin handoff "$review_id"
kevin answer "$review_id" "Correction authorized" >/dev/null
kevin handoff "$review_id" >/dev/null
assert_eq "QA follows optional security review" "$(kevin stage "$review_id")" "qa"
assert_fails "security review cannot precede hardener" kevin new "Wrong review order" --route security-reviewer,hardener,qa

# send-back: a flagged stage hands the task back to an earlier role, twice at most.
assert_eq "the fallback route skips the pull request reviewer" "$(kevin new "No reviewer" -m x | xargs kevin route | grep -c pr-reviewer || true)" "0"
pr_id="$(kevin new "Review pull request #1" -m "Review pull request #1 against main." --route pr-reviewer)"
assert_eq "pull request mode starts at the reviewer" "$(kevin stage "$pr_id")" "pr-reviewer"
sb="$(kevin new "Send back" -m x --route coder,cleaner,pr-reviewer,qa)"
kevin start "$sb" >/dev/null
to_reviewer() { kevin handoff "$sb" >/dev/null; kevin handoff "$sb" >/dev/null; }
to_reviewer
set +e; out="$(kevin handoff "$sb" --to coder 2>&1)"; rc=$?; set -e
assert_eq "the first send-back is accepted" "$rc" "0"
assert_match "the first send-back hands the task to the coder" "$out" "^HANDOFF: $sb pr-reviewer -> coder at "
assert_eq "a send-back moves the stage back" "$(kevin stage "$sb")" "coder"
assert_eq "the coder gets a sent-back task" "$(kevin next coder | grep -c "^$sb$")" "1"
kevin handoff "$sb" >/dev/null
set +e; err="$(kevin handoff "$sb" --to coder 2>&1)"; rc=$?; set -e
assert_eq "a stage without the flag keeps the refusal of today" "$rc $err" "1 kevin: --to coder is not on route 'coder,cleaner,pr-reviewer,qa' at or after stage:cleaner"
kevin handoff "$sb" >/dev/null
assert_eq "the task reaches the reviewer again" "$(kevin stage "$sb")" "pr-reviewer"
kevin handoff "$sb" --to coder >/dev/null
to_reviewer
tip="$(git rev-parse "swarm/$sb")"
set +e; err="$(kevin handoff "$sb" --to coder 2>&1)"; rc=$?; set -e
assert_eq "the third send-back is refused" "$rc $err" "1 kevin: --to coder: task was sent back 2 times; ask the operator"
assert_eq "a refused send-back records nothing" "$(git rev-parse "swarm/$sb") $(kevin stage "$sb")" "$tip pr-reviewer"
assert_match "the reviewer still hands off forward" "$(kevin handoff "$sb" --to qa)" "pr-reviewer -> qa"
assert_eq "harvest shows each send-back as a handoff from the reviewer to the coder" \
  "$(kevin harvest | jq -c --arg id "$sb" 'select(.id == $id) | [.records[] | select(.type == "handoff" and .from == "pr-reviewer") | .stage]')" '["coder","coder","qa"]'
rr="$(kevin new "Send back across a route change" -m x --route coder,cleaner,pr-reviewer,qa)"
kevin start "$rr" >/dev/null
rr_to_reviewer() { kevin handoff "$rr" >/dev/null; kevin handoff "$rr" >/dev/null; }
rr_to_reviewer; kevin handoff "$rr" --to coder >/dev/null
rr_to_reviewer; kevin handoff "$rr" --to coder >/dev/null
rr_to_reviewer
kevin route "$rr" cleaner,pr-reviewer,qa >/dev/null
tip="$(git rev-parse "swarm/$rr")"
set +e; err="$(kevin handoff "$rr" --to cleaner 2>&1)"; rc=$?; set -e
assert_eq "a route change keeps the send-back count" "$rc $err" "1 kevin: --to cleaner: task was sent back 2 times; ask the operator"
assert_eq "a send-back refused after a route change records nothing" "$(git rev-parse "swarm/$rr") $(kevin stage "$rr")" "$tip pr-reviewer"
assert_match "the reviewer still hands off forward after a route change" "$(kevin handoff "$rr")" "pr-reviewer -> qa"
ss="$(kevin new "Send back to spec" -m x --route specifier,coder,pr-reviewer)"
kevin start "$ss" >/dev/null
git -C ".worktrees/$ss" commit -q --allow-empty -m "Spec"
kevin review "$ss" >/dev/null && kevin approve "$ss" >/dev/null && kevin handoff "$ss" >/dev/null
set +e; err="$(kevin handoff "$ss" --to specifier 2>&1)"; rc=$?; set -e
assert_eq "a send-back to the specifier is refused" "$rc $err" "1 kevin: --to specifier: a task cannot go back to the specifier"
sc="$(kevin new "Unflagged reviewer" -m x --route coder,security-reviewer,qa)"
kevin start "$sc" >/dev/null && kevin handoff "$sc" >/dev/null
set +e; err="$(kevin handoff "$sc" --to coder 2>&1)"; rc=$?; set -e
assert_eq "the security reviewer cannot send back" "$rc $err" "1 kevin: --to coder is not on route 'coder,security-reviewer,qa' at or after stage:security-reviewer"

# audit gate
sed -i 's/^coder     strong   subagent$/coder strong subagent audit/' kevin/roles.conf
assert_match "check accepts the audit flag" "$(kevin check)" "^CHECK: ok"
id5="$(kevin new "Audited" -m "x" --route "coder,qa")"
kevin start "$id5" >/dev/null
git -C ".worktrees/$id5" commit -q --allow-empty -m "Coder work"
head5="$(git -C ".worktrees/$id5" rev-parse --short HEAD)"
set +e; out="$(kevin handoff "$id5")"; rc=$?; set -e
assert_eq "first handoff of an audited role exits 3" "$rc" "3"
assert_match "first handoff prints the checklist" "$out" "^AUDIT_REQUIRED: $id5 at $head5; nothing was recorded"
assert_eq "refused handoff leaves the stage" "$(kevin stage "$id5")" "coder"
assert_match "second handoff at the same commit is recorded" "$(kevin handoff "$id5")" "^HANDOFF: $id5 coder -> qa at $head5$"
assert_eq "audit state is cleared after the record" "$(test -e ".kevin/audit/$id5" && echo kept || echo cleared)" "cleared"
assert_match "unaudited role hands off at once" "$(kevin handoff "$id5")" "^HANDOFF: $id5 qa -> done"
id6="$(kevin new "Audited twice" -m "x" --route "coder")"
kevin start "$id6" >/dev/null
git -C ".worktrees/$id6" commit -q --allow-empty -m "First"
kevin handoff "$id6" >/dev/null || true
git -C ".worktrees/$id6" commit -q --allow-empty -m "Second"
set +e; kevin handoff "$id6" >/dev/null; rc=$?; set -e
assert_eq "a new commit restarts the audit" "$rc" "3"
idm="$(kevin new "Audited missing tool" -m "x" --route "coder,qa")"
kevin start "$idm" >/dev/null
git -C ".worktrees/$idm" commit -q --allow-empty -m "Code" -m "Not-tested: DRY: dry4clj is not installed"
set +e; out="$(kevin handoff "$idm")"; rc=$?; set -e
assert_eq "the missing-tool check runs before the audit gate" "$rc" "4"
assert_match "the missing-tool refusal names the tool" "$out" "^MISSING_TOOL: $idm dry4clj "
assert_eq "a missing-tool refusal leaves no audit state" "$(test -e ".kevin/audit/$idm/coder" && echo kept || echo none)" "none"
# The missing-tool scan reads only the task branch's first-parent chain.
merge_base_with_trailer() {
  git commit -q --allow-empty -m "Base" -m "Not-tested: DRY: dry4clj is not installed"
  git -C ".worktrees/$1" commit -q --allow-empty -m "Code"
  git -C ".worktrees/$1" merge -q --no-ff --no-edit main
}
merge_with_trailer() {
  git commit -q --allow-empty -m "Base"
  git -C ".worktrees/$1" merge -q --no-ff -m "Merge main" -m "Not-tested: DRY: dry4clj is not installed" main
  assert_eq "$2: HEAD is a merge commit" "$(git -C ".worktrees/$1" rev-parse -q --verify HEAD^2 >/dev/null && echo merge || echo single)" "merge"
}
idfp1="$(kevin new "Merged base trailer" -m "x" --route "coder,qa")"
kevin start "$idfp1" >/dev/null
merge_base_with_trailer "$idfp1"
set +e; out="$(kevin handoff "$idfp1")"; rc=$?; set -e
assert_eq "a merged base trailer leaves handoff at the audit gate" "$rc" "3"
assert_match "a merged base trailer gives the audit refusal" "$out" "^AUDIT_REQUIRED: $idfp1 "
assert_eq "a merged base trailer prints no MISSING_TOOL" "$(echo "$out" | grep -c '^MISSING_TOOL' || true)" "0"
idfp2="$(kevin new "Merge commit trailer" -m "x" --route "coder,qa")"
kevin start "$idfp2" >/dev/null
merge_with_trailer "$idfp2" "handoff"
set +e; out="$(kevin handoff "$idfp2")"; rc=$?; set -e
assert_eq "a trailer on the role's merge commit refuses handoff" "$rc" "4"
assert_match "the merge-commit trailer names the tool on handoff" "$out" "^MISSING_TOOL: $idfp2 dry4clj "
idfp3="$(kevin new "Merged base trailer review" -m "x" --route "specifier,coder")"
kevin start "$idfp3" >/dev/null
merge_base_with_trailer "$idfp3"
set +e; out="$(kevin review "$idfp3")"; rc=$?; set -e
assert_eq "a merged base trailer does not refuse review" "$rc" "0"
assert_match "a merged base trailer lets review record" "$out" "^REVIEW: $idfp3 waits for operator approval"
idfp4="$(kevin new "Merge commit trailer review" -m "x" --route "specifier,coder")"
kevin start "$idfp4" >/dev/null
merge_with_trailer "$idfp4" "review"
set +e; out="$(kevin review "$idfp4")"; rc=$?; set -e
assert_eq "a trailer on the role's merge commit refuses review" "$rc" "4"
assert_match "the merge-commit trailer names the tool on review" "$out" "^MISSING_TOOL: $idfp4 dry4clj "
sed -i 's/^specifier frontier subagent$/specifier frontier subagent audit/' kevin/roles.conf
id7="$(kevin new "Audited spec" -m "x" --route "specifier,coder")"
kevin start "$id7" >/dev/null
git -C ".worktrees/$id7" commit -q --allow-empty -m "Spec. By specifier."
set +e; kevin review "$id7" >/dev/null; rc=$?; set -e
assert_eq "first review of an audited specifier exits 3" "$rc" "3"
assert_match "second review is recorded" "$(kevin review "$id7")" "waits for operator approval"
assert_match "approval does not re-gate the audited review" "$(kevin approve "$id7")" "^HANDOFF: $id7 specifier -> coder"
sed -i 's/^specifier frontier subagent audit$/specifier frontier subagent/' kevin/roles.conf
sed -i 's/^coder strong subagent audit$/coder strong subagent bogus/' kevin/roles.conf
assert_fails "check rejects an unknown role flag" kevin check
cp "$HERE/kevin/roles.conf" kevin/roles.conf

# without routes.conf the route is the whole catalogue
rm kevin/routes.conf
printf 'coder strong subagent\ncleaner fast subagent\n' > kevin/roles.conf
assert_eq "roles reads roles.conf" "$(kevin roles | paste -sd ' ' -)" "coder cleaner"
id4="$(kevin new "Tidy" -m "x")"
assert_eq "new uses the first catalogue role" "$(kevin stage "$id4")" "coder"
assert_eq "default route is the catalogue" "$(kevin route "$id4")" "coder,cleaner"

# A computed fallback is a literal role list, never a named-route lookup.
printf 'coder strong subagent\nsecurity-reviewer strong subagent optional\n' > kevin/roles.conf
printf 'coder coder,security-reviewer\n' > kevin/routes.conf
assert_match "check permits a named route sharing the fallback name" "$(kevin check)" "^CHECK: ok"
fallback_id="$(kevin new "Literal fallback" -m x)"
assert_eq "fallback collision cannot opt in security review" "$(kevin route "$fallback_id")" "coder"
explicit_id="$(kevin new "Explicit named review" -m x --route coder)"
assert_eq "explicit colliding named route remains available" "$(kevin route "$explicit_id")" "coder,security-reviewer"

rm kevin/routes.conf

# adapters
printf 'specifier frontier session\ncoder frontier subagent\ncleaner fast subagent\n' > kevin/roles.conf
out="$(kevin adapters)"
assert_eq "adapters only for subagent roles" "$(ls .claude/agents | paste -sd ' ' -)" "cleaner.md coder.md"
assert_eq "adapters for copilot" "$(ls .github/agents | paste -sd ' ' -)" "cleaner.agent.md coder.agent.md"
assert_eq "adapters for codex" "$(ls .codex/agents | paste -sd ' ' -)" "cleaner.toml coder.toml"
assert_eq "adapters for kilo" "$(ls .kilo/agent | paste -sd ' ' -)" "cleaner.md coder.md"
assert_eq "adapters for vibe" "$(ls .vibe/agents | paste -sd ' ' -)" "cleaner.toml coder.toml"
assert_match "claude adapter uses the tier model" "$(cat .claude/agents/cleaner.md)" "^model: sonnet$"
assert_match "frontier adapter inherits the session model" "$(cat .claude/agents/coder.md)" "^model: inherit$"
assert_eq "copilot adapter uses the tier model" \
  "$(sed -n 's/^model: //p' .github/agents/cleaner.agent.md)" "$(awk '$1 == "fast" {print $4}' kevin/tiers.conf)"
assert_match "codex adapter uses the tier effort" "$(cat .codex/agents/cleaner.toml)" '^model_reasoning_effort = "low"$'
assert_match "kilo adapter is a subagent" "$(cat .kilo/agent/cleaner.md)" "^mode: subagent$"
assert_eq "kilo adapter inherits the session model" \
  "$(sed -n 's/^model: //p' .kilo/agent/cleaner.md)" ""
assert_match "vibe adapter is a subagent" "$(cat .vibe/agents/cleaner.toml)" '^agent_type = "subagent"$'
assert_eq "vibe adapter uses the tier model" \
  "$(sed -n 's/^active_model = \"//p' .vibe/agents/cleaner.toml | sed 's/\"$//')" "$(awk '$1 == "fast" {print $6}' kevin/tiers.conf)"

assert_eq "no vibe-plugin tree in an installed project" "$(test ! -e vibe-plugin && echo none)" "none"

# committed adapters are current
work="$(mktemp -d "$scratch/adapters.XXXX")"
cp -R "$HERE/.claude" "$HERE/.github" "$HERE/.codex" "$HERE/.vibe" "$HERE/vibe-plugin" "$work/" \
  && mkdir -p "$work/.kilo" && cp -R "$HERE/.kilo/agent" "$HERE/.kilo/command" "$work/.kilo/" \
  && copy_kevin "$work"
(cd "$work" && git init -q . && git add -A && git commit -q -m seed && ./kevin/bin/kevin adapters >/dev/null && git diff --quiet) \
  && pass "committed adapters match kevin adapters" \
  || fail "committed adapters are stale; run kevin/bin/kevin adapters"
assert_match "plugin subagents carry the plugin schema version" \
  "$(cat "$work/vibe-plugin/ai.mistral.vibe/agents/cleaner.toml")" "^schema_version = 1$"

# configuration check
copy_kevin .
assert_match "check passes on the shipped configuration" "$(kevin check)" "^CHECK: ok \(9 roles, 7 routes\)$"
assert_eq "the shipped configuration has the falsifier gate" "$(kevin gates)" "falsifier"
check_fails() {
  local name="$1" expected="$2" out
  out="$(kevin check 2>&1 || true)"
  assert_match "$name" "$out" "$expected"
  copy_kevin .
}
echo "qa strong subagent" >> kevin/roles.conf
check_fails "check rejects a duplicate role" "CHECK: roles.conf: role 'qa' listed twice"
echo "janitor strong" >> kevin/roles.conf
check_fails "check rejects a short role line" "CHECK: roles.conf: 'janitor' needs <role>"
echo "Big_Role strong subagent" >> kevin/roles.conf
check_fails "check rejects a bad role name" "CHECK: roles.conf: role name 'Big_Role'"
echo "done strong subagent" >> kevin/roles.conf
check_fails "check rejects a reserved role name" "CHECK: roles.conf: 'done' is reserved"
echo "none strong subagent gate" >> kevin/roles.conf
cp kevin/roles/falsifier.md kevin/roles/none.md
check_fails "check rejects none as a reserved role name" "CHECK: roles.conf: 'none' is reserved"
echo "scribe mythic subagent" >> kevin/roles.conf
check_fails "check rejects an unknown tier" "CHECK: roles.conf: 'scribe' uses unknown tier 'mythic'"
echo "scribe fast daemon" >> kevin/roles.conf
check_fails "check rejects an unknown runs-as" "CHECK: roles.conf: 'scribe' runs-as must be"
echo "scribe fast subagent" >> kevin/roles.conf
check_fails "check requires a role prompt" "CHECK: roles/scribe.md: missing role prompt"
echo "slow opus" >> kevin/tiers.conf
check_fails "check rejects a short tier line" "CHECK: tiers.conf: 'slow' needs exactly"
echo "fast haiku low claude-haiku-5.5" >> kevin/tiers.conf
check_fails "check rejects a duplicate tier" "CHECK: tiers.conf: tier 'fast' listed twice"
echo "kevin-cs local kevin/bin/kevin-cs - - - -" >> kevin/tools.conf
check_fails "check rejects a duplicate tool" "CHECK: tools.conf: tool 'kevin-cs' listed twice"
echo "backwards qa,coder" >> kevin/routes.conf
check_fails "check rejects an out-of-order route" "CHECK: routes.conf: backwards: route 'qa,coder' is out of order"
echo "feature specifier" >> kevin/routes.conf
check_fails "check rejects a duplicate route" "CHECK: routes.conf: route 'feature' listed twice"
echo "weird magic github.com/x/y - task - -" >> kevin/tools.conf
check_fails "check rejects an unknown tool kind" "CHECK: tools.conf: 'weird' has unknown kind 'magic'"
echo "qa strong subagent" >> kevin/roles.conf
assert_fails "init refuses a broken configuration" kevin init
assert_fails "adapters refuse a broken configuration" kevin adapters
copy_kevin .
kevin adapters >/dev/null

# Config commands read the checkout they run in; bookkeeping reads main's.
idc="$(kevin new "Config change" -m x --route coder)"
kevin start "$idc" >/dev/null
wt="$repo/.worktrees/$idc"
echo "quick coder" >> "$wt/kevin/routes.conf"
sed -i '/^security-reviewer /d' "$wt/kevin/roles.conf"
assert_match "routes reads the worktree's routes.conf" "$(cd "$wt" && kevin routes)" "^quick +coder$"
assert_eq "routes in main reads main's routes.conf" "$(kevin routes | grep -c '^quick' || true)" "0"
assert_eq "roles reads the worktree's roles.conf" "$(cd "$wt" && kevin roles | grep -c '^security-reviewer$' || true)" "0"
assert_match "check names the worktree's kevin" "$(cd "$wt" && kevin check 2>&1 || true)" "problem\(s\) in .*/\.worktrees/$idc/kevin$"
assert_match "check in main reads main's config" "$(kevin check)" "^CHECK: ok"
git -C "$wt" checkout -q -- kevin/roles.conf
assert_fails "new from a worktree resolves routes from main" sh -c "cd '$wt' && kevin new Y -m x --route quick"
assert_eq "route from a worktree is unchanged" "$(cd "$wt" && kevin route "$idc")" "coder"
cp -R .claude .codex .github "$wt/"
sed -i 's/^cleaner   fast /cleaner   strong /' "$wt/kevin/roles.conf"
sed -i '3s/.*/Cleans up after the coder./' "$wt/kevin/roles/cleaner.md"
(cd "$wt" && kevin adapters >/dev/null) && pass "adapters in a worktree take over main's adapters" || fail "adapters in a worktree"
assert_match "adapters writes the worktree's agents" "$(cat "$wt/.claude/agents/cleaner.md")" "^model: opus$"
assert_match "adapters leaves main's agents" "$(cat .claude/agents/cleaner.md)" "^model: sonnet$"
kevin adapters >/dev/null && pass "adapters in main still succeeds" || fail "adapters in main after a worktree run"
assert_match "main's agents follow main's config" "$(cat .claude/agents/cleaner.md)" "^model: sonnet$"

# Tasks live in their record commits. Finished state is the finished merge in
# HEAD's history, any parent included; only local swarm/* branches are read.
store="$scratch/store"
mkdir -p "$store/remote.git" "$store/project" && cd "$store/project"
git init -q --bare ../remote.git
git init -q . && copy_kevin . && git add -A && git commit -q -m seed
git remote add origin ../remote.git && git push -q origin main
kevin init >/dev/null
done_task() { # <title>: a started task handed off to done with one commit
  local tid; tid="$(kevin new "$1" -m "Request of $1." --route coder)"
  kevin start "$tid" >/dev/null
  git -C ".worktrees/$tid" commit -q --allow-empty -m "Work on $1"
  kevin handoff "$tid" >/dev/null
  echo "$tid"
}

idp="$(done_task "Via PR")"
git push -q origin "swarm/$idp"
git checkout -q -b pr
assert_match "finish into a PR branch" "$(kevin finish "$idp")" "^FINISHED: $idp merged into pr$"
git checkout -q main && git merge -q --no-ff --no-edit pr && git fetch -q origin
assert_match "a finish merged by the host shows as finished" "$(kevin show "$idp")" "^STATUS: finished$"
assert_eq "ls skips a task finished through a PR" "$(kevin ls)" ""
assert_eq "next skips a task finished through a PR" "$(kevin next coder)" "NO_TASK"
assert_match "start refuses a task finished through a PR" "$(kevin start "$idp" 2>&1 || true)" "is finished"
assert_eq "the remote branch is left in place" "$(git rev-parse --verify --quiet "origin/swarm/$idp" >/dev/null && echo kept)" "kept"
assert_eq "no task path is in HEAD" "$(git ls-tree -r --name-only HEAD | grep -c "$idp" || true)" "0"

ids="$(done_task "Squash after")"
git checkout -q -b pr2 && kevin finish "$ids" >/dev/null
git checkout -q main && git merge -q --squash pr2 >/dev/null && git commit -q --allow-empty -m "Squash pr2" && git branch -q -D pr2
assert_eq "ls skips a task squash-merged after finish" "$(kevin ls)" ""
assert_match "show finds no task squash-merged after finish" "$(kevin show "$ids" 2>&1 || true)" "no task '$ids'"
assert_match "start finds no task squash-merged after finish" "$(kevin start "$ids" 2>&1 || true)" "no task '$ids'"

idb="$(done_task "Squash before")"
git merge -q --squash "swarm/$idb" >/dev/null && git commit -q --allow-empty -m "Squash $idb"
assert_match "a task squash-merged before finish is listed at done" "$(kevin ls)" "^$idb	done		coder	none	Squash before$"
assert_match "finish merges a squash-merged task" "$(kevin finish "$idb")" "^FINISHED: $idb merged into main$"
assert_match "the squash-merged task is finished" "$(kevin show "$idb")" "^STATUS: finished$"

idh="$(done_task "Host merge")"
git merge -q --no-ff --no-edit "swarm/$idh" && git commit -q --allow-empty -m "After the host merge"
after="$(git rev-parse HEAD)"
assert_match "finish completes a host merge" "$(kevin finish "$idh")" "^FINISHED: $idh merged into main$"
assert_eq "finish removes the host-merged branch" "$(git branch --list "swarm/$idh")" ""
assert_eq "finish records on top of the commits after a host merge" "$(git rev-parse HEAD^1)" "$after"
assert_match "a task finished after a host merge shows as finished" "$(kevin show "$idh")" "^STATUS: finished$"

idm="$(done_task "Host merge at HEAD")"
git merge -q --no-ff --no-edit "swarm/$idm"
merge="$(git rev-parse HEAD)"
kevin finish "$idm" >/dev/null
assert_eq "finish keeps a host merge at HEAD" "$(git rev-parse HEAD^1)" "$merge"
assert_eq "the finished record merges the task tip" "$(git log -1 --format=%B HEAD | head -n 1)" "kevin finished: $idm"
assert_match "a task host-merged at HEAD shows as finished" "$(kevin show "$idm")" "^STATUS: finished$"

idl="legacy-task-1234567"
git checkout -q -b "swarm/$idl"
mkdir -p tasks && printf -- '---\nid: %s\ncreated: 2026-10-27\n---\n\n# Legacy task\n\nOld request.\n' "$idl" > "tasks/$idl.md"
git add "tasks/$idl.md"
git commit -q --no-verify -m "$(printf 'kevin created: %s\n\nLegacy task\n\nKevin-Task: %s\nKevin-Record: created\nKevin-From: operator\nKevin-Stage: coder\nKevin-Route: coder\nKevin-Needs: none' "$idl" "$idl")"
git checkout -q main
assert_match "ls reads the title of a legacy task" "$(kevin ls)" "^$idl	coder		coder	none	Legacy task$"
assert_eq "a legacy task has no gates" "$(kevin gates "$idl")" ""
assert_match "show reads the request of a legacy task" "$(kevin show "$idl")" "^Old request\.$"
kevin start "$idl" >/dev/null && kevin handoff "$idl" >/dev/null
assert_eq "the handoff to done deletes the legacy task file" "$(git diff-tree --no-commit-id --name-status -r "swarm/$idl")" "D	tasks/$idl.md"
kevin finish "$idl" >/dev/null
assert_eq "a finished legacy task leaves no file" "$(git ls-tree -r --name-only HEAD | grep -c "$idl" || true)" "0"

idt="$(kevin new "$(printf 'Two\nlines')" -m x --route coder)"
assert_match "a title with a line break is listed as one line" "$(kevin ls)" "^$idt	coder		coder	none	Two lines$"
assert_match "new writes å as aa, æ as ae and ø as oe" "$(kevin new "Blåbærsyltetøy" -m x --route coder)" '^blaabaersyltetoey-[0-9a-f]{7}$'
assert_match "new drops the accents of other letters" "$(kevin new "Crème brûlée" -m x --route coder)" '^creme-brulee-[0-9a-f]{7}$'

assert_fails "move-tasks is gone" kevin move-tasks tasks
printf 'tasks-dir somewhere\n' > kevin/project.conf
assert_match "check ignores a leftover project.conf" "$(kevin check)" "^CHECK: ok"
assert_match "ls ignores a leftover project.conf" "$(kevin ls)" "^$idt	"
rm kevin/project.conf
mkdir -p tasks/done && echo archive > tasks/done/old-archive-7654321.md
git add tasks && git commit -q -m "Old archive"
assert_match "an old archive makes no task" "$(kevin show old-archive-7654321 2>&1 || true)" "no task 'old-archive-7654321'"
cd "$repo"

# kevin harvest: one JSON line per task, read-only
hv="$scratch/harvest"
mkdir -p "$hv" && cd "$hv"
git init -q . && copy_kevin . && git add -A && git commit -q -m seed
kevin init >/dev/null
assert_eq "harvest of a repository without tasks prints nothing" "$(kevin harvest)" ""
hf="$(kevin new "Harvest finished" -m "$(printf 'First line of the request.\n\nSecond paragraph, é.')" --route specifier,coder,cleaner)"
kevin start "$hf" >/dev/null
git -C ".worktrees/$hf" commit -q --allow-empty -m "$(printf 'Specify harvest\n\nConstraint: keep it read-only\nTested: a folded\n value\nSupersedes: 0badc0de')"
spec_lore_id="$(git -C ".worktrees/$hf" log -1 --format='%(trailers:key=Lore-id,valueonly)')"
kevin ask "$hf" "Which format?" >/dev/null
kevin answer "$hf" "JSON lines" >/dev/null
kevin review "$hf" -m "spec ready" >/dev/null
kevin reject "$hf" "split the criteria" >/dev/null
kevin review "$hf" -m "spec revised" >/dev/null
kevin approve "$hf" --rejected "DEFECT, LOW: a | b | reason" --rejected "plain finding | accepted" >/dev/null
git -C ".worktrees/$hf" commit -q --allow-empty -m "Code harvest"
git config user.name "Codex Ü"
GIT_AUTHOR_DATE="2026-03-01T10:00:00+02:00" kevin handoff "$hf" -m "coded" >/dev/null
git config --unset user.name
kevin route "$hf" specifier,coder,cleaner,qa >/dev/null
kevin handoff "$hf" >/dev/null
kevin handoff "$hf" >/dev/null
kevin finish "$hf" >/dev/null
git clone -q --depth 3 "file://$hv" "$scratch/harvest-shallow" 2>/dev/null
ho="$(kevin new "Harvest open" -m "Open request." --route coder)"
kevin ask "$ho" "Open question?" >/dev/null
out="$(kevin harvest 2>"$scratch/harvest.err")"
line_f="$(echo "$out" | sed -n 1p)"
line_o="$(echo "$out" | sed -n 2p)"
assert_eq "harvest gives no shallow warning in the full repository" "$(grep -c '^kevin: shallow repository: ' "$scratch/harvest.err" || true)" "0"
record_times() { TZ=UTC git log --first-parent --reverse --all-match --grep='^Kevin-Record: ' --grep="^Kevin-Task: $1\$" \
  --date=format-local:%Y-%m-%dT%H:%M:%SZ --format='%ad %an' "$2"; }
hf_merge="$(git log --merges --format=%H -1 --grep="^Kevin-Task: $hf\$" HEAD)"
assert_eq "harvest gives a finished task's records their commit times and authors" \
  "$(echo "$line_f" | jq -r '.records[] | "\(.time) \(.recorded_by)"')" "$(record_times "$hf" "$hf_merge^2"; record_times "$hf" "$hf_merge" | tail -1)"
assert_eq "harvest gives an open task's records their commit times and authors" \
  "$(echo "$line_o" | jq -r '.records[] | "\(.time) \(.recorded_by)"')" "$(record_times "$ho" "swarm/$ho")"
assert_eq "harvest gives the record time in UTC and the author name unchanged" \
  "$(echo "$line_f" | jq -r '.records[] | select(.text == "coded") | "\(.time) \(.recorded_by)"')" "2026-03-01T08:00:00Z Codex Ü"
assert_eq "harvest ends every record with time and recorded_by" "$(echo "$out" | jq -c '.records[] | keys_unsorted' | sort -u)" \
  '["type","from","stage","needs","route","commit","text","merged_into","rejected","audit_refusals","spec_verdict","time","recorded_by","backend","dispatched"]'
assert_eq "harvest prints the same lines under another time zone" "$(TZ=Asia/Tokyo kevin harvest)" "$out"
assert_eq "harvest prints one line per task" "$(echo "$out" | wc -l | tr -d ' ')" "2"
assert_eq "every harvest line is JSON" "$(echo "$out" | jq -e -c 'type' | sort -u)" '"object"'
assert_eq "harvest prints the finished task first" "$(echo "$out" | jq -r '.id' | paste -sd ' ' -)" "$hf $ho"
assert_eq "harvest keys every line" "$(echo "$out" | jq -c 'keys' | sort -u)" '["fixes","found_by","gates","id","lore","needs","records","request","route","stage","status","title","truncated"]'
for t in "$hf" "$ho"; do
  line="$(echo "$out" | jq -c --arg id "$t" 'select(.id == $id)')"
  assert_eq "harvest title equals kevin show ($t)" "$(echo "$line" | jq -r '.title')" "$(kevin show "$t" | sed -n 's/^TITLE: //p')"
  assert_eq "harvest status equals kevin show ($t)" "$(echo "$line" | jq -r '.status')" "$(kevin show "$t" | sed -n 's/^STATUS: //p')"
  assert_eq "harvest stage equals kevin stage ($t)" "$(echo "$line" | jq -r '.stage')" "$(kevin stage "$t")"
  assert_eq "harvest route equals kevin route ($t)" "$(echo "$line" | jq -r '.route | join(",")')" "$(kevin route "$t")"
  assert_eq "harvest records follow kevin show's blocks ($t)" "$(echo "$line" | jq -r '.records[].type' | paste -sd ' ' -)" \
    "$(kevin show "$t" | sed -n 's/^type: //p' | paste -sd ' ' -)"
  assert_eq "harvest lore follows kevin show's LORE lines but Lore-id ($t)" \
    "$(echo "$line" | jq -r '.lore[] | "LORE: \(.commit) \(.role // "-") \(.key): \(.value)"')" \
    "$(kevin show "$t" | grep '^LORE: ' | grep -v '^LORE: [^ ]* [^ ]* Lore-id: ' || true)"
done
assert_eq "harvest keeps the request" "$(echo "$line_f" | jq -r '.request')" "$(printf 'First line of the request.\n\nSecond paragraph, é.')"
assert_eq "harvest statuses" "$(echo "$out" | jq -r '.status' | paste -sd ' ' -)" "finished open"
assert_eq "harvest needs of the waiting open task equals kevin ls" "$(echo "$line_o" | jq -r '.needs')" "$(kevin ls | awk -F'\t' -v id="$ho" '$1 == id {print $3}')"
assert_eq "harvest needs of the finished task" "$(echo "$line_f" | jq -r '.needs')" "none"
assert_eq "harvest texts" "$(echo "$line_f" | jq -c '[.records[] | select(.text != null) | .text]')" \
  '["Which format?","JSON lines","spec ready","split the criteria","spec revised","specifier work complete","coded","cleaner work complete","qa work complete"]'
assert_eq "harvest gives the route change its new route" "$(echo "$line_f" | jq -c '[.records[] | select(.type == "route") | .route]')" '[["specifier","coder","cleaner","qa"]]'
assert_eq "harvest review records wait for approval" "$(echo "$line_f" | jq -r '[.records[] | select(.type == "review") | .needs] | unique | .[]')" "approval"
assert_eq "harvest gives the finished record its branch" "$(echo "$line_f" | jq -r '.records[-1] | "\(.type) \(.merged_into)"')" "finished main"
assert_eq "harvest commits equal kevin show's" "$(echo "$line_f" | jq -r '.records[] | select(.commit != null) | .commit')" "$(kevin show "$hf" | sed -n 's/^commit: //p')"
assert_eq "harvest parses the approved-over findings" "$(echo "$line_f" | jq -c '.records[] | select(.type == "approval") | .rejected')" \
  '[{"finding":"DEFECT, LOW: a | b","reason":"reason","kind":"DEFECT","severity":"LOW"},{"finding":"plain finding","reason":"accepted","kind":null,"severity":null}]'
assert_eq "harvest gives unaudited records no refusal count" "$(echo "$out" | jq -c '[.records[].audit_refusals] | unique')" "$(printf '[null]\n[null]')"
assert_eq "harvest gives lore the Lore-id of its commit" "$(echo "$line_f" | jq -r --arg id "$spec_lore_id" '[.lore[] | select(.lore_id == $id) | .key] | join(" ")')" "Constraint Tested Supersedes"
assert_eq "harvest unfolds a folded value" "$(echo "$line_f" | jq -r '.lore[] | select(.key == "Tested") | .value')" "a folded value"
assert_eq "harvest gives the approval's Rejected lore role null" "$(echo "$line_f" | jq -c '[.lore[] | select(.key == "Rejected") | .role] | unique')" "[null]"
assert_eq "harvest lines are not truncated in the full repository" "$(echo "$out" | jq -c '.truncated' | sort -u)" "false"
snapshot() { git for-each-ref; git status --porcelain --ignored; git worktree list; find .git | sort; { find .kevin 2>/dev/null || true; } | sort; }
before="$(snapshot)"
kevin harvest >/dev/null
assert_eq "harvest changes no ref, worktree or file" "$(snapshot)" "$before"
assert_eq "a dispatched role can harvest" "$(KEVIN_DISPATCHED=coder kevin harvest)" "$out"
set +e; extra="$(kevin harvest extra 2>"$scratch/harvest.err")"; rc=$?; set -e
assert_eq "harvest refuses an argument" "$rc-$extra" "1-"
assert_match "harvest names the refusal" "$(cat "$scratch/harvest.err")" "^kevin: harvest takes no arguments$"
git branch swarm/experiment
set +e; stray="$(kevin harvest 2>"$scratch/harvest.err")"; rc=$?; set -e
assert_eq "harvest skips a branch without records" "$rc" "0"
assert_eq "harvest still prints the tasks beside a stray branch" "$stray" "$out"
assert_match "harvest names the stray branch" "$(cat "$scratch/harvest.err")" "swarm/experiment"
git branch -D -q swarm/experiment

# audit refusal count
sed -i 's/^coder     strong   subagent$/coder strong subagent audit/; s/^specifier frontier subagent$/specifier frontier subagent audit/' kevin/roles.conf
ia="$(kevin new "Audit count" -m x --route coder,cleaner)"
kevin start "$ia" >/dev/null
git -C ".worktrees/$ia" commit -q --allow-empty -m "X"
set +e; kevin handoff "$ia" --to coder >/dev/null; rc1=$?
git -C ".worktrees/$ia" commit -q --allow-empty -m "Y"
kevin handoff "$ia" --to coder >/dev/null; rc2=$?; set -e
assert_eq "each refusal exits 3" "$rc1 $rc2" "3 3"
kevin handoff "$ia" --to coder >/dev/null
count() { kevin harvest | jq -c --arg id "$1" 'select(.id == $id) | [.records[] | select(.type == "handoff" or .type == "review") | .audit_refusals]'; }
assert_eq "the record counts the refusals since the last record" "$(kevin harvest | jq --arg id "$ia" 'select(.id == $id) | .records[-1].audit_refusals == 2')" "true"
assert_match "show prints the count" "$(kevin show "$ia")" "^audit-refusals: 2$"
assert_match "start prints the count" "$(kevin start "$ia")" "^audit-refusals: 2$"
git -C ".worktrees/$ia" commit -q --allow-empty -m "Z"
set +e; kevin handoff "$ia" >/dev/null; set -e
kevin handoff "$ia" >/dev/null
kevin handoff "$ia" >/dev/null
assert_eq "the count resets at each record; an unaudited role has none" "$(count "$ia")" "[2,1,null]"
is="$(kevin new "Audit spec" -m x --route specifier,coder)"
kevin start "$is" >/dev/null
git -C ".worktrees/$is" commit -q --allow-empty -m "Spec"
set +e; kevin review "$is" >/dev/null; set -e
kevin review "$is" >/dev/null
kevin approve "$is" >/dev/null
assert_eq "the handoff of an approval has no count; the audited review has one" "$(count "$is")" "[1,null]"
roles() { kevin harvest | jq -c --arg id "$1" 'select(.id == $id) | [.records[] | select(.type == "handoff" or .type == "review") | [.from, .audit_refusals]]'; }
# a specifier refused while waiting for approval: one task through kevin approve
spec_waits() {
  local t; t="$(kevin new "$1" -m x --route specifier,coder)"
  kevin start "$t" >/dev/null
  git -C ".worktrees/$t" commit -q --allow-empty -m "Spec"
  set +e; kevin review "$t" >/dev/null; set -e
  kevin review "$t" >/dev/null
  set +e; kevin review "$t" >/dev/null; set -e
  echo "$t"
}
iq1="$(spec_waits "Audit per role 1")"
kevin approve "$iq1" >/dev/null
set +e; out="$(kevin handoff "$iq1")"; rc=$?; set -e
assert_eq "the coder is refused at the specifier's refused head" "$rc" "3"
assert_match "the coder's refusal prints the checklist" "$out" "^AUDIT_REQUIRED: $iq1 at "
kevin handoff "$iq1" >/dev/null
assert_eq "each role is charged its own refusals" "$(roles "$iq1")" '[["specifier",1],["specifier",null],["coder",1]]'
iq2="$(spec_waits "Audit per role 2")"
kevin approve "$iq2" >/dev/null
git -C ".worktrees/$iq2" commit -q --allow-empty -m "Code"
set +e; kevin handoff "$iq2" >/dev/null; set -e
kevin handoff "$iq2" >/dev/null
assert_eq "a refusal the specifier left behind is not the coder's" "$(roles "$iq2" | jq -c '.[-1]')" '["coder",1]'
iq4="$(spec_waits "Audit per role 4")"
kevin reject "$iq4" "again" >/dev/null
git -C ".worktrees/$iq4" commit -q --allow-empty -m "Spec 2"
set +e; kevin review "$iq4" >/dev/null; set -e
kevin review "$iq4" >/dev/null
assert_eq "the left refusal counts toward the specifier's next review" "$(roles "$iq4" | jq -c '.[-1]')" '["specifier",2]'
sed -i 's/^hardener  fast     subagent$/hardener fast subagent audit/' kevin/roles.conf
iq3="$(kevin new "Audit per role 3" -m x --route coder,hardener)"
kevin start "$iq3" >/dev/null
git -C ".worktrees/$iq3" commit -q --allow-empty -m "Code"
set +e; KEVIN_ROLE=coder kevin handoff "$iq3" --to coder >/dev/null; rc1=$?
KEVIN_ROLE=hardener kevin handoff "$iq3" --to coder >/dev/null; rc2=$?
KEVIN_ROLE=hardener kevin handoff "$iq3" --to coder >/dev/null; rc3=$?
KEVIN_ROLE=coder kevin handoff "$iq3" --to coder >/dev/null; rc4=$?; set -e
assert_eq "each audited role gets its own first refusal" "$rc1 $rc2 $rc3 $rc4" "3 3 0 0"
assert_eq "a role's record keeps another role's pending state" "$(roles "$iq3")" '[["hardener",1],["coder",1]]'
assert_eq "no audit state is left once no role is pending" "$(test -e ".kevin/audit/$iq3" && echo kept || echo cleared)" "cleared"
for legacy in 'h\n2\n' 'h\n'; do
  iq5="$(kevin new "Audit roleless $legacy" -m x --route coder)"
  kevin start "$iq5" >/dev/null
  git -C ".worktrees/$iq5" commit -q --allow-empty -m "Code"
  h="$(git -C ".worktrees/$iq5" rev-parse --short HEAD)"
  mkdir -p .kevin/audit && printf "${legacy/h/$h}" > ".kevin/audit/$iq5"
  set +e; kevin handoff "$iq5" >/dev/null; rc1=$?; kevin handoff "$iq5" >/dev/null; rc2=$?; set -e
  assert_eq "a roleless file ($legacy) admits no role" "$rc1 $rc2" "3 0"
  assert_eq "a roleless file ($legacy) adds no refusal" "$(roles "$iq5")" '[["coder",1]]'
  assert_eq "a roleless file ($legacy) is gone after the record" "$(test -e ".kevin/audit/$iq5" && echo kept || echo cleared)" "cleared"
done
cp "$HERE/kevin/roles.conf" kevin/roles.conf

# truncated task in a shallow clone
cd "$scratch/harvest-shallow"
set +e; shallow="$(kevin harvest 2>"$scratch/harvest.err")"; rc=$?; set -e
assert_eq "harvest in a shallow clone exits 0" "$rc" "0"
assert_eq "harvest in a shallow clone warns once" "$(grep -c '^kevin: shallow repository: ' "$scratch/harvest.err")" "1"
assert_eq "harvest in a shallow clone warns first" "$(sed -n 1p "$scratch/harvest.err")" \
  "kevin: shallow repository: older tasks are truncated or missing; run git fetch --unshallow"
assert_eq "the truncated task's records keep their time and recorder" \
  "$(echo "$shallow" | jq '[.records[] | select(.time == null or .recorded_by == null)] | length')" "0"
assert_eq "harvest prints the cut-off task as truncated" "$(echo "$shallow" | jq -c '[.id, .truncated, .title, .request, .status]')" "[\"$hf\",true,\"\",\"\",\"finished\"]"
assert_eq "the truncated task keeps its readable records, finished last" "$(echo "$shallow" | jq -r '[.records[].type] | join(" ")')" "handoff handoff finished"
assert_eq "no commit of the truncated task is blank" "$(echo "$shallow" | jq '[.records[].commit] | index("")')" "null"
cd "$hv"
assert_match "help lists harvest" "$(kevin --help)" "^  harvest  "
assert_match "help lists --allow-missing-tool for handoff" "$(kevin --help)" "^  handoff <id> \[--to ROLE\] \[--allow-missing-tool\] \[-m text\]"
assert_match "help lists --allow-missing-tool for review" "$(kevin --help)" "^  review <id> \[--allow-missing-tool\] \[-m text\]"
cd "$repo"

# spec-judge loop: kevin spec-verdict and kevin spec-revisions through the CLI
copy_kevin .
assert_match "help lists spec-verdict" "$(kevin help)" "^  spec-verdict <id> <converged\|continue\|needs-operator\|stuck> <text>$"
assert_match "help lists spec-revisions" "$(kevin help)" "^  spec-revisions <id> \\[--full\\]  "
assert_eq "help lists no old gate command" "$(kevin help | grep -cE '^  (rounds|verdict) ' || true)" "0"
assert_eq "the old rounds command is unknown" "$(kevin rounds x 2>&1; echo "rc=$?")" "$(printf "kevin: unknown command 'rounds' (try kevin --help)\nrc=1")"
assert_eq "the old verdict command is unknown" "$(kevin verdict x continue y 2>&1; echo "rc=$?")" "$(printf "kevin: unknown command 'verdict' (try kevin --help)\nrc=1")"
assert_match "a route that names the spec-judge is refused" "$(kevin new "Spec-judge route" -m x --route specifier,spec-judge,coder 2>&1 || true)" "'spec-judge' in route 'specifier,spec-judge,coder' is a spec-judge, not a stage"
jq_spec() {
  local t; t="$(kevin new "$1" -m x --route specifier,coder)"
  kevin start "$t" >/dev/null
  git -C ".worktrees/$t" commit -q --allow-empty -m "$(printf 'Spec\n1\n2\n3\n4\n5\n6\n7\n8\n9')"
  kevin review "$t" >/dev/null
  echo "$t"
}
jl="$(jq_spec "Spec-judge loop")"
assert_match "dispatch refuses the spec-judge" "$(kevin dispatch spec-judge "$jl" claude 2>&1 || true)" "^kevin: 'spec-judge' is the spec-judge: it needs the spec revision's gate reports"
assert_match "dispatch refuses the spec-judge on the mistral backend too" "$(kevin dispatch spec-judge "$jl" mistral 2>&1 || true)" "^kevin: 'spec-judge' is the spec-judge"

# backend detection must not depend on the machine's PATH: a copy of PATH
# without the CLI backends, so a developer machine with claude installed runs
# the same cases as CI
cleanbin="$scratch/cleanbin"
mkdir -p "$cleanbin"
oldifs="$IFS"; IFS=:
for d in $PATH; do
  [ -d "$d" ] || continue
  for f in "$d"/*; do
    [ -e "$f" ] || continue
    b="${f##*/}"
    case "$b" in claude|codex|copilot) continue ;; esac
    [ -e "$cleanbin/$b" ] || ln -s "$f" "$cleanbin/$b"
  done
done
IFS="$oldifs"
assert_match "no backend without a CLI or an API key" "$(env -u MISTRAL_API_KEY PATH="$cleanbin" kevin dispatch coder "$jl" 2>&1 || true)" "^kevin: no backend found \(claude, codex, copilot, mistral\)"
assert_match "an explicit mistral backend without a key refuses" "$(env -u MISTRAL_API_KEY PATH="$cleanbin" kevin dispatch coder "$jl" mistral 2>&1 || true)" "^kevin-mistral: MISTRAL_API_KEY is not set"
assert_match "mistral is detected with an API key and no CLI, and refuses without a remote" "$(env PATH="$cleanbin" MISTRAL_API_KEY=dummy kevin dispatch coder "$jl" 2>&1 || true)" "^kevin-mistral: the mistral backend needs a git remote 'origin'"
assert_match "kevin run with the mistral backend starts one pipeline execution" "$(env PATH="$cleanbin" MISTRAL_API_KEY=dummy kevin run "$jl" mistral 2>&1 || true)" "^kevin-mistral: the mistral backend needs a git remote 'origin'"
assert_match "kevin-mistral reads the legacy SWARMFORGE_REMOTE" "$(env PATH="$cleanbin" MISTRAL_API_KEY=dummy SWARMFORGE_REMOTE=old kevin dispatch coder "$jl" mistral 2>&1 || true)" "^kevin-mistral: the mistral backend needs a git remote 'old'"
assert_match "KEVIN_REMOTE wins over the legacy SWARMFORGE_REMOTE" "$(env PATH="$cleanbin" MISTRAL_API_KEY=dummy KEVIN_REMOTE=new SWARMFORGE_REMOTE=old kevin dispatch coder "$jl" mistral 2>&1 || true)" "^kevin-mistral: the mistral backend needs a git remote 'new'"

# kevin run <id> mistral against a loopback stub of the Workflows API: the
# execution reports blocked, so kevin run fetches the branch back and exits 2
git init -q --bare "$scratch/origin.git"
git remote add origin "$scratch/origin.git"
# the worker checks out the base branch, so the remote must have it
git push -q origin HEAD
stub_port="$(bb -e '(import (java.net ServerSocket)) (with-open [s (ServerSocket. 0)] (.getLocalPort s))')"
cat > "$scratch/stub.clj" <<'STUB_EOF'
(require '[org.httpkit.server :as server])
(def port (Integer/parseInt (first *command-line-args*)))
(server/run-server
  (fn [req]
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (if (= :post (:request-method req))
             "{\"execution_id\":\"kevin-stub-1\"}"
             "{\"status\":\"COMPLETED\",\"result\":{\"status\":\"blocked\",\"stage\":\"specifier\",\"needs\":\"approval\",\"reports\":[],\"verdict\":null,\"detail\":\"\"}}")})
  {:port port})
(Thread/sleep 120000)
STUB_EOF
bb "$scratch/stub.clj" "$stub_port" >/dev/null 2>&1 &
stub_pid=$!
for _ in $(seq 1 50); do
  curl -fsS -o /dev/null "http://127.0.0.1:$stub_port/v1/workflows/executions/probe" 2>/dev/null && break
  sleep 0.2
done
set +e
run_out="$(env MISTRAL_API_URL="http://127.0.0.1:$stub_port" MISTRAL_API_KEY=dummy KEVIN_MISTRAL_TIMEOUT_SECONDS=30 kevin run "$jl" mistral 2>&1)"
run_rc=$?
set -e
assert_eq "kevin run with the mistral backend exits 2 when the execution reports blocked" "$run_rc" "2"
assert_match "kevin run with the mistral backend reports the blocked task" "$run_out" "^BLOCKED: $jl needs:approval"
kill "$stub_pid" 2>/dev/null || true
git remote remove origin
assert_eq "spec-verdict continue prints its line" "$(kevin spec-verdict "$jl" continue "r1")" "SPEC-VERDICT: $jl continue"
assert_eq "start shows the send-back with its spec verdict" "$(kevin start "$jl" | grep -A2 '^from: spec-judge$')" "$(printf 'from: spec-judge\nspec-verdict: continue\nreason: r1')"
assert_match "the send-back has the new trailer" "$(git log -1 --format=%B "swarm/$jl")" "^Kevin-Spec-Verdict: continue$"
assert_eq "the send-back has no old trailer" "$(git log -1 --format=%B "swarm/$jl" | grep -c '^SwarmForge-Verdict:' || true)" "0"
git -C ".worktrees/$jl" commit -q --allow-empty -m "Rework"
kevin review "$jl" >/dev/null
kevin spec-verdict "$jl" continue "r2" >/dev/null
git -C ".worktrees/$jl" commit -q --allow-empty -m "Rework again"
kevin review "$jl" >/dev/null
revisions="$(kevin spec-revisions "$jl")"
loop_lines='SPEC-REVISION 1 size:11 growth:0% total:0% spec-verdict:continue
SPEC-REVISION 2 size:13 growth:18% total:18% spec-verdict:continue
SPEC-REVISION 3 size:15 growth:36% total:36% spec-verdict:-'
assert_eq "spec-revisions prints three spec revisions, the first two continued" "$(echo "$revisions" | grep '^SPEC-REVISION' | sed 's/ [0-9a-f]* size:/ size:/')" "$loop_lines"
assert_eq "spec-revisions prints no send-back text" "$(echo "$revisions" | grep -c '^  ' || true)" "0"
assert_match "spec-revisions --full prints each send-back" "$(kevin spec-revisions "$jl" --full)" "^  spec-judge: r2$"
assert_fails "spec-revisions refuses another argument" kevin spec-revisions "$jl" --fuller
assert_match "spec-revisions names itself in its usage" "$(kevin spec-revisions "$jl" --fuller 2>&1 || true)" "spec-revisions takes <id> \[--full\]"
assert_match "spec-revisions prints the cap" "$revisions" "^LIMIT: cap 2 of 2 automatic send-backs used$"
tip="$(git rev-parse "swarm/$jl")"
assert_match "continue past the cap is refused with the limit" "$(kevin spec-verdict "$jl" continue x 2>&1 || true)" "^kevin: LIMIT: cap 2 of 2 automatic send-backs used$"
assert_eq "the refused continue records nothing" "$(git rev-parse "swarm/$jl")" "$tip"
assert_match "a bad spec verdict is refused" "$(kevin spec-verdict "$jl" maybe x 2>&1 || true)" "spec verdict must be converged, continue, needs-operator or stuck, not 'maybe'"
assert_match "a spec verdict needs a reason" "$(kevin spec-verdict "$jl" stuck 2>&1 || true)" "spec verdict needs a reason"
assert_eq "stuck is recorded" "$(kevin spec-verdict "$jl" stuck "cap")" "SPEC-VERDICT: $jl stuck"
assert_match "show prints the spec-verdict record" "$(kevin show "$jl")" "^type: spec-verdict$"
assert_match "the spec-verdict record has the new trailer" "$(git log -1 --format=%B "swarm/$jl")" "^Kevin-Record: spec-verdict$"
assert_match "a second spec verdict in the spec revision is refused" "$(kevin spec-verdict "$jl" converged "x" 2>&1 || true)" "spec revision 3 already has the spec verdict stuck"
assert_match "a dispatched role cannot record a spec verdict" "$(KEVIN_DISPATCHED=spec-judge kevin spec-verdict "$jl" converged "x" 2>&1 || true)" "^kevin: spec-verdict is the operator's; the dispatched spec-judge cannot run it$"
assert_fails "a dispatched role's spec verdict fails" env KEVIN_DISPATCHED=spec-judge kevin spec-verdict "$jl" converged "x"
assert_eq "a dispatched role can read the spec revisions" "$(KEVIN_DISPATCHED=spec-judge kevin spec-revisions "$jl" | grep -c '^SPEC-REVISION')" "3"
assert_eq "harvest gives the spec verdicts" "$(kevin harvest | jq -c --arg id "$jl" 'select(.id == $id) | [.records[] | .spec_verdict]')" \
  '[null,null,"continue",null,"continue",null,"stuck"]'
assert_eq "harvest gives the new record type" "$(kevin harvest | jq -r --arg id "$jl" 'select(.id == $id) | .records[-1].type')" "spec-verdict"
assert_eq "harvest has no verdict key" "$(kevin harvest | jq --arg id "$jl" 'select(.id == $id) | [.records[] | select(has("verdict"))] | length')" "0"
assert_match "approve hands off after a spec verdict" "$(kevin approve "$jl")" "specifier -> coder"
assert_match "spec-verdict at the next stage is refused" "$(kevin spec-verdict "$jl" converged "x" 2>&1 || true)" "spec verdicts are recorded at stage:specifier"
jg="$(jq_spec "Spec-judge growth")"
kevin spec-verdict "$jg" continue "r1" >/dev/null
git -C ".worktrees/$jg" commit -q --allow-empty -m "$(printf 'Rework\n1\n2\n3\n4\n5')"
kevin review "$jg" >/dev/null
assert_match "growth over half the baseline is a limit" "$(kevin spec-revisions "$jg")" "^LIMIT: growth size 18 is more than half over 11$"
assert_match "continue over the growth limit is refused" "$(kevin spec-verdict "$jg" continue x 2>&1 || true)" "^kevin: LIMIT: growth"
jn="$(jq_spec "Spec-judge note")"
kevin spec-verdict "$jn" needs-operator "decide" >/dev/null
kevin reject "$jn" "more" >/dev/null
git -C ".worktrees/$jn" commit -q --allow-empty -m "$(printf 'Rework\n1\n2\n3\n4\n5')"
kevin review "$jn" >/dev/null
assert_eq "spec-revisions totals growth over spec revision 1 across a send-back and warns and notes it" "$(kevin spec-revisions "$jn" | sed 's/ [0-9a-f]* size:/ size:/')" \
  "$(printf 'SPEC-REVISION 1 size:11 growth:0%% total:0%% spec-verdict:needs-operator\nSPEC-REVISION 2 size:18 growth:0%% total:63%% spec-verdict:-\nWARNING: total growth 63%% over spec revision 1 (11); more than half over across operator send-backs\nNOTE: total growth 63%% over spec revision 1 (11); not a limit')"
assert_eq "the WARNING does not refuse continue" "$(kevin spec-verdict "$jn" continue "again")" "SPEC-VERDICT: $jn continue"
jw="$(jq_spec "Spec-judge warn")"
kevin spec-verdict "$jw" needs-operator "decide" >/dev/null
kevin reject "$jw" "more" >/dev/null
git -C ".worktrees/$jw" commit -q --allow-empty -m "$(printf 'Rework\n1\n2\n3\n4\n5')"
kevin review "$jw" >/dev/null
assert_eq "reject prints the WARNING after REJECTED and is not refused" "$(kevin reject "$jw" "back again")" "$(printf 'REJECTED: %s stays at stage:specifier\nWARNING: total growth 63%% over spec revision 1 (11); more than half over across operator send-backs' "$jw")"
assert_eq "a task without a review has no spec revisions" "$(kevin spec-revisions "$(kevin new "Unreviewed" -m x)")" "NO_SPEC_REVISIONS"

# old records: type verdict and SwarmForge-Verdict read as the new ones
old_record() { # <id> <record> <verdict> <text> <needs>: an old-form record on the task branch
  local branch="swarm/$1" head tree commit
  head="$(git rev-parse "$branch")"; tree="$(git rev-parse "$head^{tree}")"
  commit="$(printf 'kevin %s: %s\n\n%s\n\nKevin-Task: %s\nKevin-Record: %s\nKevin-From: judge\nKevin-Stage: specifier\nKevin-Route: specifier,coder\nKevin-Needs: %s\nSwarmForge-Verdict: %s\n' \
    "$2" "$1" "$4" "$1" "$2" "$5" "$3" | git commit-tree "$tree" -p "$head")"
  git update-ref "refs/heads/$branch" "$commit"
}
jo="$(jq_spec "Old loop")"
old_record "$jo" changes-requested continue "o1" none
git -C ".worktrees/$jo" merge -q --ff-only "swarm/$jo"
git -C ".worktrees/$jo" commit -q --allow-empty -m "Rework"
kevin review "$jo" >/dev/null
old_record "$jo" changes-requested continue "o2" none
git -C ".worktrees/$jo" merge -q --ff-only "swarm/$jo"
git -C ".worktrees/$jo" commit -q --allow-empty -m "Rework again"
kevin review "$jo" >/dev/null
old_revisions="$(kevin spec-revisions "$jo")"
assert_eq "old send-backs give the same spec revision lines" "$(echo "$old_revisions" | grep '^SPEC-REVISION' | sed 's/ [0-9a-f]* size:/ size:/')" "$loop_lines"
assert_match "old send-backs count toward the cap" "$old_revisions" "^LIMIT: cap 2 of 2 automatic send-backs used$"
assert_match "continue past the cap of old send-backs is refused" "$(kevin spec-verdict "$jo" continue x 2>&1 || true)" "^kevin: LIMIT: cap 2 of 2"
old_record "$jo" verdict stuck "o3" approval
assert_match "an old verdict record shows as a spec-verdict" "$(kevin show "$jo")" "$(printf '^type: spec-verdict$')"
assert_eq "an old verdict record shows its spec verdict" "$(kevin show "$jo" | grep -A3 '^type: spec-verdict$' | tail -n 3)" "$(printf 'from: judge\nspec-verdict: stuck\nreason: o3')"
assert_match "an old send-back shows its spec verdict" "$(kevin start "$jo")" "$(printf '^spec-verdict: continue$')"
assert_match "an old verdict closes its spec revision" "$(kevin spec-revisions "$jo")" "^SPEC-REVISION 3 [0-9a-f]+ size:15 growth:36% total:36% spec-verdict:stuck$"
assert_match "an old verdict refuses a second one" "$(kevin spec-verdict "$jo" converged x 2>&1 || true)" "spec revision 3 already has the spec verdict stuck"
assert_eq "harvest reads old records as new ones" "$(kevin harvest | jq -c --arg id "$jo" 'select(.id == $id) | [.records[] | select(.spec_verdict) | [.type, .spec_verdict]]')" \
  '[["changes-requested","continue"],["changes-requested","continue"],["spec-verdict","stuck"]]'
assert_eq "kevin does not rewrite old records" "$(git log -1 --format=%B "swarm/$jo" | grep -c '^SwarmForge-Verdict: stuck$')" "1"

sed -i 's/^spec-judge \(.*\) spec-judge$/spec-judge \1 judge/' kevin/roles.conf
assert_eq "check refuses the old judge flag" "$(kevin check 2>&1 | grep -c "roles.conf: 'spec-judge' has unknown flag 'judge' (only audit, optional, gate, spec-judge, send-back)")" "1"
assert_fails "check fails on the old judge flag" kevin check
copy_kevin .
sed -i '/^spec-judge /d' kevin/roles.conf
assert_match "check passes without the spec-judge" "$(kevin check)" "^CHECK: ok"
assert_match "spec-verdict fails without the spec-judge" "$(kevin spec-verdict "$jg" stuck x 2>&1 || true)" "roles.conf has no spec-judge"
copy_kevin .

# backend on records
brepo="$scratch/backend"
mkdir -p "$brepo" && cd "$brepo"
git init -q .
copy_kevin .
echo "x" > README.md
git add -A && git commit -q -m "seed"
kevin init >/dev/null
carried() {
  git log -1 --format='%(trailers:key=Kevin-Backend,valueonly,separator=)|%(trailers:key=Kevin-Dispatched,valueonly,separator=)' "$1"
}
b1="$(env CLAUDECODE=1 kevin new "Backend one" -m x --route specifier,coder)"
assert_eq "a Claude Code session records claude session" "$(carried "swarm/$b1")" "claude|session"
assert_eq "show ends the record with its backend" "$(kevin show "$b1" | grep -v '^$' | tail -1)" "backend: claude session"
env CODEX_THREAD_ID=t1 kevin ask "$b1" "q1" >/dev/null
assert_eq "a Codex session records codex session" "$(carried "swarm/$b1")" "codex|session"
assert_eq "show ends the Codex record with its backend" "$(kevin show "$b1" | grep -v '^$' | tail -1)" "backend: codex session"
env CLAUDECODE=1 CODEX_THREAD_ID=t1 kevin answer "$b1" "a1" >/dev/null
assert_eq "both session markers record nothing" "$(carried "swarm/$b1")" "|"
assert_eq "show prints no backend for ambiguous markers" "$(kevin show "$b1" | grep -v '^$' | tail -1)" "answer: a1"
for thread in '' ' '; do
  env CODEX_THREAD_ID="$thread" CLAUDECODE=1 kevin ask "$b1" "blank Codex marker" >/dev/null
  assert_eq "blank Codex marker preserves a Claude session" "$(carried "swarm/$b1")" "claude|session"
  env CODEX_THREAD_ID="$thread" kevin answer "$b1" "no active marker" >/dev/null
  assert_eq "blank Codex marker alone records nothing" "$(carried "swarm/$b1")" "|"
done
env CLAUDECODE=0 CODEX_THREAD_ID=t1 KEVIN_DISPATCHED=' ' KEVIN_BACKEND=claude kevin ask "$b1" "Codex with inactive Claude marker" >/dev/null
assert_eq "Codex ignores inactive Claude and nondispatched backend values" "$(carried "swarm/$b1")" "codex|session"
assert_eq "start prints the Codex session backend" "$(kevin start "$b1" | grep -v '^$' | tail -1)" "backend: codex session"
kevin answer "$b1" "resume" >/dev/null
env CLAUDECODE=0 kevin ask "$b1" "q2" >/dev/null
assert_eq "CLAUDECODE other than 1 records nothing" "$(carried "swarm/$b1")" "|"
env KEVIN_BACKEND=codex kevin answer "$b1" "a2" >/dev/null
assert_eq "KEVIN_BACKEND outside dispatch records nothing" "$(carried "swarm/$b1")" "|"
env KEVIN_DISPATCHED= CLAUDECODE=1 kevin ask "$b1" "q3" >/dev/null
assert_eq "a blank KEVIN_DISPATCHED is a session" "$(carried "swarm/$b1")" "claude|session"
kevin answer "$b1" "a3" >/dev/null
env KEVIN_DISPATCHED=specifier CLAUDECODE=1 CODEX_THREAD_ID=t1 kevin ask "$b1" "q4" >/dev/null
assert_eq "dispatch without a backend records nothing" "$(carried "swarm/$b1")" "|"
kevin answer "$b1" "a4" >/dev/null
env KEVIN_DISPATCHED=specifier KEVIN_BACKEND=vibe CLAUDECODE=1 CODEX_THREAD_ID=t1 kevin ask "$b1" "q5" >/dev/null
assert_eq "dispatch with an unknown backend records nothing" "$(carried "swarm/$b1")" "|"
kevin answer "$b1" "a5" >/dev/null

for backend in claude codex copilot; do
  env KEVIN_DISPATCHED=specifier KEVIN_BACKEND="$backend" CLAUDECODE=1 CODEX_THREAD_ID=t1 kevin ask "$b1" "dispatched backend" >/dev/null
  assert_eq "dispatch takes precedence over both markers for $backend" "$(carried "swarm/$b1")" "$backend|dispatched"
  kevin answer "$b1" "resume" >/dev/null
done

fakes="$scratch/fake-backends"
mkdir -p "$fakes"
for b in claude codex copilot; do
  printf '#!/usr/bin/env bash
kevin review "$BACKEND_TASK" -m "by %s" >/dev/null
' "$b" > "$fakes/$b"
  chmod +x "$fakes/$b"
done
env BACKEND_TASK="$b1" CLAUDECODE=1 PATH="$fakes:$PATH" kevin dispatch specifier "$b1" codex
assert_eq "a dispatched role records the backend that dispatch runs" "$(carried "swarm/$b1")" "codex|dispatched"
env CLAUDECODE=1 kevin approve "$b1" >/dev/null
assert_eq "the approve handoff carries the review's backend" "$(carried "swarm/$b1")" "codex|dispatched"
assert_eq "the approval carries the operator's backend" "$(carried "swarm/$b1^")" "claude|session"
b2="$(kevin new "Backend two" -m x --route specifier,coder)"
env BACKEND_TASK="$b2" KEVIN_BACKEND=claude PATH="$fakes:$PATH" kevin dispatch specifier "$b2"
assert_eq "dispatch passes on the backend it detects" "$(carried "swarm/$b2")" "claude|dispatched"
b3="$(kevin new "Backend three" -m x --route specifier,coder)"
env CLAUDECODE=1 kevin review "$b3" >/dev/null
kevin approve "$b3" >/dev/null
assert_eq "a clean approve records nothing on the approval" "$(carried "swarm/$b3^")" "|"
assert_eq "a clean approve hands on the review's session" "$(carried "swarm/$b3")" "claude|session"
b4="$(kevin new "Backend four" -m x --route specifier,coder)"
kevin review "$b4" >/dev/null
env CLAUDECODE=1 kevin approve "$b4" >/dev/null
assert_eq "the approve handoff of a review that carries nothing carries nothing" "$(carried "swarm/$b4")" "|"
b5="$(kevin new "Backend five" -m x --route coder)"
kevin start "$b5" >/dev/null
git -C ".worktrees/$b5" commit -q --allow-empty -m "Work"
kevin handoff "$b5" -m done >/dev/null
env CLAUDECODE=1 kevin finish "$b5" >/dev/null
assert_eq "the finished merge carries the backend of kevin finish" "$(carried HEAD)" "claude|session"
bout="$(kevin harvest)"
assert_eq "harvest exports backend and dispatched" \
  "$(echo "$bout" | jq -c --arg id "$b1" 'select(.id == $id) | [.records[] | [.backend, .dispatched]] | .[0], .[1], .[-1]')" \
  "$(printf '["claude",false]\n["codex",false]\n["codex",true]')"
assert_eq "harvest dispatched is null exactly when backend is null" \
  "$(echo "$bout" | jq -c '[.records[] | select((.backend == null) != (.dispatched == null))] | length' | sort -u)" "0"
assert_fails "hook refuses a Kevin-Backend trailer" git -C ".worktrees/$b2" commit -q --allow-empty -m "$(printf 'Forged\n\nKevin-Backend: claude')"
assert_fails "hook refuses a Kevin-Dispatched trailer" git -C ".worktrees/$b2" commit -q --allow-empty -m "$(printf 'Forged\n\nKevin-Dispatched: session')"
cd "$repo"

# gates of a task
ga="$(kevin new "A" -m x --route specifier,coder,qa)"
assert_eq "a task without --gates has no gates" "$(kevin gates "$ga")" ""
assert_eq "start prints GATES: none after ROUTE:" "$(kevin start "$ga" | grep -A1 '^ROUTE: ' | tail -1)" "GATES: none"
assert_eq "show prints GATES: none after ROUTE:" "$(kevin show "$ga" | grep -A1 '^ROUTE: ' | tail -1)" "GATES: none"
assert_match "ls prints the gates between route and title" "$(kevin ls)" "^$ga	specifier		specifier,coder,qa	none	A$"
gb="$(kevin new "B" -m x --gates falsifier)"
assert_eq "new --gates records the gate" "$(kevin gates "$gb")" "falsifier"
assert_eq "start prints the chosen gates" "$(kevin start "$gb" | grep -A1 '^ROUTE: ' | tail -1)" "GATES: falsifier"
assert_eq "show prints the chosen gates" "$(kevin show "$gb" | grep -A1 '^ROUTE: ' | tail -1)" "GATES: falsifier"
assert_match "ls prints the chosen gates" "$(kevin ls)" "^$gb	specifier		$full	falsifier	B$"
assert_eq "new --gates drops a duplicate" "$(kevin gates "$(kevin new "B2" -m x --gates falsifier,falsifier)")" "falsifier"
for value in qa nosuch ""; do
  assert_match "new refuses --gates '$value'" "$(kevin new "C" -m x --gates "$value" 2>&1 && echo created || true)" "^kevin: --gates"
done
assert_eq "a refused --gates makes no task" "$(kevin ls | awk -F'\t' '$6 == "C"' | wc -l | tr -d ' ')" "0"
assert_eq "gates changes the gates of an open task" "$(kevin gates "$ga" falsifier)" "GATES: $ga falsifier"
assert_eq "gates prints the changed gates" "$(kevin gates "$ga")" "falsifier"
assert_match "show prints the gates record" "$(kevin show "$ga")" "type: gates"
assert_match "show prints the gates of the gates record" "$(kevin show "$ga")" "^gates: falsifier$"
assert_eq "a gates change keeps the stage" "$(kevin stage "$ga")" "specifier"
assert_eq "a gates change keeps the route" "$(kevin route "$ga")" "specifier,coder,qa"
assert_eq "gates none clears the gates" "$(kevin gates "$ga" none)" "GATES: $ga none"
tip="$(git rev-parse "swarm/$ga")"
assert_fails "gates refuses a role that is not a gate" kevin gates "$ga" qa
assert_eq "a refused gates change records nothing" "$(git rev-parse "swarm/$ga")" "$tip"
assert_eq "a dispatched gate cannot change the gates" "$(KEVIN_DISPATCHED=falsifier kevin gates "$ga" falsifier 2>&1 || true)" \
  "kevin: gates is the operator's; the dispatched falsifier cannot run it"
assert_eq "the refused gates change records nothing" "$(git rev-parse "swarm/$ga")" "$tip"
assert_eq "a dispatched gate can read the task's gates" "$(KEVIN_DISPATCHED=falsifier kevin gates "$ga")" ""
assert_eq "a dispatched gate can read the gate roles" "$(KEVIN_DISPATCHED=falsifier kevin gates)" "falsifier"
git -C ".worktrees/$ga" commit -q --allow-empty -m "Specify A"
kevin review "$ga" >/dev/null
revisions="$(kevin spec-revisions "$ga")"
kevin gates "$ga" falsifier >/dev/null
assert_match "a gates change keeps the task waiting for approval" "$(kevin ls --needs)" "^$ga	specifier	approval	"
assert_eq "a gates change is not a spec revision" "$(kevin spec-revisions "$ga")" "$revisions"
assert_eq "spec-verdict works after a gates change" "$(kevin spec-verdict "$ga" converged x)" "SPEC-VERDICT: $ga converged"
assert_match "approve works after a gates change" "$(kevin approve "$ga")" "^HANDOFF: $ga specifier -> coder"
assert_eq "the gates outlive the approval" "$(kevin gates "$ga")" "falsifier"
assert_eq "the gate roles stay as roles.conf has them" "$(kevin gates)" "falsifier"
gn="$(kevin new "None" -m x --route coder)"
gout="$(kevin harvest)"
assert_eq "harvest gives the chosen gates" "$(echo "$gout" | jq -c --arg id "$gb" 'select(.id == $id) | .gates')" '["falsifier"]'
assert_eq "harvest gives no gates for none" "$(echo "$gout" | jq -c --arg id "$gn" 'select(.id == $id) | .gates')" '[]'

runfakes="$scratch/run-backends"
mkdir -p "$runfakes"
printf '#!/usr/bin/env bash
printf "%%s\\n" "$*" >> "$RUN_LOG"
case "$*" in *"gate."*) ;; *) kevin review "$BACKEND_TASK" -m spec >/dev/null ;; esac
' > "$runfakes/claude"
chmod +x "$runfakes/claude"
for gates in none falsifier; do
  gr="$(kevin new "Run $gates" -m x --route specifier,coder --gates "$gates")"
  : > "$scratch/run.log"
  out="$(env BACKEND_TASK="$gr" RUN_LOG="$scratch/run.log" PATH="$runfakes:$PATH" kevin run "$gr" claude || true)"
  assert_match "run with gates $gates waits for approval" "$out" "^BLOCKED: $gr needs:approval$"
  assert_eq "run with gates $gates starts the falsifier only when chosen" "$(grep -c "'falsifier' gate" "$scratch/run.log" || true)" \
    "$([ "$gates" = none ] && echo 0 || echo 1)"
done

for pair in ".claude/skills/swarm/SKILL.md .agents/skills/swarm/SKILL.md"; do
  set -- $pair
  cmp -s "$HERE/$1" "$HERE/$2" && pass "$1 matches $2" || fail "$1 differs from $2"
done

# option-like free arguments of new/ask/answer/reject (issue #122)
opt="$(kevin new "Option probe" -m x)"
branches_before="$(git for-each-ref --format='%(refname)' refs/heads/swarm/ | sort)"
ls_before="$(kevin ls)"
needs_before="$(kevin ls --needs)"

new_entry="$(printf '%s\n' \
  '  new <title> [-m text | -F file] [--route NAME|a,b,c] [--gates <gates>]' \
  "      [--fixes <id>|'#N' [--found-by pr-review|ci|operator]]" \
  '                                  create a task on a route; prints its id. <gates> is a' \
  '                                  comma list of gate roles, or none (the default)')"
ask_entry='  ask <id> <text>                 record a question and stop: needs operator'
answer_entry="  answer <id> <text>              answer a role's question and clear needs operator"
reject_entry='  reject <id> <text>              send the specification back to the specifier'

# AC1: exact --help/-h as the title print the new entry, exit 0, no task
for arg in --help -h; do
  rc=0; out="$(kevin new "$arg" 2>"$scratch/err")" || rc=$?
  assert_eq "AC1 new $arg exits 0" "$rc" "0"
  assert_eq "AC1 new $arg prints exactly the new entry" "$out" "$new_entry"
  assert_eq "AC1 new $arg keeps stderr empty" "$(cat "$scratch/err")" ""
done
assert_eq "AC1 makes no task branch" "$(git for-each-ref --format='%(refname)' refs/heads/swarm/ | sort)" "$branches_before"
assert_eq "AC1 leaves kevin ls unchanged" "$(kevin ls)" "$ls_before"

# AC2: exact --help/-h as the text print the command's own line, exit 0, no record
ac2() {
  local cmd="$1" entry="$2" arg show0 rc out
  for arg in --help -h; do
    show0="$(kevin show "$opt")"
    rc=0; out="$(kevin "$cmd" "$opt" "$arg" 2>"$scratch/err")" || rc=$?
    assert_eq "AC2 $cmd <id> $arg exits 0" "$rc" "0"
    assert_eq "AC2 $cmd <id> $arg prints exactly its usage line" "$out" "$entry"
    assert_eq "AC2 $cmd <id> $arg keeps stderr empty" "$(cat "$scratch/err")" ""
    assert_eq "AC2 $cmd <id> $arg appends no record" "$(kevin show "$opt")" "$show0"
  done
}
ac2 ask "$ask_entry"
ac2 answer "$answer_entry"
ac2 reject "$reject_entry"
assert_eq "AC2 leaves the needs column unchanged" "$(kevin ls --needs)" "$needs_before"

# AC3: every other '-'-prefixed title is refused before option parsing
for t in -x -- --bogus; do
  rc=0; err="$(kevin new "$t" 2>&1)" || rc=$?
  assert_eq "AC3 new $t exits 1" "$rc" "1"
  assert_eq "AC3 new $t refuses the title" "$err" "kevin: new needs a title that does not start with '-'"
done
rc=0; err="$(kevin new -m body 2>&1)" || rc=$?
assert_eq "AC3 new -m body exits 1" "$rc" "1"
assert_eq "AC3 new -m body refuses the title" "$err" "kevin: new needs a title that does not start with '-'"
assert_eq "AC3 makes no task branch" "$(git for-each-ref --format='%(refname)' refs/heads/swarm/ | sort)" "$branches_before"

# AC4: every other '-'-prefixed text of ask/answer/reject is refused, nothing recorded
ac4() {
  local cmd="$1" msg="$2" text show0 rc err
  for text in "-x" "--help extra"; do
    show0="$(kevin show "$opt")"
    rc=0; err="$(kevin "$cmd" "$opt" "$text" 2>&1)" || rc=$?
    assert_eq "AC4 $cmd <id> '$text' exits 1" "$rc" "1"
    assert_eq "AC4 $cmd <id> '$text' refuses the text" "$err" "kevin: $msg"
    assert_eq "AC4 $cmd <id> '$text' appends no record" "$(kevin show "$opt")" "$show0"
  done
}
ac4 ask "ask needs a question that does not start with '-'"
ac4 answer "answer needs text that does not start with '-'"
ac4 reject "reject needs a reason that does not start with '-'"

# AC5: whitespace cannot bypass
rc=0; out="$(kevin new " --help " 2>"$scratch/err")" || rc=$?
assert_eq "AC5 new ' --help ' exits 0" "$rc" "0"
assert_eq "AC5 new ' --help ' prints the new entry" "$out" "$new_entry"
rc=0; err="$(kevin new " -x " 2>&1)" || rc=$?
assert_eq "AC5 new ' -x ' exits 1" "$rc" "1"
assert_eq "AC5 new ' -x ' refuses the title" "$err" "kevin: new needs a title that does not start with '-'"
show0="$(kevin show "$opt")"
rc=0; out="$(kevin ask "$opt" " --help " 2>"$scratch/err")" || rc=$?
assert_eq "AC5 ask <id> ' --help ' exits 0" "$rc" "0"
assert_eq "AC5 ask <id> ' --help ' prints the ask line" "$out" "$ask_entry"
assert_eq "AC5 ask <id> ' --help ' appends no record" "$(kevin show "$opt")" "$show0"

# AC6: the id resolves first; the text check precedes every state check
rc=0; err="$(kevin ask --help 2>&1)" || rc=$?
assert_eq "AC6 ask --help exits 1" "$rc" "1"
assert_eq "AC6 ask --help fails id resolution" "$err" "kevin: no task '--help'"
show0="$(kevin show "$opt")"
rc=0; out="$(kevin reject "$opt" --help 2>"$scratch/err")" || rc=$?
assert_eq "AC6 reject <id> --help on a task not awaiting approval exits 0" "$rc" "0"
assert_eq "AC6 reject <id> --help prints the reject line" "$out" "$reject_entry"
assert_eq "AC6 reject <id> --help records nothing" "$(kevin show "$opt")" "$show0"
rc=0; err="$(kevin reject "$opt" -x 2>&1)" || rc=$?
assert_eq "AC6 reject <id> -x fails with the text refusal" "$err" "kevin: reject needs a reason that does not start with '-'"

# AC7: unchanged behavior around the new checks
id7="$(kevin new "Real title" -m x)"
assert_eq "AC7 new with a real title creates the branch" "$(git branch --list "swarm/$id7" | tr -d ' *')" "swarm/$id7"
assert_match "AC7 ask records normal text" "$(kevin ask "$opt" "why?")" "waits for the operator"
assert_match "AC7 answer records text with a flag inside" "$(kevin answer "$opt" "see --gates")" "resumes at stage:"
rc=0; err="$(kevin new 2>&1)" || rc=$?
assert_eq "AC7 new without a title exits 1" "$rc" "1"
assert_eq "AC7 new without a title message" "$err" "kevin: new needs a title"
rc=0; err="$(kevin ask "$opt" 2>&1)" || rc=$?
assert_eq "AC7 ask without text exits 1" "$rc" "1"
assert_eq "AC7 ask without text message" "$err" "kevin: ask needs a question"
for h in --help -h help; do
  rc=0; out="$(kevin $h)" || rc=$?
  assert_eq "AC7 kevin $h exits 0" "$rc" "0"
  assert_match "AC7 kevin $h prints the full usage" "$out" '^usage: kevin <command> \[args\]$'
done
rc=0; out="$(kevin)" || rc=$?
assert_match "AC7 bare kevin prints the full usage" "$out" '^usage: kevin <command> \[args\]$'
rc=0; err="$(kevin next --help 2>&1)" || rc=$?
assert_match "AC7 next --help keeps its refusal" "$err" "unknown role '--help'"
rc=0; err="$(kevin ls --help 2>&1)" || rc=$?
assert_eq "AC7 ls --help keeps its refusal" "$err" "kevin: unknown ls option '--help'"
rc=0; err="$(kevin show --help 2>&1)" || rc=$?
assert_eq "AC7 show --help keeps its refusal" "$err" "kevin: no task '--help'"

if [ "$failures" -gt 0 ]; then echo "$failures failure(s)" >&2; exit 1; fi
echo "all tests passed"
