#!/usr/bin/env bash
# Behavioral tests for `kevin tool`, using a local tool source and a fake bb.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected '$3', got '$2'"; fi; }
assert_match() { if grep -Eq "$3" <<<"$2"; then pass "$1"; else fail "$1: '$2' does not match /$3/"; fi; }
assert_fails() { local name="$1"; shift; if "$@" >/dev/null 2>&1; then fail "$name should fail"; else pass "$name fails"; fi; }

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export HOME="$scratch/home"; mkdir -p "$HOME"
git config --global user.name tester
git config --global user.email tester@example.com

repo="$scratch/project"
mkdir -p "$repo" && cd "$repo"
git init -q .
cp -R "$HERE/kevin" kevin && rm -f kevin/verify.conf
git add -A && git commit -q -m seed

fake="$scratch/fakebin"; mkdir -p "$fake"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@"\n' > "$fake/bb"; chmod +x "$fake/bb"
export PATH="$repo/kevin/bin:$PATH"
# Wrappers run the fake bb; kevin itself needs the real one.
fake_bb() { PATH="$fake:$PATH" "$@"; }
src="$scratch/tool-src"; mkdir -p "$src"; echo '{:tasks {mutate {} gherkin-mutator {}}}' > "$src/bb.edn"
export KEVIN_TOOL_SRC="$src"

assert_match "list prints the catalogue with its refs" "$(kevin tool list)" "^clj-mutate +bb +github.com/unclebob/clj-mutate +[0-9a-f]{40}$"
assert_eq "bin names the wrapper directory" "$(kevin tool bin)" "$repo/.kevin/bin"
assert_fails "unknown tool" kevin tool require nosuchtool
out="$(kevin tool require clj-mutate || true)"
assert_match "require reports the first missing dependency" "$out" "^MISSING: cloverage$"

out="$(kevin tool ensure clj-mutate)"
assert_match "ensure installs the dependency first at its pinned version" "$out" "^INSTALLED: cloverage .*/.kevin/bin/cloverage @[0-9][^ ]*$"
assert_match "ensure installs the tool from the unpinned override" "$out" "INSTALLED: clj-mutate .*/.kevin/bin/clj-mutate$"
assert_match "require passes after ensure" "$(kevin tool require clj-mutate)" "^OK: clj-mutate $repo/.kevin/bin/clj-mutate$"
assert_match "cloverage wrapper runs the Clojure CLI" "$(head -2 .kevin/bin/cloverage | tail -1)" "^exec clojure -Sdeps .* -M -m 'cloverage.coverage' -p src -s spec -s test -r speclj"

run="$(fake_bb .kevin/bin/clj-mutate --mutate-all --max-workers 9 src/a.clj | paste -sd ' ' -)"
assert_eq "mutate wrapper drops --mutate-all and forces four workers" "$run" "--config $src/bb.edn mutate src/a.clj --max-workers 4"
run="$(fake_bb .kevin/bin/clj-mutate --scan src/a.clj | paste -sd ' ' -)"
assert_eq "mutate wrapper leaves a scan alone" "$run" "--config $src/bb.edn mutate --scan src/a.clj"

kevin tool ensure gherkin-mutator >/dev/null
run="$(fake_bb .kevin/bin/gherkin-mutator --level full --workers 8 features | paste -sd ' ' -)"
assert_eq "gherkin wrapper caps the level and workers" "$run" "--config $src/bb.edn gherkin-mutator --level hard features --workers 4"
run="$(fake_bb .kevin/bin/gherkin-mutator --level soft features | paste -sd ' ' -)"
assert_eq "gherkin wrapper keeps soft" "$run" "--config $src/bb.edn gherkin-mutator --level soft features --workers 4"

