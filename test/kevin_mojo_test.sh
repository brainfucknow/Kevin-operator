#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
kevin="$root/kevin/bin/kevin"
mkdir -p "$root/tmp"
scratch="$(mktemp -d "$root/tmp/mojo-test.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
git init -qb main
mkdir -p bin 'test/sub dir' 'import dir' src
export PATH="$scratch/bin:$PATH"
export MOJO_CALLS="$scratch/calls"
export MOJO_STATUS=0
cat > bin/mojo <<'TOOL'
#!/usr/bin/env bash
if [[ "$1" == --version ]]; then echo 'Mojo test SDK'; exit 0; fi
printf '[%s]' "$@" >> "$MOJO_CALLS"
printf '\n' >> "$MOJO_CALLS"
exit "$MOJO_STATUS"
TOOL
chmod +x bin/mojo
touch test/test_z.mojo 'test/sub dir/test_a.mojo' test/property_p.mojo
check() {
    local expected="$1" status=0
    shift
    "$kevin" "$@" > output 2>&1 || status=$?
    if [[ "$status" != "$expected" ]]; then
        cat output >&2
        echo "Expected exit $expected from kevin $*, got $status" >&2
        exit 1
    fi
}
check 0 --help
grep -qw mojo output || { echo 'Top-level help omits mojo' >&2; exit 1; }
check 0 mojo tools
grep -q 'Mojo test SDK' output
check 0 mojo test
sed "s|$scratch/||g" calls > actual
diff -u - actual <<'CALLS'
[run][-j][4][test/sub dir/test_a.mojo]
[run][-j][4][test/test_z.mojo]
CALLS
: > calls
check 0 mojo property
sed "s|$scratch/||g" calls > actual
diff -u - actual <<'CALLS'
[run][-j][4][test/property_p.mojo]
CALLS
: > calls
check 0 mojo test -I 'import dir' -I src 'test/sub dir/test_a.mojo' -- --only 'case name'
sed "s|$scratch/||g" calls > actual
grep -Fxq '[run][-j][4][-I][import dir][-I][src][test/sub dir/test_a.mojo][--only][case name]' actual
MOJO_STATUS=17 check 17 mojo test test/test_z.mojo
check 2 mojo test -- --only case
check 2 mojo property test/test_z.mojo
check 2 mojo coverage --source absent
check 2 mojo crap
grep -qi unsupported output
check 2 mojo mutate
grep -qi unsupported output

if [[ "$(uname)" == Linux ]]; then
    cat > bin/kcov <<'TOOL'
#!/usr/bin/env bash
set -eu
while [[ "$1" == --* ]]; do shift; done
if [[ "${KCOV_NO_REPORT:-}" ]]; then exit 0; fi
mkdir -p "$1/report"
printf '<coverage lines-valid="%s"/>\n' "${KCOV_LINES:-2}" > "$1/report/cobertura.xml"
exit "${KCOV_STATUS:-0}"
TOOL
    chmod +x bin/kcov
    check 0 mojo coverage -I src test/test_z.mojo -- --skip flaky
    grep -q 'tmp/coverage/.*cobertura.xml' output
    KCOV_LINES=0 check 2 mojo coverage test/test_z.mojo
    grep -q 'no measured' output
    KCOV_NO_REPORT=1 check 2 mojo coverage test/test_z.mojo
    grep -q 'did not write a Cobertura report' output
    KCOV_STATUS=17 check 17 mojo coverage test/test_z.mojo
    KCOV_STATUS=255 KCOV_LINES=0 check 2 mojo coverage test/test_z.mojo
    grep -q 'permission to trace' output
fi

cat > bin/jscpd <<'TOOL'
#!/usr/bin/env bash
set -eu
test -f src/one.mojo
test ! -e src/ignored.mojo
test ! -L src/dangling.mojo
test ! -e build/generated.mojo
while [[ "$1" != --output ]]; do shift; done
mkdir -p "$2"
cat > "$2/jscpd-report.json" <<'REPORT'
{"duplicates":[{"tokens":40,"firstFile":{"name":"src/one.mojo","start":1,"end":5},"secondFile":{"name":"src/two.mojo","start":1,"end":5}}]}
REPORT
exit "${JSCPD_STATUS:-0}"
TOOL
chmod +x bin/jscpd
mkdir build
printf 'one\n' > src/one.mojo
printf 'two\n' > src/two.mojo
touch src/ignored.mojo build/generated.mojo
printf 'src/ignored.mojo\n' > .gitignore
git add .gitignore src/one.mojo src/two.mojo
GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name GIT_CONFIG_VALUE_0=Codex \
GIT_CONFIG_KEY_1=user.email GIT_CONFIG_VALUE_1=codex@openai.com git commit -qm base
test "$(git log -1 --format='%an <%ae>%n%cn <%ce>')" = $'Codex <codex@openai.com>\nCodex <codex@openai.com>'
ln -s missing.mojo src/dangling.mojo
check 0 mojo dry
check 1 mojo dry --all
grep -Fq 'src/two.mojo:1-5' output
printf 'changed\n' >> src/one.mojo
check 1 mojo dry
grep -Fq 'src/one.mojo:1-5 (changed)' output
JSCPD_STATUS=4 check 2 mojo dry --all
