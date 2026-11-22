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
cp -R "$HERE/kevin" kevin && rm -f kevin/verify.conf && cp -R "$HERE/.claude-plugin" .claude-plugin
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

# A project that uses the user-wide runtime: kevin on PATH through the link
# install.sh --user writes, no kevin/ of its own but kevin/project.md.
plugin="$scratch/plugin"
mkdir -p "$plugin"
(cd "$HERE" && git ls-files --cached --others --exclude-standard -- install.sh kevin .claude-plugin \
  | tar -cf - -T -) | tar -C "$plugin" -xf -
bash "$plugin/install.sh" --user >/dev/null
consumer="$scratch/consumer"
git init -q "$consumer"
git -C "$consumer" commit -q --allow-empty -m seed
user_kevin() { (cd "$consumer" && PATH="$HOME/.local/bin:$PATH" "$@"); }
assert_match "a project with no kevin/ checks the runtime's configuration" "$(user_kevin kevin check)" '^CHECK: ok'
assert_match "init in a project with no kevin/ is repeatable" "$(user_kevin kevin init >/dev/null && user_kevin kevin init)" '^roles: '
assert_eq "init writes only kevin/project.md" "$(git -C "$consumer" status --porcelain --untracked-files=all)" "?? kevin/project.md"
assert_eq "the compatibility entrypoint lists the runtime's tools" \
  "$(user_kevin "$plugin/kevin/bin/kevin-tool" list)" "$(user_kevin kevin tool list)"
for name in cs fs vb js rb java; do
  (user_kevin kevin "$name" --help >/dev/null 2>&1 || [ "$name" = java ]) \
    && pass "kevin $name loads from the runtime" || fail "kevin $name cannot load"
done
mkdir -p "$consumer/tmp/jscpd"
cp "$HERE/kevin/tools/csharp/fixtures/jscpd-report.json" "$consumer/tmp/jscpd/jscpd-report.json"
for wrapper in kevin-cs kevin-fs kevin-vb kevin-js kevin-rb; do
  user_kevin kevin tool ensure "$wrapper" >/dev/null
  grep -qF "exec \"\$('$HOME/.local/bin/kevin' home)/../\"'kevin/bin/$wrapper' " "$consumer/.kevin/bin/$wrapper" \
    && pass "$wrapper wrapper runs the runtime through kevin home" || fail "$wrapper wrapper does not run the runtime through kevin home"
  status=0
  out="$(cd "$consumer" && ".kevin/bin/$wrapper" dry --all --report-only)" || status=$?
  assert_eq "$wrapper reports clone findings" "$status" 1
  assert_match "$wrapper reads reports through shared runtime code" "$out" '3 of 3 clone'
done
for name in fs vb; do
  status=0
  user_kevin kevin "$name" mutate >/dev/null 2>&1 || status=$?
  assert_eq "kevin $name mutation fails explicitly" "$status" 2
done
status=0
user_kevin kevin rb crap --complexity skunk >/dev/null 2>&1 || status=$?
assert_eq "kevin rb refuses an unknown complexity source" "$status" 2

if [ "$failures" -gt 0 ]; then echo "$failures failure(s)" >&2; exit 1; fi
echo "all tests passed"