kevin tool ensure kevin-cs >/dev/null
assert_match "local wrapper execs the repository script" "$(tail -1 .kevin/bin/kevin-cs)" "^exec '$repo/kevin/bin/kevin-cs' "
assert_match "wrappers run from a task worktree too" "$(git worktree add -q "$scratch/wt" -b wt && cd "$scratch/wt" && kevin tool require kevin-cs)" "^OK: kevin-cs $repo/.kevin/bin/kevin-cs$"

consumer="$scratch/consumer"
git init -q "$consumer"
bash "$HERE/install.sh" "$consumer" >/dev/null
assert_match "fresh install validates its runtime configuration" "$(cd "$consumer" && kevin/bin/kevin check)" '^CHECK: ok'
assert_match "installed init is repeatable" "$(cd "$consumer" && kevin/bin/kevin init)" '^roles: '
assert_eq "installed compatibility entrypoint lists the same tools" \
  "$(cd "$consumer" && kevin/bin/kevin-tool list)" "$(cd "$consumer" && kevin/bin/kevin tool list)"
for wrapper in kevin kevin-tool kevin-cs kevin-fs kevin-vb kevin-js kevin-rb kevin-java; do
  (cd "$consumer" && "kevin/bin/$wrapper" --help >/dev/null) \
    && pass "installed $wrapper exposes help" || fail "installed $wrapper cannot load"
done
development="$(find "$consumer/kevin/tools" -type d \( -name spec -o -name test -o -name property -o -name fixtures -o -name features -o -name acceptance \))"
assert_eq "fresh install excludes development asset directories" "$development" ""
assert_fails "fresh install excludes the developer-only kevin task config" test -e "$consumer/kevin/tools/kevin/bb.edn"
assert_fails "fresh install excludes this repository's verify.conf" test -e "$consumer/kevin/verify.conf"
assert_fails "fresh install ships no project.conf" test -e "$consumer/kevin/project.conf"
printf 'tasks-dir kevin/tasks\n' > "$consumer/kevin/project.conf"
bash "$HERE/install.sh" "$consumer" >/dev/null
assert_eq "reinstall leaves an existing project.conf alone" "$(cat "$consumer/kevin/project.conf")" "tasks-dir kevin/tasks"
linked="$scratch/linked"
git init -q "$linked"
printf '# Rules\n' > "$linked/CLAUDE.md" && ln -s CLAUDE.md "$linked/AGENTS.md"
bash "$HERE/install.sh" "$linked" >/dev/null
assert_eq "install into a project whose AGENTS.md links to CLAUDE.md adds no self-import" \
  "$(grep -c '^@AGENTS.md$' "$linked/CLAUDE.md" || true)" "0"
assert_eq "install into a linked AGENTS.md still adds the orchestrator line" \
  "$(grep -c '^Follow kevin/README.md' "$linked/CLAUDE.md")" "1"
assert_eq "install into an unlinked project imports AGENTS.md from CLAUDE.md" \
  "$(grep -c '^@AGENTS.md$' "$consumer/CLAUDE.md")" "1"
test -f "$HERE/kevin/tools/kevin/bb.edn" && test -d "$HERE/kevin/tools/javascript/fixtures" \
  && pass "installation leaves source development assets in place" || fail "source development assets changed"
mkdir -p "$consumer/tmp/jscpd"
cp "$HERE/kevin/tools/csharp/fixtures/jscpd-report.json" "$consumer/tmp/jscpd/jscpd-report.json"
for wrapper in kevin-cs kevin-fs kevin-vb kevin-js kevin-rb; do
  (cd "$consumer" && kevin/bin/kevin-tool ensure "$wrapper" >/dev/null)
  status=0
  out="$(cd "$consumer" && ".kevin/bin/$wrapper" dry --all --report-only)" || status=$?
  assert_eq "installed $wrapper reports clone findings" "$status" 1
  assert_match "installed $wrapper reads reports through shared runtime code" "$out" '3 of 3 clone'
