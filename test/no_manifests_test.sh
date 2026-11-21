#!/usr/bin/env bash
# Behavioral tests for test/no_manifests.sh in a scratch repository.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected '$3', got '$2'"; fi; }

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export HOME="$scratch/home"; mkdir -p "$HOME"
git config --global user.name tester
git config --global user.email tester@example.com

repo="$scratch/project"
mkdir -p "$repo/features" && cd "$repo"
git init -q .
echo ".metrics/" > .gitignore
echo "Feature: x" > features/x.feature
git add -A && git commit -q -m seed

# guard [dir]: runs the guard and sets status, out and err.
guard() {
  status=0
  (cd "${1:-.}" && bash "$HERE/test/no_manifests.sh") >"$scratch/out" 2>"$scratch/err" || status=$?
  out="$(cat "$scratch/out")"; err="$(cat "$scratch/err")"
}

guard
assert_eq "a clean repository passes" "$status:$out:$err" "0::"

printf '# mutation-stamp: sha256=ab\n\nFeature: x\n' > features/x.feature
guard
assert_eq "an unstaged block passes" "$status:$err" "0:"

git add features/x.feature
guard features
assert_eq "a staged block fails from a subdirectory" "$status" "1"
assert_eq "a staged block is named from the root" "$err" "mutation manifest block in a tracked feature file: features/x.feature"
assert_eq "a block report leaves stdout empty" "$out" ""

printf 'Feature: x\n  Given # acceptance-mutation-manifest-begin\n' > features/x.feature
printf '# acceptance-mutation-manifest-begin\n' > notes.md
git add -A && git commit -q -m "quoted markers"
guard
assert_eq "markers inside a line or outside .feature files pass" "$status:$err" "0:"

printf 'Feature: z\n# acceptance-mutation-manifest-begin\n' > z.feature
mkdir -p .metrics tools/.metrics metrics && echo x > .metrics/a.edn && echo x > tools/.metrics/b.edn && echo x > metrics/c.edn
git add -f z.feature .metrics tools metrics
guard
assert_eq "every manifest is reported, blocks first" "$status:$err" "1:mutation manifest block in a tracked feature file: z.feature
tracked file under .metrics/: .metrics/a.edn
tracked file under .metrics/: tools/.metrics/b.edn"

git reset -q
rm -rf z.feature .metrics tools metrics
guard
assert_eq "the index back at HEAD passes again" "$status:$out:$err" "0::"

mkdir -p kevin/tools/kevin/target/coverage target/coverage kevin/tools/kevin/target/classes kevin/tools/kevin/target/coverage-old
echo x > kevin/tools/kevin/target/coverage/index.html
echo x > target/coverage/lcov.info
echo x > kevin/tools/kevin/target/classes/x.class
echo x > kevin/tools/kevin/target/coverage-old/y
git add -f kevin/tools/kevin/target target/coverage
guard
assert_eq "tracked files under target/coverage/ fail" "$status" "1"
assert_eq "each coverage file is named on stderr, other target/ output is not" "$err" "tracked coverage output under target/coverage/: kevin/tools/kevin/target/coverage/index.html
tracked coverage output under target/coverage/: target/coverage/lcov.info"
assert_eq "a coverage report leaves stdout empty" "$out" ""

git reset -q
rm -rf kevin target
mkdir -p kevin/tools/kevin/target
echo x > kevin/tools/kevin/target/coverage
git add -f kevin
guard
assert_eq "a bare file named target/coverage passes" "$status:$out:$err" "0::"

git reset -q
rm -rf kevin
mkdir -p a/target/coverage .metrics
echo x > a/target/coverage/z
echo x > .metrics/m.edn
printf '# mutation-stamp: sha256=cd\n' > m.feature
git add -f a .metrics m.feature
guard
assert_eq "blocks, then .metrics/, then coverage output" "$status:$err" "1:mutation manifest block in a tracked feature file: m.feature
tracked file under .metrics/: .metrics/m.edn
tracked coverage output under target/coverage/: a/target/coverage/z"

echo
if [ "$failures" -eq 0 ]; then echo "all no_manifests tests passed"; else echo "$failures failure(s)" >&2; exit 1; fi
