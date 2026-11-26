#!/usr/bin/env bash
# End-to-end check of kevin-cpp with the real tools on the CMake project in
# test/cpp_cmake_project. Needs cmake 3.21+, a C++ compiler, Babashka, uv
# and Node. KEVIN_CPP_E2E selects the host part: linux (default: gcov,
# lizard, jscpd and every refusal), windows (MSVC, MSBuild, OpenCppCoverage,
# /analyze) or mull (clang and Mull).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
KEVIN_CPP="$HERE/kevin/bin/kevin-cpp"
PART="${KEVIN_CPP_E2E:-linux}"

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }
assert_match() { if grep -Eq -- "$3" <<<"$2"; then pass "$1"; else fail "$1: output does not match /$3/"; fi; }
assert_no_match() { if grep -Eq -- "$3" <<<"$2"; then fail "$1: output matches /$3/"; else pass "$1"; fi; }
assert_status() { if [ "$2" -eq "$3" ]; then pass "$1"; else fail "$1: expected exit $3, got $2"; fi; }

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export GIT_CONFIG_GLOBAL="$scratch/gitconfig"
git config --global user.name tester
git config --global user.email tester@example.com
git config --global init.defaultBranch main
export KEVIN_BASE=main

run() { # sets $out and $status
  status=0
  out="$("$@" 2>&1)" || status=$?
  echo "$out" | sed 's/^/     /'
}

# A git project on branch work whose base, main, holds `files` from `fixture`.
new_project() {
  project="$scratch/$1"
  mkdir -p "$project"
  cp -R "$HERE/test/$2/." "$project/"
  cd "$project"
  git init -q .
  git add -A && git commit -q -m base
  git checkout -q -b work
}

linux() {
  new_project cmake cpp_cmake_project

  run "$KEVIN_CPP" tools
  assert_status "tools" "$status" 0
  assert_match "tools names the build system" "$out" "build system: cmake"
  run "$KEVIN_CPP" test
  assert_status "test" "$status" 0
  run "$KEVIN_CPP" test --preset dev
  assert_status "test --preset dev" "$status" 0
  test -d tmp/build/test && pass "the preset build lands in tmp/" || fail "no tmp/build/test"
  test ! -d build && pass "the preset's own binaryDir stays unused" || fail "build/ was written"

  run "$KEVIN_CPP" coverage
  assert_status "coverage with gcov" "$status" 0
  run "$KEVIN_CPP" crap --all
  assert_status "crap reports the declaration-only header" "$status" 2
  assert_match "crap names it" "$out" "UNMEASURED source: src/sign.hpp"
  run "$KEVIN_CPP" crap --all --no-executable-code src/sign.hpp
  assert_status "crap --all" "$status" 0
  assert_match "the three-way branch is partly covered" "$out" "cc   3  cov  50%  src/sign.cpp:3 sign"
  assert_no_match "test sources stay out" "$out" "test/sign_test.cpp"
  run "$KEVIN_CPP" coverage --include-tests
  run "$KEVIN_CPP" crap --all --no-executable-code src/sign.hpp
  assert_match "--include-tests adds the test sources" "$out" "test/sign_test.cpp"

  sed -i.bak 's/add_test(NAME sign_positive COMMAND sign_test positive)/&\nadd_test(NAME sign_negative COMMAND sign_test negative)\nadd_test(NAME sign_zero COMMAND sign_test zero)/' CMakeLists.txt
  rm CMakeLists.txt.bak
  run "$KEVIN_CPP" coverage
  run "$KEVIN_CPP" crap --all --no-executable-code src/sign.hpp
  assert_match "a fresh report covers every side" "$out" "cc   3  cov 100%  src/sign.cpp:3 sign"
  git checkout -q CMakeLists.txt

  sed -i.bak 's/return 1;/return 2;/' src/sign.cpp && rm src/sign.cpp.bak
  run "$KEVIN_CPP" test
  assert_status "a failing test" "$status" 1
  sed -i.bak 's/return 2;/return 2/' src/sign.cpp && rm src/sign.cpp.bak
  run "$KEVIN_CPP" test
  assert_status "a compile error" "$status" 1
  git checkout -q src/sign.cpp

  sed -n '3,10p' src/sign.cpp | sed 's/sign(/sign_copy(/' > src/copy.hpp
  run "$KEVIN_CPP" dry --all --min-tokens 20
  assert_status "dry --all finds the copy" "$status" 1
  assert_match "dry names both sides" "$out" "(src/sign.cpp:.*src/copy.hpp:|src/copy.hpp:.*src/sign.cpp:)"

  for args in "property" "mutate" "test --build-system msbuild" "coverage --tool dotnet-coverage" "coverage --tool opencppcoverage"; do
    # shellcheck disable=SC2086
    run "$KEVIN_CPP" $args
    assert_status "$args is refused" "$status" 2
  done

  mkdir -p "$scratch/empty" && cd "$scratch/empty"
  run "$KEVIN_CPP" test
  assert_status "a directory with nothing to build" "$status" 2
}