done
for wrapper in kevin-fs kevin-vb; do
  status=0
  (cd "$consumer" && "kevin/bin/$wrapper" mutate >/dev/null 2>&1) || status=$?
  assert_eq "installed $wrapper mutation fails explicitly" "$status" 2
done
test -f "$consumer/kevin/languages/vbnet.md" && pass "installed VB.NET guide" || fail "installed VB.NET guide missing"
test -f "$consumer/kevin/languages/ruby.md" && pass "installed Ruby guide" || fail "installed Ruby guide missing"
for resource in simplecov.rb flay_report.rb; do
  test -f "$consumer/kevin/tools/ruby/resources/kevinrb/$resource" \
    && pass "installed kevin-rb resource $resource" || fail "installed kevin-rb resource $resource missing"
done
status=0
(cd "$consumer" && kevin/bin/kevin-rb crap --complexity skunk >/dev/null 2>&1) || status=$?
assert_eq "installed kevin-rb refuses an unknown complexity source" "$status" 2
(cd "$consumer/kevin/tools/csharp/resources" && test -f smell.globalconfig && test -f smell.targets && test -f fsharplint.json) \
  && pass "installed .NET code-smell configs" || fail "installed .NET code-smell configs missing"
mkdir -p "$consumer/kevin/tools/kevin/spec" "$consumer/.agents/skills/project"
echo owned > "$consumer/kevin/tools/kevin/spec/project.clj"
echo owned > "$consumer/.agents/skills/project/SKILL.md"
for path in .claude/agents/project.md .github/agents/project.agent.md .codex/agents/project.toml; do
  echo owned > "$consumer/$path"
done
bash "$HERE/install.sh" "$consumer" >/dev/null
for path in kevin/tools/kevin/spec/project.clj .agents/skills/project/SKILL.md \
  .claude/agents/project.md .github/agents/project.agent.md .codex/agents/project.toml; do
  assert_eq "reinstall preserves consumer file $path" "$(cat "$consumer/$path")" owned
done
assert_eq "reinstall leaves one ignore entry" "$(grep -cxF .metrics/ "$consumer/.gitignore")" 1

for rule in '[Bb]in/' 'bin/' 'bin' '**/[Bb]in/*' '**/bin/**'; do
  dotnet="$scratch/dotnet-$RANDOM"
  git init -q "$dotnet"
  printf '%s\n' "$rule" > "$dotnet/.gitignore"
  mkdir -p "$dotnet/src/App/bin" && echo x > "$dotnet/src/App/bin/App.dll"
  bash "$HERE/install.sh" "$dotnet" >/dev/null
  assert_fails "git check-ignore of kevin/bin/kevin after install under '$rule'" git -C "$dotnet" check-ignore -q kevin/bin/kevin
  git -C "$dotnet" check-ignore -q src/App/bin/App.dll \
    && pass "install under '$rule' keeps other bin directories ignored" || fail "install under '$rule' unignored src/App/bin"
  git -C "$dotnet" check-ignore -q kevin/tools/kevin/bin/out \
    && pass "install under '$rule' keeps bin directories nested in kevin ignored" || fail "install under '$rule' unignored kevin/tools/kevin/bin"
  assert_eq "install under '$rule' adds no negation to the root .gitignore" "$(grep -c '^!' "$dotnet/.gitignore")" 0
done
echo scratch/ > "$dotnet/kevin/.gitignore"
bash "$HERE/install.sh" "$dotnet" >/dev/null
bash "$HERE/install.sh" "$dotnet" >/dev/null
assert_eq "reinstall keeps kevin/.gitignore lines once" "$(sort "$dotnet/kevin/.gitignore" | uniq -d)" ""
assert_eq "reinstall keeps existing kevin/.gitignore lines" "$(grep -cxF scratch/ "$dotnet/kevin/.gitignore")" 1

if [ "$failures" -gt 0 ]; then echo "$failures failure(s)" >&2; exit 1; fi
echo "all tests passed"
