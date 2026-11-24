# C and C++ tooling

Run `kevin cpp` from the project directory, beside `CMakeLists.txt` or the
solution. It needs Babashka, uv (gcovr, lizard) and Node with `npx` (jscpd).
Reports and build trees go under `./tmp/`. kevin-cpp installs nothing into the
project and never edits its files.

Baseline:

- Windows: Visual Studio 2022 (MSVC toolset v143) or later. kevin-cpp finds it
  with vswhere and exits 2 on an older one.
- Linux and macOS: gcc or clang.
- C++17 or later.

## Build system

`CMakeLists.txt` in the current directory selects CMake (cmake 3.21 or later
on PATH). Otherwise one `.sln` or `.slnx`, or one `.vcxproj` when there is no
solution, selects MSBuild. Pass `--build-system cmake|msbuild` when both exist;
CMake wins by default. MSBuild runs on Windows only.

Tests use any framework ctest runs (CMake), or the Microsoft
CppUnitTestFramework in `NativeUnitTestProject` projects (MSBuild). Every build
uses the `Debug` configuration.

## Commands

| Command | Behavior |
|---|---|
| `tools` | Prints the build system and the version or absence of cmake or Visual Studio, the compiler, uv, npx, gcov, gcovr, OpenCppCoverage, lizard, jscpd and Mull. Exits 2 only when the build system is missing. |
| `test [--preset NAME] [-- args]` | CMake: configures (`cmake --preset NAME -B tmp/build/test`, or `cmake -S . -B tmp/build/test`), builds, then runs `ctest --output-on-failure` with the args. MSBuild: builds, asks the solution for its projects through an entry project in `tmp/msbuild/`, asks MSBuild for each project's `ProjectSubType` and `TargetPath`, and runs `vstest.console` on the `NativeUnitTestProject` outputs with the args. |
| `property` | Unsupported; exits 2. |
| `coverage [--tool opencppcoverage\|gcov] [--include-tests] [--preset NAME] [-- args]` | Runs `test` under the backend and writes `tmp/coverage/coverage.cobertura.xml`. The default backend is OpenCppCoverage on Windows and gcov elsewhere. |
| `crap [--all] [--base REF] [--no-executable-code PATH ...]` | CRAP per changed function: `comp² × (1 − cov)³ + comp`. Fails above 10. |
| `dry [--all] [--base REF] [--min-tokens N] [--report-only]` | jscpd clones touching changed lines, over every C and C++ extension; report in `tmp/jscpd/`. |
| `mutate [--base REF] [--all] [-- mull args]` | Mull on the diff since the merge base; exit 1 on surviving mutants. |

Exit status is 0 when clean, 1 on findings, failing builds or failing tests,
and 2 on usage, input, host or prerequisite errors, which print a `kevin-cpp:`
message on stderr. "Changed" means the C and C++ changes since the merge base
with `--base` (default `KEVIN_BASE`, `main`, then `master`), committed or not,
plus untracked C and C++ files; `--all` drops the scope. C and C++ files end in
`.c`, `.cc`, `.cpp`, `.cxx`, `.c++`, `.h`, `.hh`, `.hpp`, `.hxx`, `.ixx` or
`.inl`.

## Coverage

Coverage measures the repository's C and C++ sources outside `tmp/` and
outside any directory named `test` or `tests`. `--include-tests` keeps those
directories. A failing test run exits 1 and still writes the report.

- gcov: builds into `tmp/build/coverage` with `--coverage`, runs ctest, then
  `uvx gcovr@8.4`. A clang build reads its data with `llvm-cov gcov`. CMake
  only.
- opencppcoverage (Windows): `OpenCppCoverage` on PATH, covering child
  processes. A report without data exits 2.

dotnet-coverage is not a backend: it collects no native C++ coverage.

## CRAP

Complexity is lizard's cyclomatic complexity (`uvx lizard@1.17.31`) for every
backend. A function's coverage is the share of the report's coverable lines
inside lizard's line span that ran; a function with no coverable line counts
as uncovered. Test sources join the scope only when the report covers them.

A source in scope without coverage data prints `UNMEASURED source:` and exits
2. Name each declaration-only header with `--no-executable-code PATH`; it
prints `EXEMPT no executable code:`. With no report, run `coverage` first.

Review mixed-duty files with the `cc` column of `crap --all`.

## Mutation

Mull runs only with clang, a matching `mull-runner-<clang major>` on PATH with
`mull-ir-frontend-<major>` in the `lib/` beside its `bin/`, and a CMake
project. kevin-cpp builds into `tmp/build/mutate` with the Mull IR frontend
plugin, writes `tmp/mull/mull.yml` (diff against the merge base, sources
outside `tmp/` and test directories), and runs `mull-runner` on each test
ctest lists. A mutant any test kills counts as killed. Keep mutation
differential: do not pass `--all`.

Any other setup (gcc, MSVC, MSBuild, no Mull) exits 2 with "mutation
unsupported in this setup" and the reason. Record
`Not-tested: mutation: unsupported in this setup, <reason>` and continue.

## Security scanners

Run every scanner the setup can run, on the changed files. Record each as
`Tested` or `Not-tested: <scanner>: <reason>`.

- clang-tidy, after a CMake configure with
  `-DCMAKE_EXPORT_COMPILE_COMMANDS=ON` into `tmp/build/scan`:
  `uvx --from clang-tidy==21.1.1 clang-tidy -p tmp/build/scan --checks='-*,clang-analyzer-security.*,cert-*,clang-diagnostic-format-security' --extra-arg=-Wformat-security --warnings-as-errors='*' <changed files>`.
  Exit 0 is clean and 1 is findings.
- MSVC code analysis (Windows): build with `/analyze`, for example
  `cmake -S . -B tmp/build/analyze "-DCMAKE_CXX_FLAGS=/EHsc /analyze /W4 /WX"` then
  `cmake --build tmp/build/analyze`, or MSBuild with
  `-p:EnablePREfast=true -p:TreatWarningAsError=true`. In Git Bash, write
  the options with `-` (`-analyze`): Git Bash turns `/analyze` into a path.
- CodeQL CLI with the `codeql/cpp-queries` security-extended suite:
  `codeql pack download codeql/cpp-queries`, then
  `codeql database create tmp/codeql-db --language=cpp --command="<build command>"`
  and
  `codeql database analyze tmp/codeql-db codeql/cpp-queries:codeql-suites/cpp-security-extended.qls --format=sarif-latest --output=tmp/codeql.sarif`.
  The CodeQL CLI license allows free use on open-source code only; on other
  code, record `Not-tested: codeql: license`.
- Semgrep: follow the [shared Semgrep setup](semgrep.md) with
  `--config tmp/semgrep-rules/c`, on the changed `.c` and `.h` files only. The
  pinned rules do not match `.cpp` files.
- Dependency audit: package management is the project's. Unless the project
  configures an audit, add `Dependency audit absent: <reason>` to
  `project.md`.