windows() {
  new_project cmake cpp_cmake_project

  run "$KEVIN_CPP" tools
  assert_status "tools (CMake + MSVC)" "$status" 0
  run "$KEVIN_CPP" test --preset dev
  assert_status "test with ctest" "$status" 0
  run "$KEVIN_CPP" coverage
  assert_status "coverage with the default OpenCppCoverage (CMake)" "$status" 0
  assert_match "the Windows default is OpenCppCoverage" "$out" "OpenCppCoverage --modules"
  run "$KEVIN_CPP" crap --all --no-executable-code src/sign.hpp
  assert_status "crap from OpenCppCoverage (CMake)" "$status" 0
  assert_match "crap scores sign from OpenCppCoverage" "$out" "cc   3  cov +[0-9]+%  src/sign.cpp:3 sign"
  run "$KEVIN_CPP" coverage --tool dotnet-coverage
  assert_status "coverage --tool dotnet-coverage is refused" "$status" 2
  run "$KEVIN_CPP" dry --all
  assert_status "dry" "$status" 0
  run "$KEVIN_CPP" mutate
  assert_status "mutate on MSVC" "$status" 2
  # cl takes -opt for /opt; Git Bash rewrites /analyze into a path.
  run cmake -S . -B tmp/build/analyze "-DCMAKE_CXX_FLAGS=-EHsc -analyze -W4 -WX"
  run cmake --build tmp/build/analyze --config Debug
  assert_status "MSVC /analyze build" "$status" 0

  new_project msbuild cpp_msbuild_project

  run "$KEVIN_CPP" tools
  assert_match "tools picks the solution" "$out" "build system: msbuild Sign.sln"
  run "$KEVIN_CPP" test
  assert_status "test with vstest.console" "$status" 0
  run "$KEVIN_CPP" coverage --tool opencppcoverage
  assert_status "coverage --tool opencppcoverage (MSBuild)" "$status" 0
  run "$KEVIN_CPP" crap --all --no-executable-code Sign/src/sign.hpp
  assert_status "crap from OpenCppCoverage (MSBuild)" "$status" 0
  assert_match "crap scores sign from OpenCppCoverage (MSBuild)" "$out" "Sign/src/sign.cpp:3 sign"
  run "$KEVIN_CPP" mutate
  assert_status "mutate on MSBuild" "$status" 2
}

mull() {
  new_project mull cpp_cmake_project
  cat >> CMakeLists.txt <<'EOF'
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/default.marker" "")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/explicit/explicit.marker" "")
add_test(NAME sign_default COMMAND sign_test positive default)
set_tests_properties(sign_default PROPERTIES ENVIRONMENT "KEVIN_CPP_TEST_CONTEXT=default")
add_test(NAME sign_disabled COMMAND sign_test positive default)
set_tests_properties(sign_disabled PROPERTIES DISABLED TRUE)
add_test(NAME sign_expected_failure COMMAND sign_test positive explicit)
set_tests_properties(sign_expected_failure PROPERTIES
  WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/explicit"
  ENVIRONMENT "KEVIN_CPP_TEST_CONTEXT=explicit"
  WILL_FAIL TRUE)
EOF

  run "$KEVIN_CPP" test
  assert_status "CTest passes with its own environment and working directories" "$status" 0
  run tmp/build/test/sign_test positive default
  assert_status "direct execution lacks CTest context" "$status" 1

  run "$KEVIN_CPP" mutate --all -- --workers 4 --no-cache
  assert_match "Mull ran and scored the mutants" "$out" "mutation score"
  [ "$status" -le 1 ] && pass "mutate --all ends with a verdict" || fail "mutate --all exited $status"
  for name in sign_default sign_expected_failure; do
    if [ -f "tmp/mull/$name.json" ]; then
      assert_match "$name kills a mutant through CTest" "$(cat "tmp/mull/$name.json")" '"status"[[:space:]]*:[[:space:]]*"Killed"'
    else
      fail "$name produced no Mull report"
    fi
  done
  test ! -e tmp/mull/sign_disabled.json && pass "Mull skips the disabled test" || fail "Mull ran the disabled test"

  run "$KEVIN_CPP" mutate -- --workers 4 --no-cache
  assert_match "mutate on an unchanged source scores no mutant" "$out" "no mutants"
}

"$PART"

[ "$failures" -eq 0 ] && echo "all kevin-cpp $PART end-to-end checks passed"
exit "$failures"
